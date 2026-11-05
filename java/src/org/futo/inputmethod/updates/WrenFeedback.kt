package org.futo.inputmethod.updates

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.settings.WrenBottomCard
import org.futo.inputmethod.latin.uix.settings.WrenFlat
import org.futo.inputmethod.latin.uix.settings.WrenOrangeButton
import org.futo.inputmethod.latin.uix.settings.WrenTopBar

/*
 * WrenBoard 1.6 (item 11): Ezzy's standard Feedback & bugs and Updates pages, ported from BingeMote
 * (MtndaleRedneck/BingeMote main @6b60b13: Feedback.kt, SettingsScreen.kt cards). Nothing is ever sent from the
 * app: both routes open the browser with the report filled in, and the person reviews and submits it there.
 */

/** The public route: a new GitHub issue on WrenBoard. */
const val WRENBOARD_ISSUES_URL = "https://github.com/MtndaleRedneck/WrenBoard/issues"

/** The private route: WrenBoard's own Google form, opened pre-filled (usp=pp_url). */
const val BUG_FORM_URL = WRENBOARD_FEEDBACK_FORM_URL
const val FORM_ENTRY_MESSAGE = "entry.755520238"
const val FORM_ENTRY_PHONE_MODEL = "entry.793045318"
const val FORM_ENTRY_ANDROID = "entry.1132607697"
const val FORM_ENTRY_APP_VERSION = "entry.904027313"
/** Optional reply email (entry.1123297416): never pre-filled; the person types it in the form if they want a reply. */
const val FORM_ENTRY_REPLY_EMAIL = "entry.1123297416"

private const val MAX_TEXT = 3000
private const val MAX_ENCODED = 4500

/** The only things a report carries besides what was typed. No PC name, Wi-Fi names, paired phones or file paths. */
data class FeedbackContext(val appVersion: String, val androidVersion: String, val phoneModel: String, val display: String = "")

object Feedback {
    /** Trims the text so the encoded link stays well under what web servers accept. The preview shows this same text. */
    fun clean(text: String): String {
        var t = text.trim().take(MAX_TEXT)
        while (t.isNotEmpty() && enc(t).length > MAX_ENCODED) t = t.dropLast(50).trimEnd()
        return t
    }

    /** Exactly what is sent, shown before a button is tapped. */
    fun preview(text: String, c: FeedbackContext): String =
        "Message:\n${clean(text).ifEmpty { "(nothing written yet)" }}\n\nApp version: ${c.appVersion}\nAndroid version: ${c.androidVersion}\nPhone model: ${c.phoneModel}${displayLine(c)}"

    private fun displayLine(c: FeedbackContext) = if (c.display.isBlank()) "" else "\nDisplay: ${c.display}"

    fun githubUrl(text: String, c: FeedbackContext): String {
        val t = clean(text)
        val title = "Feedback: " + t.lineSequence().firstOrNull().orEmpty().take(60)
        val body = "$t\n\n---\nApp version: ${c.appVersion}\nAndroid version: ${c.androidVersion}\nPhone model: ${c.phoneModel}${displayLine(c)}"
        return "$WRENBOARD_ISSUES_URL/new?title=${enc(title)}&body=${enc(body)}"
    }

    fun formUrl(text: String, c: FeedbackContext): String =
        "$BUG_FORM_URL?usp=pp_url" +
            "&$FORM_ENTRY_MESSAGE=${enc(clean(text) + (if (c.display.isBlank()) "" else "\n\nDisplay: ${c.display}"))}" +
            "&$FORM_ENTRY_PHONE_MODEL=${enc(c.phoneModel)}" +
            "&$FORM_ENTRY_ANDROID=${enc(c.androidVersion)}" +
            "&$FORM_ENTRY_APP_VERSION=${enc(c.appVersion)}"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun context(ctx: Context) = FeedbackContext(
        BuildConfig.VERSION_NAME,
        "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
        displayInfo(ctx),
    )

    /** Font scale, density and window size: the bits that explain a layout bug. */
    private fun displayInfo(ctx: Context): String = try {
        val cfg = ctx.resources.configuration
        val dm = ctx.resources.displayMetrics
        val dpiName = when {
            dm.densityDpi <= 240 -> "hdpi"
            dm.densityDpi <= 320 -> "xhdpi"
            dm.densityDpi <= 480 -> "xxhdpi"
            else -> "xxxhdpi"
        }
        "font scale %.2f, %d dpi (%s), screen %dx%d dp".format(
            java.util.Locale.ROOT, cfg.fontScale, dm.densityDpi, dpiName, cfg.screenWidthDp, cfg.screenHeightDp)
    } catch (e: Exception) { "" }
}

@Composable
private fun FeedbackSection(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WrenFlat.Card).padding(18.dp)
    ) {
        if (title.isNotEmpty()) {
            Text(title, color = WrenFlat.Title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** The Feedback page (mock-ups 21-22): message, the exact preview, then the two routes. */
@Composable
fun FeedbackScreen(navController: NavHostController) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val c = remember { Feedback.context(ctx) }
    fun open(url: String) = ctx.openURI(url, newTask = true)
    Column(Modifier.fillMaxSize().background(WrenFlat.Page)) {
    WrenTopBar(androidx.compose.ui.res.stringResource(R.string.feedback_page_title), onBack = { navController.navigateUp() })
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(12.dp))
        FeedbackSection("") {
            Text("Tell us what is on your mind: a bug, an idea, or general feedback. Reports may become public issues on GitHub, so leave out personal details.",
                color = WrenFlat.Sub, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                text, { text = it.take(MAX_TEXT) },
                label = { Text("Your message") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = WrenFlat.Title, unfocusedTextColor = WrenFlat.Title,
                    focusedBorderColor = WrenFlat.Orange, unfocusedBorderColor = androidx.compose.ui.graphics.Color(0xFF5A5A62),
                    cursorColor = WrenFlat.Orange, focusedLabelColor = WrenFlat.Orange, unfocusedLabelColor = androidx.compose.ui.graphics.Color(0xFF8F8F96),
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        FeedbackSection("What will be sent") {
            Text(Feedback.preview(text, c), color = WrenFlat.Sub, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
            Text("No PC name, Wi-Fi names, paired phone names, or file paths are included. Nothing is sent until you tap a button below, and then it opens in your browser for you to review and submit.",
                color = WrenFlat.Sub, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        FeedbackSection("GitHub issue") {
            Text("Public and easiest to follow up.", color = WrenFlat.Sub, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            WrenOrangeButton("Open GitHub issue") {
                if (text.isBlank()) Toast.makeText(ctx, "Write your message first.", Toast.LENGTH_SHORT).show()
                else open(Feedback.githubUrl(text, c))
            }
        }
        Spacer(Modifier.height(12.dp))
        FeedbackSection("Private form") {
            Text("No name needed. A reply email is optional.", color = WrenFlat.Sub, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            WrenOrangeButton("Open private form") {
                if (text.isBlank()) Toast.makeText(ctx, "Write your message first.", Toast.LENGTH_SHORT).show()
                else open(Feedback.formUrl(text, c))
            }
        }
        Spacer(Modifier.height(4.dp))
        // Done is just Back: a quiet text button, not a third orange button competing with the two send buttons.
        TextButton(onClick = { navController.navigateUp() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("Done", color = WrenFlat.Sub, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
    }
    }
}

/** Home card 1 (mock-up 02): opens the Feedback page. */
@Composable
fun WrenFeedbackCard(navController: NavHostController) {
    WrenBottomCard("Feedback & bugs") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button) { navController.navigate("feedback") },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No hard line break: the text wraps on its own at any font size.
            Text("Send feedback, report a bug, or suggest a feature", color = WrenFlat.Title, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Icon(painterResource(R.drawable.wren_set_ic_chevron), contentDescription = null, tint = WrenFlat.CardChevron, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("You see exactly what is sent before you send it.", color = WrenFlat.CardSub, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** Home card 2 (mock-ups 02-03): orange Check for updates, the answer, then the version. Updates come only from
 *  WrenBoard's GitHub releases; a newer version opens WrenBoard's in-app update prompt. */
@Composable
fun WrenUpdatesCard(navController: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf("") }
    WrenBottomCard("Updates", titleGap = 22.dp) {
        Spacer(Modifier.height(14.dp))
        WrenOrangeButton(if (checking) "Checking\u2026" else "Check for updates", enabled = !checking) {
            if (checking) return@WrenOrangeButton
            checking = true
            result = ""
            scope.launch {
                val outcome = checkForUpdateDetailed(ctx.applicationContext)
                checking = false
                when (outcome) {
                    is UpdateCheckOutcome.Newer -> {
                        result = "Version " + outcome.result.nextVersionString + " is available."
                        navController.navigate("update")
                    }
                    UpdateCheckOutcome.UpToDate -> result = "You're up to date."
                    UpdateCheckOutcome.Unavailable -> result = "Couldn't check right now. Try again later."
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (result.isNotEmpty()) {
            Text(result, color = WrenFlat.CardSub, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(2.dp))
        }
        Text("Version ${BuildConfig.VERSION_NAME}", color = WrenFlat.CardSub, fontSize = 14.sp, lineHeight = 20.sp)
    }
}
