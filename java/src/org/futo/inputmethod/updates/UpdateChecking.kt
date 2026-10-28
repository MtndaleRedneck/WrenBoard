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

const val UPDATE_URL = "https://api.github.com/repos/MtndaleRedneck/WrenBoard/releases/latest"
const val RELEASES_PAGE_URL = "https://github.com/MtndaleRedneck/WrenBoard/releases/latest"

@Serializable
private data class GithubAsset(
    val name: String = "",
    val browser_download_url: String = ""
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

suspend fun checkForUpdate(): UpdateResult? {
    if(!BuildConfig.UPDATE_CHECKING || !BuildConfig.UPDATE_CHECKING_NETWORK) return null

    return withContext(Dispatchers.IO) {
        try {
            val connection = (URL(UPDATE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WrenBoard-UpdateCheck")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val release = updateJson.decodeFromString<GithubRelease>(body)
            if(release.draft || release.prerelease) return@withContext null

            val versionString = release.tag_name.removePrefix("v")
            val nextVersion = versionStringToCode(versionString)
            if(nextVersion <= 0) return@withContext null

            val apkUrl = release.assets
                .firstOrNull { it.name.endsWith(".apk") }
                ?.browser_download_url
                ?: return@withContext null

            Log.d("UpdateChecking", "Retrieved update for version $versionString")
            UpdateResult(
                nextVersion = nextVersion,
                apkUrl = apkUrl,
                nextVersionString = versionString
            )
        } catch (e: Exception) {
            Log.e("UpdateChecking", "Checking update failed with exception")
            e.printStackTrace()
            null
        }
    }
}

suspend fun checkForUpdateAndSaveToPreferences(context: Context): Boolean {
    val updateResult = checkForUpdate()
    if(updateResult != null) {
        withContext(Dispatchers.IO) {
            context.dataStore.edit {
                it[LAST_UPDATE_CHECK_RESULT] = Json.encodeToString(updateResult)
                it[LAST_UPDATE_CHECK_FAILED] = false
                it[DEFER_MANUAL_UPDATE_UNTIL] = System.currentTimeMillis() + MANUAL_UPDATE_PERIOD_MS
            }
        }
        return true
    } else {
        context.dataStore.edit {
            it[LAST_UPDATE_CHECK_FAILED] = true

            if(it[DEFER_MANUAL_UPDATE_UNTIL] == null) {
                it[DEFER_MANUAL_UPDATE_UNTIL] = System.currentTimeMillis() + MANUAL_UPDATE_PERIOD_MS
            }
        }

        return false
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
