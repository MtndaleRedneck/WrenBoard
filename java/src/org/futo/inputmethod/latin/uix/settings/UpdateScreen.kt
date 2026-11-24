package org.futo.inputmethod.latin.uix.settings

import androidx.compose.ui.unit.dp
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
//import okhttp3.OkHttpClient
//import okhttp3.Request
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.InfoDialog
import androidx.datastore.preferences.core.edit

import org.futo.inputmethod.latin.uix.dataStore
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.futo.inputmethod.updates.InstallReceiver
import org.futo.inputmethod.updates.LAST_UPDATE_CHECK_FAILED
import org.futo.inputmethod.updates.LAST_UPDATE_CHECK_RESULT
import org.futo.inputmethod.updates.PENDING_INSTALL_ATTEMPT
import org.futo.inputmethod.updates.PENDING_INSTALL_SHA256
import org.futo.inputmethod.updates.PENDING_INSTALL_VERSION
import org.futo.inputmethod.updates.SKIPPED_UPDATE_VERSION
import org.futo.inputmethod.updates.UpdateAttempts
import org.futo.inputmethod.updates.UpdateResult
import org.futo.inputmethod.updates.UpdateVerify
import org.futo.inputmethod.updates.openURI

import java.io.File
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
/** Owned marker retirement: the caller's attempt holds the tracker claim, so unconditional
 *  removal is safe; one edit keeps the three keys atomic. */
private suspend fun Context.retirePendingInstallMarker() {
    dataStore.edit { prefs ->
        prefs.remove(PENDING_INSTALL_VERSION)
        prefs.remove(PENDING_INSTALL_SHA256)
        prefs.remove(PENDING_INSTALL_ATTEMPT)
    }
}

/** Deferred retirement after a freed hand-over: removes the marker only while it still belongs
 *  to [attempt]. A newer attempt's marker (written after the tracker was freed) survives. */
private suspend fun Context.retirePendingInstallMarkerIfOwned(attempt: Long) {
    dataStore.edit { prefs ->
        if (prefs[PENDING_INSTALL_ATTEMPT] == attempt) {
            prefs.remove(PENDING_INSTALL_VERSION)
            prefs.remove(PENDING_INSTALL_SHA256)
            prefs.remove(PENDING_INSTALL_ATTEMPT)
        }
    }
}

private fun downloadAndInstall(context: Context, updateResult: UpdateResult, onHandedOver: () -> Unit) {
    // WrenBoard 1.10 (review M1): the tracker, not only the progress flag, gates a new attempt, so no second
    // download starts while a download runs or a handed-over install is still inside its window.
    if (UpdateStatus.attempts.isBusy()) return
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
                // Same boundary as the re-check path: the installer launches only after the hand-over was
                // recorded for a still-current attempt. Unlike the re-check refusal (which leaves the file
                // and state to the current attempt), a refusal here falls through to the finally, which
                // deletes the file — possibly the shared verified APK reused via verifiedDownload — and
                // resets readyVersion. Unreachable under the current gated UI (the busy gate holds for the
                // whole download, so the token cannot change mid-attempt); latent for future callers only.
                if (UpdateStatus.attempts.handOver(attempt)) {
                    // A fresh direct hand-over supersedes any staged resume marker (the person
                    // tapped Update again with permission already granted): clear it so a later
                    // resume cannot re-select the old file.
                    context.retirePendingInstallMarker()
                    if (launchInstaller(context, file)) {
                        failed = false
                        UpdateStatus.attempts.post(attempt, "")
                        runOnMain { onHandedOver() }
                    } else {
                        UpdateStatus.attempts.fail(attempt, "Could not open the installer.")
                    }
                }
            } else {
                // Android needs "Allow from this source" once. Keep the verified file; the install starts when the
                // person comes back from that screen.
                failed = false
                UpdateStatus.pendingApk = file
                // Android kills this process when the person grants "Allow from this source"
                // (StorageManagerService kills on the MODE_ALLOWED transition), so the in-memory
                // pendingApk cannot honor "the install starts when the person comes back" by
                // itself. Persist a resume marker: version + the published digest. The marker
                // only SELECTS the staged file for the cold-start recheck; identity, skip state,
                // digest and UpdateVerify's checks all re-run before any hand-over.
                context.dataStore.edit { prefs ->
                    prefs[PENDING_INSTALL_VERSION] = updateResult.nextVersion
                    prefs[PENDING_INSTALL_SHA256] = updateResult.sha256 ?: ""
                    // Attempt identity tags the marker so retirement can be conditional on it
                    // (DataStore edit blocks serialize, so a later attempt's write can never be
                    // erased by an older attempt's deferred clear).
                    prefs[PENDING_INSTALL_ATTEMPT] = attempt
                }
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
        // M1: observable busy for the buttons; refreshed on resume and when a download ends.
        val attemptBusy = remember { mutableStateOf(UpdateStatus.attempts.isBusy()) }
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    val f = UpdateStatus.pendingApk
                    if (f != null && appContext.packageManager.canRequestPackageInstalls()) {
                        // WrenBoard 1.10 hardening: check the file again before handing it over (it sat in cache
                        // meanwhile). The check parses the whole APK, so it runs off the main thread (review I2-1).
                        // I2 review of (17): the attempt is marked running BEFORE the background work starts and the
                        // gate-opening transition happens only after cleanup/launch, so no new attempt can start
                        // mid-re-check and superseded work can never delete the file or launch a second installer.
                        val attempt = UpdateStatus.attempts.currentToken
                        if (UpdateStatus.attempts.beginRecheck(attempt)) {
                            UpdateStatus.pendingApk = null
                            val job = lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                // finally + the completion cleanup below: a scope cancelled between the claim
                                // and this coroutine's first dispatch never runs the body at all, so the
                                // finally alone could leave the tracker busy for good (ChatGPT (18) review).
                                // The completion handler runs even then; downloadEnded is token-checked and
                                // idempotent, so double cleanup is harmless.
                                try {
                                    val ok = try { UpdateVerify.verify(appContext, f); true } catch (e: Throwable) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        // Cleanup first; fail() opens the gate only after it (I2 supersession review).
                                        try { f.delete() } catch (_: Throwable) { }
                                        // Owned cleanup: the durable marker keys this branch's
                                        // staging wrote die with the file they select.
                                        appContext.retirePendingInstallMarker()
                                        UpdateStatus.readyVersion = -1
                                        // Review C2: say why instead of returning to the old words; the person can retry.
                                        UpdateStatus.attempts.fail(attempt, if (e is UpdateVerify.Failure) e.msg else "The downloaded file could not be checked, so it was not installed.")
                                        false
                                    }
                                    if (ok) {
                                        // Lifecycle boundary (I2 ordering review): the coroutine is owned by this
                                        // nav destination's scope; the destination being popped (Back/navigateUp)
                                        // cancels it. Abandon here, before any hand-over or installer launch, so a
                                        // dismissed dialog produces no side effects. (onDispose only removes the
                                        // observer; it does not cancel this job while the destination is alive.)
                                        ensureActive()
                                        // Launch only when the hand-over was recorded for a still-current attempt.
                                        if (UpdateStatus.attempts.handOver(attempt)) {
                                            UpdateStatus.attempts.downloadEnded(attempt)
                                            // Last boundary before the only side effect left; a pop landing
                                            // between here and startActivity is a checked-but-benign UX race
                                            // (the file is verified; the sheet just opens, no install happens
                                            // without the person's confirmation in the system installer).
                                            ensureActive()
                                            if (launchInstaller(appContext, f)) {
                                                // Durable cleanup last (ChatGPT (25) review): the marker
                                                // outlives the in-memory hand-over across process death, so
                                                // retiring it before the sheet was actually up could strand a
                                                // verified file no branch could reselect. Death before this
                                                // point leaves marker + file for the cold-start recovery;
                                                // death after the sheet is up replays the M1 hand-off
                                                // (recovery re-hands the same verified file; Android serializes).
                                                appContext.retirePendingInstallMarker()
                                                withContext(Dispatchers.Main) { navController.navigateUp() }
                                            } else {
                                                // Review C2: the installer did not open; say so and let the person retry.
                                                UpdateStatus.attempts.fail(attempt, "Could not open the installer.")
                                            }
                                        } else {
                                            // Superseded while verifying: leave the file and the state to the current
                                            // attempt; just end our own running mark.
                                            UpdateStatus.attempts.downloadEnded(attempt)
                                        }
                                    }
                                } finally {
                                    UpdateStatus.attempts.downloadEnded(attempt)
                                }
                            }
                            job.invokeOnCompletion { UpdateStatus.attempts.downloadEnded(attempt) }
                        }
                    } else if (f == null && appContext.packageManager.canRequestPackageInstalls()) {
                        // Cold start after the permission-grant process kill: the in-memory
                        // pendingApk is gone, so a persisted marker selects the staged file.
                        // Recovery runs only when THIS destination resumes (the observer's
                        // home), never on a global cold start. The beginRecheck claim covers
                        // the whole select+verify; ALL durable reads and file cleanup run on
                        // Dispatchers.IO inside the claimed coroutine, never on main.
                        val attempt = UpdateStatus.attempts.currentToken
                        if (UpdateStatus.attempts.beginRecheck(attempt)) {
                            val job = lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val markerV = appContext.getSettingBlocking(PENDING_INSTALL_VERSION, -1)
                                    if (markerV != -1) {
                                        val current = UpdateResult.fromString(appContext.getSettingBlocking(LAST_UPDATE_CHECK_RESULT, ""))
                                        val skipped = appContext.getSettingBlocking(SKIPPED_UPDATE_VERSION, -1)
                                        val markerSha = appContext.getSettingBlocking(PENDING_INSTALL_SHA256, "")
                                        val candidate = File(UpdateVerify.updateDir(appContext), "WrenBoard-update.apk")
                                        // Honor the marker only while it still names the CURRENT
                                        // update (version-based, account-independent identity),
                                        // the person has not skipped that version, the recorded
                                        // digest is the current release's digest, and the file is
                                        // present. Anything else is stale: clean up once.
                                        val identityOk = current != null && current.nextVersion == markerV
                                                && markerV != skipped && markerSha.isNotEmpty()
                                                && markerSha == current.sha256 && candidate.exists()
                                        if (!identityOk) {
                                            try { candidate.delete() } catch (_: Throwable) { }
                                            appContext.retirePendingInstallMarker()
                                        } else {
                                            val ok = try { UpdateVerify.verify(appContext, candidate, markerSha); true } catch (e: Throwable) {
                                                if (e is kotlinx.coroutines.CancellationException) throw e
                                                try { candidate.delete() } catch (_: Throwable) { }
                                                appContext.retirePendingInstallMarker()
                                                UpdateStatus.readyVersion = -1
                                                UpdateStatus.attempts.fail(attempt, if (e is UpdateVerify.Failure) e.msg else "The downloaded file could not be checked, so it was not installed.")
                                                false
                                            }
                                            if (ok) {
                                                // Claim boundary: the durable marker is kept until
                                                // the installer sheet is actually up (ChatGPT (25)
                                                // review). Cancellation or process death anywhere
                                                // before the successful launch retains marker + file,
                                                // so a later resume reselects and re-verifies instead
                                                // of stranding them; the hand-over guard is in-memory
                                                // and cannot own anything across death. Death after
                                                // the sheet is up but before the retire replays the
                                                // M1 hand-off; a cancelled sheet never auto-retries.
                                                ensureActive()
                                                if (UpdateStatus.attempts.handOver(attempt)) {
                                                    UpdateStatus.attempts.downloadEnded(attempt)
                                                    ensureActive()
                                                    if (launchInstaller(appContext, candidate)) {
                                                        // Marker dies only once the sheet is up;
                                                        // see the claim-boundary comment above.
                                                        appContext.retirePendingInstallMarker()
                                                        withContext(Dispatchers.Main) { navController.navigateUp() }
                                                    } else {
                                                        UpdateStatus.attempts.fail(attempt, "Could not open the installer.")
                                                    }
                                                } else {
                                                    UpdateStatus.attempts.downloadEnded(attempt)
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    UpdateStatus.attempts.downloadEnded(attempt)
                                }
                            }
                            job.invokeOnCompletion { UpdateStatus.attempts.downloadEnded(attempt) }
                        } else {
                            // Claim refused; only the handedOver shape (back from a cancelled
                            // installer sheet) retires the selector, and even then ONLY in the
                            // serialized retire-then-free order inside one IO coroutine:
                            // while isHandedOver(attempt) holds, beginRecheck refuses every
                            // claim, so retiring BEFORE endHandOver means no resume can claim
                            // the old selector in the window; after the free there is no
                            // selector left to claim. The retire is CAS on attempt identity, so
                            // a marker staged by a NEWER attempt survives. The running and
                            // token-mismatch shapes leave shared state to its active owner.
                            // If the destination is popped before this coroutine runs, the
                            // marker stays STAGED and the in-memory hand-over blocks claims for
                            // the process's life; a cold start later recovers via the marker,
                            // which is the promised "come back" behavior rather than an
                            // auto-retry against the cancelled sheet.
                            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                if (UpdateStatus.attempts.isHandedOver(attempt)) {
                                    appContext.retirePendingInstallMarkerIfOwned(attempt)
                                    UpdateStatus.attempts.endHandOver(attempt, "If the install did not finish, tap Update to try again.")
                                }
                            }
                        }
                    } else if (f == null) {
                        // Review M1: back at this screen while a hand-over has no answer. A completed self-install
                        // would have replaced this app process, so the install did not complete; the system install
                        // screen may still be open elsewhere, and a retry just hands Android the same verified file
                        // again (Android serializes it). Free the busy window for an explicit retry now; the
                        // 10-minute hand-over timeout stays the recovery for never coming back.
                        UpdateStatus.attempts.endHandOver(UpdateStatus.attempts.currentToken, "If the install did not finish, tap Update to try again.")
                    }
                    attemptBusy.value = UpdateStatus.attempts.isBusy()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(isDownloading.value) { attemptBusy.value = UpdateStatus.attempts.isBusy() }
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
                }, enabled = !isDownloading.value && !attemptBusy.value) {
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
