package org.futo.inputmethod.latin.uix.settings

import android.app.Activity
import android.app.PendingIntent.FLAG_MUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.PendingIntent.getBroadcast
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
//import okhttp3.OkHttpClient
//import okhttp3.Request
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.InfoDialog
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.updates.InstallReceiver
import org.futo.inputmethod.updates.LAST_UPDATE_CHECK_FAILED
import org.futo.inputmethod.updates.LAST_UPDATE_CHECK_RESULT
import org.futo.inputmethod.updates.SKIPPED_UPDATE_VERSION
import org.futo.inputmethod.updates.UpdateAttempts
import org.futo.inputmethod.updates.UpdateResult
import org.futo.inputmethod.updates.UpdateVerify
import org.futo.inputmethod.updates.openURI
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream
import java.io.OutputStream

@Composable
fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private fun InputStream.copyToOutputStream(inputStreamLength: Long, outputStream: OutputStream, onProgress: (Float) -> Unit) {
    val buffer = ByteArray(16384);
    var n: Int;
    var total = 0;
    val inputStreamLengthFloat = inputStreamLength.toFloat();

    while (read(buffer).also { n = it } >= 0) {
        total += n;
        outputStream.write(buffer, 0, n);
        onProgress.invoke(total.toFloat() / inputStreamLengthFloat);
    }
}


object UpdateStatus {
    val isDownloading = mutableStateOf(false)
    val downloadText = mutableStateOf("")

    /** WrenBoard 1.2: the words under the prompt belong to one attempt at a time (see UpdateAttempts). */
    val attempts = UpdateAttempts(publish = { words ->
        if (Looper.myLooper() == Looper.getMainLooper()) downloadText.value = words
        else Handler(Looper.getMainLooper()).post { downloadText.value = words }
    })

    var downloadedUpdate: ByteArrayOutputStream? = null

    /** WrenBoard 1.2: the version Settings already prompted for in this app session, so a dismissed
     *  prompt does not reopen itself every time the Settings screen is rebuilt. */
    @Volatile var promptedVersion: Int = -1
}

private suspend fun install(context: Context, attempt: Long, inputStream: InputStream, dataLength: Long) {
    var lastProgressText = "";
    var session: PackageInstaller.Session? = null;

    try {
        // WrenBoard 1.2: the installer's answer comes back through InstallReceiver with this attempt's token
        // (1.1 prefixed every answer, success included, with "Fatal error:" and never cleared it).
        val packageInstaller: PackageInstaller = context.packageManager.packageInstaller;
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        val sessionId = packageInstaller.createSession(params);
        session = packageInstaller.openSession(sessionId)

        // WrenBoard 1.2: stream the download straight into the install session (the release APK is
        // about 130 MB; holding it in memory first risks running out of memory on the phone).
        session.openWrite("package", 0, dataLength).use { sessionStream ->
            inputStream.copyToOutputStream(dataLength, sessionStream) { progress ->
                val progressText = "${(progress * 100.0f).toInt()}%";
                if (lastProgressText != progressText) {
                    lastProgressText = progressText

                    UpdateStatus.attempts.post(attempt, "Preparing install... $progressText")
                }
            }
            session.fsync(sessionStream)
        };

        val intent = Intent(context, InstallReceiver::class.java)
            .putExtra(InstallReceiver.EXTRA_ATTEMPT, attempt);
        // One PendingIntent per attempt (its own request code), so an older session's result keeps its own token.
        val pendingIntent = getBroadcast(context, (attempt and 0x7fffffffL).toInt(), intent, FLAG_MUTABLE or FLAG_UPDATE_CURRENT);
        val statusReceiver = pendingIntent.intentSender;

        session.commit(statusReceiver);
        session.close();

        UpdateStatus.attempts.handOver(attempt)
        UpdateStatus.attempts.post(attempt, "Installing update")
    } catch (e: Throwable) {
        Log.w("UpdateScreen", "Exception thrown while downloading and installing latest version of app.", e);
        session?.abandon();
        UpdateStatus.attempts.fail(attempt, "Update failed.")
    }
}

@DelicateCoroutinesApi
private fun downloadAndInstall(context: Context, updateResult: UpdateResult) {
    // WrenBoard 1.10: one download at a time, also while the finished download waits on Android's install
    // confirmation (the button used to come back at that moment and a second tap started a second download).
    if (UpdateStatus.isDownloading.value || UpdateStatus.attempts.isBusy()) return
    val attempt = UpdateStatus.attempts.start()
    UpdateStatus.isDownloading.value = true
    Log.i("UpdateScreen", "Update attempt $attempt started")

    GlobalScope.launch(Dispatchers.IO) {
        var file: java.io.File? = null
        try {
            // WrenBoard 1.6 (item 11, BingeMote's checks): download the release APK to the app's cache only from
            // WrenBoard's GitHub releases (HTTPS, redirects only inside GitHub), check its size and published
            // checksum, then check it is WrenBoard, newer, and signed by the same key as this app before Android's
            // installer sees it. Any failed check deletes the file and says why.
            UpdateStatus.downloadedUpdate = null
            UpdateStatus.attempts.post(attempt, "Downloading... 0%")
            var lastProgressText = ""
            val downloaded = UpdateVerify.download(context, updateResult) { progress ->
                val progressText = "${(progress * 100.0f).toInt()}%"
                if (lastProgressText != progressText) {
                    lastProgressText = progressText
                    UpdateStatus.attempts.post(attempt, "Downloading... $progressText")
                }
            }
            file = downloaded
            UpdateStatus.attempts.post(attempt, "Checking the download")
            UpdateVerify.verify(context, downloaded)
            downloaded.inputStream().use { input ->
                install(context, attempt, input, downloaded.length())
            }
        } catch (e: UpdateVerify.Failure) {
            Log.w("UpdateScreen", "Update attempt $attempt stopped: ${e.msg}")
            UpdateStatus.attempts.fail(attempt, e.msg)
        } catch (e: Throwable) {
            Log.w("UpdateScreen", "Exception thrown while downloading and installing latest version of app.", e)
            // Stay in the app and say what failed (1.1 threw here and opened the browser).
            UpdateStatus.attempts.fail(attempt, "Download failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { file?.delete() } catch (_: Throwable) { }
            UpdateStatus.attempts.downloadEnded(attempt)
            withContext(Dispatchers.Main) { UpdateStatus.isDownloading.value = false }
        }
    }
}

@Composable
fun UpdateDialog(navController: NavHostController) {
    val scope = LocalLifecycleOwner.current
    val context = LocalContext.current
    // WrenBoard 1.3.1: read the same live DataStore as the Updates card (useDataStore), not the
    // DataStoreHelper copy that only the keyboard service fills. With WrenBoard not selected as the
    // keyboard, that copy is empty and the dialog claimed "Up-to-date" over a card offering the new
    // version. The dialog now recomposes when a check writes its result.
    val (updateInfo, _) = useDataStore(key = LAST_UPDATE_CHECK_RESULT, default = "")
    val (lastCheckFailed, _) = useDataStore(key = LAST_UPDATE_CHECK_FAILED, default = false)

    val lastUpdateResult = if(!LocalInspectionMode.current){
        remember(updateInfo) { UpdateResult.fromString(updateInfo) }
    } else {
        UpdateResult(123, "abc", "1.2.3")
    }

    val isDownloading = UpdateStatus.isDownloading
    val statusText = UpdateStatus.downloadText
    // WrenBoard 1.2: a newly opened prompt starts clean; an earlier cancelled or failed attempt's words are gone and
    // its late installer result can no longer land here. A download that is still running keeps its progress.
    remember { UpdateStatus.attempts.openPrompt() }
    val showSpinner = statusText.value.isEmpty() || statusText.value.endsWith("%")

    if(lastUpdateResult == null || !lastUpdateResult.isNewer()) {
        if(lastCheckFailed) {
            // The check failed (no network, a server error, or no release published yet): do not claim "up to date".
            InfoDialog(title = "Update check failed", body = "WrenBoard could not confirm whether an update is available. There may be no network, or no release is published yet. Use \"Check for updates manually\" in Settings to check again.", navController = navController)
        } else if(updateInfo.isEmpty()) {
            // WrenBoard 1.3.1: no completed check in the store (the caller just kicked one off). Never
            // claim up-to-date on empty data; the real verdict replaces this when the result lands.
            InfoDialog(title = "Checking for updates...", body = "The update check is running. Its result appears here when it lands.", navController = navController)
        } else {
            InfoDialog(title = "Up-to-date", body = "As of the last update check, the app is up to date.", navController = navController)
        }
    } else {
        AlertDialog(
            icon = {
                Icon(Icons.Filled.Info, contentDescription = "Info")
            },
            title = {
                Text(text = "Update available")
            },
            text = {
                if(isDownloading.value) {
                    KeepScreenOn()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if(showSpinner) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(text = statusText.value)
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "A new version ${lastUpdateResult.nextVersionString} is available, would you like to update?")
                        if (statusText.value.isNotEmpty()) Text(text = statusText.value)
                    }
                }
            },
            onDismissRequest = {
                // WrenBoard 1.2: Back always closes the dialog (a running download carries on and the
                // install prompt still appears when it finishes).
                navController.navigateUp()
            },
            confirmButton = {
                TextButton(onClick = {
                    downloadAndInstall(context.applicationContext, lastUpdateResult)
                }, enabled = !isDownloading.value && !(statusText.value.isNotEmpty() && UpdateStatus.attempts.isBusy())) {
                    Text(stringResource(R.string.autoupdater_perform_update))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.lifecycleScope.launch {
                            context.setSetting(SKIPPED_UPDATE_VERSION, lastUpdateResult.nextVersion)
                        }
                        navController.navigateUp()
                    }, enabled = !isDownloading.value) {
                        Text(stringResource(R.string.updates_card_skip))
                    }
                    TextButton(onClick = {
                        navController.navigateUp()
                    }) {
                        Text(stringResource(R.string.autoupdater_dismiss_update))
                    }
                }
            }
        )
    }
}