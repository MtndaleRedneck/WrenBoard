package org.futo.inputmethod.updates

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.uix.dataStore
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.BuildConfig
import java.lang.Exception
import java.net.HttpURLConnection
import java.net.URL

// WrenBoard 1.6 (item 11, ported from BingeMote's Updater.kt): the newest published release from WrenBoard's own
// GitHub releases list. Updates come only from there; nothing here talks to FUTO's servers.
const val UPDATE_URL = "https://api.github.com/repos/MtndaleRedneck/WrenBoard/releases?per_page=30"
const val RELEASES_PAGE_URL = "https://github.com/MtndaleRedneck/WrenBoard/releases/latest"
/** The only place an update APK may come from (BingeMote's allow-list, for WrenBoard). */
const val UPDATE_DOWNLOAD_PREFIX = "https://github.com/MtndaleRedneck/WrenBoard/releases/download/"
const val UPDATE_MAX_APK_BYTES = 300L * 1024 * 1024

@Serializable
private data class GithubAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0,
    val digest: String? = null
)

@Serializable
private data class GithubRelease(
    val tag_name: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList()
)

private val updateJson = Json { ignoreUnknownKeys = true }

fun versionStringToCode(version: String): Int {
    val core = version.substringBefore('-').removePrefix("v")
    val parts = core.split(".")
    if(parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return -1
    val major = parts.getOrElse(0) { "0" }.toInt()
    val minor = parts.getOrElse(1) { "0" }.toInt()
    val patch = parts.getOrElse(2) { "0" }.toInt()
    return major * 1000000 + minor * 1000 + patch
}

/** Which way a manual check went, so the Updates card can say "up to date" or "couldn't check". */
sealed interface UpdateCheckOutcome {
    data class Newer(val result: UpdateResult) : UpdateCheckOutcome
    object UpToDate : UpdateCheckOutcome
    object Unavailable : UpdateCheckOutcome
}

/** The release's usable APK, or null: draft, prerelease, unparsable version, or no APK under the allowed prefix. */
private fun GithubRelease.toUpdateResult(): UpdateResult? {
    if(draft || prerelease) return null
    val versionString = tag_name.removePrefix("v")
    val nextVersion = versionStringToCode(versionString)
    if(nextVersion <= 0) return null
    val asset = assets.firstOrNull {
        it.name.endsWith(".apk") && it.browser_download_url.startsWith(UPDATE_DOWNLOAD_PREFIX) && it.size in 1..UPDATE_MAX_APK_BYTES
    } ?: return null
    val d = asset.digest.orEmpty()
    val sha = if(d.startsWith("sha256:") && Regex("[0-9a-fA-F]{64}").matches(d.removePrefix("sha256:"))) d.removePrefix("sha256:").lowercase() else null
    return UpdateResult(nextVersion = nextVersion, apkUrl = asset.browser_download_url, nextVersionString = versionString,
        size = asset.size, sha256 = sha)
}

/** The newest published release (the list comes newest-first), or null on any failure. */
private suspend fun fetchNewestRelease(): Pair<Boolean, UpdateResult?> {
    if(!BuildConfig.UPDATE_CHECKING || !BuildConfig.UPDATE_CHECKING_NETWORK) return false to null

    return withContext(Dispatchers.IO) {
        try {
            val connection = (URL(UPDATE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WrenBoard-UpdateCheck")
            }
            if(connection.responseCode != 200) { connection.disconnect(); return@withContext false to null }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val releases = updateJson.decodeFromString<List<GithubRelease>>(body)
            val newest = releases.firstNotNullOfOrNull { it.toUpdateResult() }
            if(newest != null) Log.d("UpdateChecking", "Retrieved update for version ${newest.nextVersionString}")
            true to newest
        } catch (e: Exception) {
            Log.e("UpdateChecking", "Checking update failed with exception")
            e.printStackTrace()
            false to null
        }
    }
}

suspend fun checkForUpdate(): UpdateResult? = fetchNewestRelease().second

/** The manual check: saves the result like the automatic one, and says which way it went. */
suspend fun checkForUpdateDetailed(context: Context): UpdateCheckOutcome {
    val (reached, result) = fetchNewestRelease()
    if(result == null && reached) {
        // GitHub answered but lists no usable release: that is not a failed check.
        context.dataStore.edit { it[LAST_UPDATE_CHECK_FAILED] = false }
    } else {
        saveUpdateResult(context, result)
    }
    return when {
        result != null && result.isNewer() -> UpdateCheckOutcome.Newer(result)
        reached -> UpdateCheckOutcome.UpToDate
        else -> UpdateCheckOutcome.Unavailable
    }
}

suspend fun checkForUpdateAndSaveToPreferences(context: Context): Boolean {
    val updateResult = checkForUpdate()
    saveUpdateResult(context, updateResult)
    return updateResult != null
}

private suspend fun saveUpdateResult(context: Context, updateResult: UpdateResult?) {
    if(updateResult != null) {
        withContext(Dispatchers.IO) {
            context.dataStore.edit {
                it[LAST_UPDATE_CHECK_RESULT] = Json.encodeToString(updateResult)
                it[LAST_UPDATE_CHECK_FAILED] = false
                it[DEFER_MANUAL_UPDATE_UNTIL] = System.currentTimeMillis() + MANUAL_UPDATE_PERIOD_MS
            }
        }
    } else {
        context.dataStore.edit {
            it[LAST_UPDATE_CHECK_FAILED] = true

            if(it[DEFER_MANUAL_UPDATE_UNTIL] == null) {
                it[DEFER_MANUAL_UPDATE_UNTIL] = System.currentTimeMillis() + MANUAL_UPDATE_PERIOD_MS
            }
        }
    }
}

suspend fun retrieveSavedLastUpdateCheckResult(context: Context): UpdateResult? {
    if(!BuildConfig.UPDATE_CHECKING) return null

    return UpdateResult.fromString(context.getSetting(LAST_UPDATE_CHECK_RESULT, ""))
}

const val JOB_ID: Int = 15782788
fun scheduleUpdateCheckingJob(context: Context) {
    if(!BuildConfig.UPDATE_CHECKING_NETWORK) return

    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    if(jobScheduler.getPendingJob(JOB_ID) != null) {
        Log.i("UpdateChecking", "Job already scheduled, no need to do anything")
        return
    }

    var jobInfoBuilder = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckingService::class.java))
        .setPeriodic(1000L * 60L * 60L * 12L) // every 12 hours
        //.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED) // on unmetered Wi-Fi
        .setPersisted(true) // persist after reboots

    // Update checking has minimum priority
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        jobInfoBuilder = jobInfoBuilder.setPriority(JobInfo.PRIORITY_MIN)
    }

    jobScheduler.schedule(jobInfoBuilder.build())
}
