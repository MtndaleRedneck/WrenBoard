package org.futo.inputmethod.latin.uix.settings

import android.app.Activity
import android.app.PendingIntent.FLAG_MUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.PendingIntent.getBroadcast
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
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
import kotlinx.coroutines.runBlocking
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
import org.futo.inputmethod.updates.UpdateResult
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

    var downloadedUpdate: ByteArrayOutputStream? = null
}

private suspend fun install(scope: CoroutineScope, context: Context, inputStream: InputStream, dataLength: Long, updateStatusText: (String) -> Unit) {
    var lastProgressText = "";
    var session: PackageInstaller.Session? = null;

    try {
        //Log.i("UpdateScreen", "Hooked InstallReceiver.onReceiveResult.")
        InstallReceiver.onReceiveResult.onEach { message -> updateStatusText("Fatal error: $message") }.launchIn(scope)

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

                    updateStatusText("Downloading... $progressText")
                }
            }
            session.fsync(sessionStream)
        };

        val intent = Intent(context, InstallReceiver::class.java);
        val pendingIntent = getBroadcast(context, 0, intent, FLAG_MUTABLE or FLAG_UPDATE_CURRENT);
        val statusReceiver = pendingIntent.intentSender;

        session.commit(statusReceiver);
        session.close();

        withContext(Dispatchers.Main) {
            updateStatusText("Installing update")
            //_textProgress.text = "";
            //_text.text = context.resources.getText(R.string.installing_update);
        }
    } catch (e: Throwable) {
        Log.w("UpdateScreen", "Exception thrown while downloading and installing latest version of app.", e);
        session?.abandon();
        withContext(Dispatchers.Main) {
            updateStatusText("Failed to install update")
        }
    } finally {
        withContext(Dispatchers.Main) {
            Log.i("UpdateScreen", "Keep screen on unset install")
            //window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}

@DelicateCoroutinesApi
private suspend fun downloadAndInstall(scope: CoroutineScope, context: Context, updateResult: UpdateResult, updateStatusText: (String) -> Unit) = GlobalScope.launch(Dispatchers.IO) {
    UpdateStatus.isDownloading.value = true

    var inputStream: InputStream? = null
    var connection: HttpURLConnection? = null
    try {
        // WrenBoard 1.2: download the release APK inside the app (GitHub redirects the asset URL to its
        // file host, so redirects are followed by hand), then hand it to Android's installer, which shows
        // the system install prompt. Same release asset the update check found; nothing about the check changes.
        var url = URL(updateResult.apkUrl)
        var hops = 0
        while (true) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "WrenBoard-Update")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = c.responseCode
            if (code in 300..399 && hops < 6) {
                val location = c.getHeaderField("Location") ?: throw Exception("redirect without a location")
                c.disconnect()
                url = URL(url, location)
                hops++
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw Exception("server answered $code")
            }
            connection = c
            break
        }

        val dataLength = connection!!.contentLengthLong
        if (dataLength <= 0L) throw Exception("download size unknown")
        withContext(Dispatchers.Main) { updateStatusText("Downloading... 0%") }
        UpdateStatus.downloadedUpdate = null
        inputStream = connection!!.inputStream
        install(scope, context, inputStream!!, dataLength) { text ->
            scope.launch(Dispatchers.Main) { updateStatusText(text) }
        }
    } catch (e: Throwable) {
        Log.w("UpdateScreen", "Exception thrown while downloading and installing latest version of app.", e)
        withContext(Dispatchers.Main) {
            // Stay in the app and say what failed (1.1 threw here and opened the browser).
            updateStatusText("Download failed: ${e.message ?: e.javaClass.simpleName}")
        }
    } finally {
        try { inputStream?.close() } catch (_: Throwable) { }
        connection?.disconnect()
        UpdateStatus.isDownloading.value = false
    }
}

@Composable
fun UpdateDialog(navController: NavHostController) {
    val scope = LocalLifecycleOwner.current
    val context = LocalContext.current
    val updateInfo = remember { runBlocking {
        context.getSetting(LAST_UPDATE_CHECK_RESULT, "")
    } }

    val lastCheckFailed = remember { runBlocking {
        context.getSetting(LAST_UPDATE_CHECK_FAILED, false)
    } }

    val lastUpdateResult = if(!LocalInspectionMode.current){
        remember { UpdateResult.fromString(updateInfo) }
    } else {
        UpdateResult(123, "abc", "1.2.3")
    }

    val isDownloading = UpdateStatus.isDownloading
    val showSpinner = remember { mutableStateOf(true) }
    val statusText = UpdateStatus.downloadText

    if(lastUpdateResult == null || !lastUpdateResult.isNewer()) {
        if(lastCheckFailed) {
            // The check failed (no network, a server error, or no release published yet): do not claim "up to date".
            InfoDialog(title = "Update check failed", body = "WrenBoard could not confirm whether an update is available. There may be no network, or no release is published yet. Use \"Check for updates manually\" in Settings to open the releases page.", navController = navController)
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
                        if(showSpinner.value) {
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
                    GlobalScope.launch { downloadAndInstall(scope.lifecycleScope, context, lastUpdateResult) {
                        statusText.value = it

                        if(!it.endsWith("%")) {
                            showSpinner.value = false
                        }
                    } }
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