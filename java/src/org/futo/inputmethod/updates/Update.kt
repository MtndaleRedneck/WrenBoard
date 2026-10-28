package org.futo.inputmethod.updates

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavHostController
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.pages.ParagraphText
import org.futo.inputmethod.latin.uix.settings.pages.PaymentSurface
import org.futo.inputmethod.latin.uix.settings.pages.PaymentSurfaceHeading
import org.futo.inputmethod.latin.uix.settings.useDataStore

val LAST_UPDATE_CHECK_RESULT = stringPreferencesKey("last_update_check_result")
val LAST_UPDATE_CHECK_FAILED = booleanPreferencesKey("last_update_check_failed")

val DISABLE_UPDATE_REMINDER = SettingsKey(booleanPreferencesKey("disable_update_reminder"), true)

val SKIPPED_UPDATE_VERSION = intPreferencesKey("skipped_update_version")

val DEFER_MANUAL_UPDATE_UNTIL = longPreferencesKey("defer_manual_update_until")
val DEFERMENT_VERSION = intPreferencesKey("defer_manual_update_version")
const val MANUAL_UPDATE_PERIOD_MS = 1000L * 60L * 60L * 24L * 7L * 10L // Every ten weeks (~2.5 months)

suspend fun deferManualUpdate(context: Context) {
    context.setSetting(
        DEFER_MANUAL_UPDATE_UNTIL,
        System.currentTimeMillis() + MANUAL_UPDATE_PERIOD_MS
    )
    context.setSetting(
        DEFERMENT_VERSION,
        BuildConfig.VERSION_CODE
    )
}

suspend fun isManualUpdateTimeExpired(context: Context): Boolean {
    if(context.getSetting(DEFERMENT_VERSION, 0) < BuildConfig.VERSION_CODE) {
        deferManualUpdate(context)
        return false
    }

    if(context.getSetting(DISABLE_UPDATE_REMINDER)) {
        return false
    }

    val defermentTime = context.getSetting(DEFER_MANUAL_UPDATE_UNTIL, Long.MAX_VALUE)
    return (System.currentTimeMillis() > defermentTime)
}

val LAST_VERSION = longPreferencesKey("last_version")
suspend fun autoDeferManualUpdateIfNeeded(context: Context) {
    if(context.getSetting(LAST_VERSION, 0L) != BuildConfig.VERSION_CODE.toLong()) {
        context.setSetting(LAST_VERSION, BuildConfig.VERSION_CODE.toLong())
        deferManualUpdate(context)
    }
}

fun Context.openURI(uri: String, newTask: Boolean = false) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        if (newTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        startActivity(intent)
    } catch(e: ActivityNotFoundException) {
        Toast.makeText(this, e.localizedMessage, Toast.LENGTH_SHORT).show()
    }
}

fun Context.openManualUpdateCheck() {
    openURI(RELEASES_PAGE_URL, newTask = true)
}

@Composable
@Preview
fun ConditionalUpdate(navController: NavHostController = rememberNavController()) {
    if(!BuildConfig.UPDATE_CHECKING) return

    val (updateInfo, _) = useDataStore(key = LAST_UPDATE_CHECK_RESULT, default = "")
    val (lastFailed, _) = useDataStore(key = LAST_UPDATE_CHECK_FAILED, default = false)

    val lastUpdateResult = if(!LocalInspectionMode.current){
        UpdateResult.fromString(updateInfo)
    } else {
        UpdateResult(123, "abc", "1.2.3")
    }

    val context = LocalContext.current
    val (skippedVersion, _) = useDataStore(key = SKIPPED_UPDATE_VERSION, default = -1)
    if(lastUpdateResult != null && lastUpdateResult.isNewer() && lastUpdateResult.nextVersion != skippedVersion) {
        SettingItem(
            title = stringResource(R.string.settings_update_available),
            subtitle = "${UpdateResult.currentVersionString()} -> ${lastUpdateResult.nextVersionString}",
            onClick = {
                navController.navigate("update")
                //context.openURI(lastUpdateResult.apkUrl)
            }
        ) {
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
    } else if(lastFailed) {
        SettingItem(
            title = stringResource(R.string.settings_check_for_updates_manually),
            onClick = {
                context.openManualUpdateCheck()
            }
        ) {
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
    }
}

val dismissedMigrateUpdateNotice = SettingsKey(
    key = booleanPreferencesKey("dismissedMigrateFdroidObtainiumNotice"),
    default = true
)

@Composable
@Preview
fun ConditionalMigrateUpdateNotice() {
    val context = LocalContext.current
    val value = useDataStore(dismissedMigrateUpdateNotice, blocking = true)
    if(!value.value) {
        PaymentSurface(isPrimary = true) {
            PaymentSurfaceHeading(stringResource(R.string.manual_update_notice_title))

            ParagraphText(stringResource(R.string.manual_update_notice_paragraph_1))

            if(booleanResource(R.bool.use_dev_styling)) {
                ParagraphText(stringResource(R.string.manual_update_notice_dev_paragraph))
            } else {
                ParagraphText(stringResource(R.string.manual_update_notice_paragraph_2))
            }

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.weight(1.0f)) {
                    Button(onClick = {
                        context.openURI(RELEASES_PAGE_URL)
                    }, modifier = Modifier.align(Alignment.Center)) {
                        Text(stringResource(R.string.manual_update_notice_visit_site_button))
                    }
                }
                Box(modifier = Modifier.weight(1.0f)) {
                    Button(
                        onClick = { value.setValue(true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ), modifier = Modifier.align(Alignment.Center)
                    ) {
                        Text(stringResource(R.string.manual_update_notice_dismiss_notice_button))
                    }
                }
            }
        }
    }
}

@Composable
fun UpdatesCard(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (updateInfo, _) = useDataStore(key = LAST_UPDATE_CHECK_RESULT, default = "")
    val (skipped, _) = useDataStore(key = SKIPPED_UPDATE_VERSION, default = -1)
    val result = if(!LocalInspectionMode.current) {
        UpdateResult.fromString(updateInfo)
    } else {
        UpdateResult(1000000, "https://example.com/app.apk", "1.0.0")
    }
    val newer = result != null && result.isNewer() && result.nextVersion != skipped

    PaymentSurface(isPrimary = true) {
        PaymentSurfaceHeading(stringResource(R.string.updates_card_title))

        ParagraphText(
            if(newer) {
                stringResource(R.string.updates_card_new_version, result!!.nextVersionString)
            } else {
                stringResource(R.string.updates_card_up_to_date)
            }
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                scope.launch {
                    checkForUpdateAndSaveToPreferences(context)
                    navController.navigate("update")
                }
            }, modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(stringResource(
                    if(newer) R.string.updates_card_view_update else R.string.updates_card_check_now
                ))
            }
        }
    }
}

// Private Google Form route. Responses land in a private sheet; nothing is posted publicly.
const val WRENBOARD_FEEDBACK_FORM_URL = "https://docs.google.com/forms/d/e/1FAIpQLSdgcWZuuFsv2z0hUlCfWbkVJ8RMsWI3Rwc5oP0WK89s6dCiOA/viewform"

// Prefill entry IDs for the feedback form (message field stays empty for the user to write).
private const val FEEDBACK_ENTRY_PHONE_MODEL = "entry.793045318"
private const val FEEDBACK_ENTRY_ANDROID = "entry.1132607697"
private const val FEEDBACK_ENTRY_APP_VERSION = "entry.904027313"

@Composable
fun FeedbackCard() {
    val context = LocalContext.current
    val showPreview = remember { mutableStateOf(false) }

    val deviceInfo = "App version: ${BuildConfig.VERSION_NAME}\n" +
        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
        "Device: ${Build.MANUFACTURER} ${Build.MODEL}"

    PaymentSurface(isPrimary = true) {
        PaymentSurfaceHeading(stringResource(R.string.feedback_card_title))

        ParagraphText(stringResource(R.string.feedback_card_body))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                showPreview.value = true
            }, modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(stringResource(R.string.feedback_card_button))
            }
        }
    }

    if(showPreview.value) {
        AlertDialog(
            onDismissRequest = { showPreview.value = false },
            title = { Text(stringResource(R.string.feedback_preview_title)) },
            text = { Text(stringResource(R.string.feedback_preview_body, deviceInfo)) },
            confirmButton = {
                TextButton(onClick = {
                    showPreview.value = false
                    val url = "https://github.com/MtndaleRedneck/WrenBoard/issues/new?body=" +
                        Uri.encode(deviceInfo + "\n\n")
                    context.openURI(url, newTask = true)
                }) {
                    Text(stringResource(R.string.feedback_route_github))
                }
            },
            dismissButton = {
                Row {
                    if(WRENBOARD_FEEDBACK_FORM_URL.isNotEmpty()) {
                        TextButton(onClick = {
                            showPreview.value = false
                            val url = WRENBOARD_FEEDBACK_FORM_URL +
                                "?usp=pp_url" +
                                "&" + FEEDBACK_ENTRY_PHONE_MODEL + "=" + Uri.encode("${Build.MANUFACTURER} ${Build.MODEL}") +
                                "&" + FEEDBACK_ENTRY_ANDROID + "=" + Uri.encode("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})") +
                                "&" + FEEDBACK_ENTRY_APP_VERSION + "=" + Uri.encode(BuildConfig.VERSION_NAME)
                            context.openURI(url, newTask = true)
                        }) {
                            Text(stringResource(R.string.feedback_route_form))
                        }
                    }
                    TextButton(onClick = { showPreview.value = false }) {
                        Text(stringResource(R.string.feedback_route_cancel))
                    }
                }
            }
        )
    }
}
