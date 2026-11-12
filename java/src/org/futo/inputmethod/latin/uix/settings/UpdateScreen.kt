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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
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

    /** WrenBoard 1.10: download progress 0..1 for the card's bar. */
    val progress = mutableStateOf(0f)
    /** The version whose verified APK sits in the update folder (-1 = none), so Update never downloads it twice. */
    @Volatile var readyVersion: Int = -1
    /** Verified file waiting for the person to allow installs from WrenBoard; installed when they return. */
    @Volatile var pendingApk: java.io.File? = null
}

/**
 * WrenBoard 1.10: hand the verified APK to Android's own install screen (the same hand-off BingeMote and Listen
 * Later use). 1.9 streamed the file into a background install session; the phone then installed it silently and
 * killed Settings with no message, and the Update button came back in between so a second tap downloaded it again.
 */
private fun launchInstaller(context: Context, f: java.io.File): Boolean = try {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", f)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
} catch (e: Throwable) {
    Log.w("UpdateScreen", "Could not open the installer.", e)
    false
}

private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
}

/** The file for [updateResult] if it is already downloaded and still passes every check, else null. */
private fun verifiedDownload(context: Context, updateResult: UpdateResult): java.io.File? {
    if (UpdateStatus.readyVersion != updateResult.nextVersion) return null
    val f = java.io.File(UpdateVerify.updateDir(context), "WrenBoard-update.apk")
    if (!f.exists()) return null
    return try { UpdateVerify.verify(context, f); f } catch (e: Throwable) { try { f.delete() } catch (_: Throwable) { }; null }
}

@DelicateCoroutinesApi
private fun downloadAndInstall(context: Context, updateResult: UpdateResult, onHandedOver: () -> Unit) {
    if (UpdateStatus.isDownloading.value) return
    val attempt = UpdateStatus.attempts.start()
    UpdateStatus.isDownloading.value = true
    UpdateStatus.progress.value = 0f
    Log.i("UpdateScreen", "Update attempt $attempt started")

    GlobalScope.launch(Dispatchers.IO) {
        var failed = true
        var file: java.io.File? = null
        try {
            // WrenBoard 1.6 (item 11, BingeMote's checks): download only from WrenBoard's GitHub releases, check size and
            // published checksum, then check it is WrenBoard, newer, and signed by the same key as this app.
            // WrenBoard 1.10: a file already downloaded and verified for this version is reused, never fetched twice.
            file = verifiedDownload(context, updateResult)
            if (file == null) {
                UpdateStatus.attempts.post(attempt, "Downloading... 0%")
                var lastProgressText = ""
                file = UpdateVerify.download(context, updateResult) { progress ->
                    runOnMain { UpdateStatus.progress.value = progress.coerceIn(0f, 1f) }
                    val progressText = "${(progress * 100.0f).toInt()}%"
                    if (lastProgressText != progressText) {
                        lastProgressText = progressText
                        UpdateStatus.attempts.post(attempt, "Downloading... $progressText")
                    }
                }
                UpdateStatus.attempts.post(attempt, "Checking the download")
                UpdateVerify.verify(context, file)
            }
            UpdateStatus.readyVersion = updateResult.nextVersion
            if (context.packageManager.canRequestPackageInstalls()) {
                if (launchInstaller(context, file)) {
                    failed = false
                    UpdateStatus.attempts.post(attempt, "")
                    runOnMain { onHandedOver() }
                } else {
                    UpdateStatus.attempts.fail(attempt, "Could not open the installer.")
                }
            } else {
                // Android needs "Allow from this source" once. Keep the verified file; the install starts when the
                // person comes back from that screen.
                failed = false
                UpdateStatus.pendingApk = file
                UpdateStatus.attempts.post(attempt, "Turn on Allow, then come back.")
                try {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Throwable) {
                    UpdateStatus.pendingApk = null
                    failed = true
                    UpdateStatus.attempts.fail(attempt, "Could not open the install permission screen.")
                }
            }
        } catch (e: UpdateVerify.Failure) {
            Log.w("UpdateScreen", "Update attempt $attempt stopped: ${e.msg}")
            UpdateStatus.attempts.fail(attempt, e.msg)
        } catch (e: Throwable) {
            Log.w("UpdateScreen", "Exception thrown while downloading the latest version of the app.", e)
            UpdateStatus.attempts.fail(attempt, "Download failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            // A failed or stopped download is deleted; a good one stays for the installer to read.
            if (failed) { try { file?.delete() } catch (_: Throwable) { }; UpdateStatus.readyVersion = -1 }
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
        val appContext = context.applicationContext
        // WrenBoard 1.10: back from Android's "allow installs" screen, install the file already verified.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    val f = UpdateStatus.pendingApk
                    if (f != null && appContext.packageManager.canRequestPackageInstalls()) {
                        UpdateStatus.pendingApk = null
                        if (launchInstaller(appContext, f)) navController.navigateUp()
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        AlertDialog(
            title = {
                Text(text = "WrenBoard ${lastUpdateResult.nextVersionString} is available")
            },
            text = {
                if(isDownloading.value) {
                    KeepScreenOn()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { UpdateStatus.progress.value },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = statusText.value)
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "You have ${UpdateResult.currentVersionString()}.")
                        if (lastUpdateResult.notes.isNotEmpty()) {
                            // Long notes scroll inside this box, so the buttons never get pushed off.
                            val maxNotes = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.38f).dp
                            Column(Modifier.heightIn(max = maxNotes).verticalScroll(rememberScrollState())) {
                                lastUpdateResult.notes.forEach {
                                    if (it.startsWith("## ")) {
                                        Text(it.drop(3), fontWeight = FontWeight.Bold)
                                    } else {
                                        Text("\u2022 $it")
                                    }
                                }
                            }
                        }
                        Text(text = "Android will ask you to confirm the install.")
                        if (statusText.value.isNotEmpty()) Text(text = statusText.value)
                    }
                }
            },
            onDismissRequest = {
                // Back always closes the card (a running download carries on and the install screen still opens when it finishes).
                navController.navigateUp()
            },
            confirmButton = {
                TextButton(onClick = {
                    downloadAndInstall(appContext, lastUpdateResult) { navController.navigateUp() }
                }, enabled = !isDownloading.value) {
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
