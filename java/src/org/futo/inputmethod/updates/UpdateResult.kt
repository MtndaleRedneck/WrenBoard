package org.futo.inputmethod.updates

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.BuildConfig

@Serializable
data class UpdateResult(
    val nextVersion: Int,
    val apkUrl: String,
    val nextVersionString: String,
    /** WrenBoard 1.6: the APK's published size in bytes (0 when unknown, e.g. a result saved by an older version). */
    val size: Long = 0,
    /** WrenBoard 1.6: lowercase hex SHA-256 GitHub published for the APK, or null when it published none. */
    val sha256: String? = null,
    /** WrenBoard 1.10: the release notes as short lines ("## Heading" for a section), shown on the update card. */
    val notes: List<String> = emptyList()
) {
    fun isNewer(): Boolean {
        return nextVersion > currentVersion()
    }

    companion object {
        fun currentVersion(): Int {
            return versionStringToCode(BuildConfig.VERSION_NAME)
        }

        fun currentVersionString(): String {
            return BuildConfig.VERSION_NAME
        }

        fun fromString(value: String): UpdateResult? {
            if(value.isEmpty()) {
                return null
            }

            try {
                return Json.decodeFromString<UpdateResult>(value)
            } catch(e: SerializationException) {
                Log.e("UpdateResult", "Failed to deserialize UpdateResult value $value")
                e.printStackTrace()
                return null
            } catch(e: IllegalArgumentException) {
                Log.e("UpdateResult", "Failed to deserialize UpdateResult value $value")
                e.printStackTrace()
                return null
            }
        }
    }
}
