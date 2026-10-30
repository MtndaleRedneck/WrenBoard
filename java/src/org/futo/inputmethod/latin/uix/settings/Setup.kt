package org.futo.inputmethod.latin.uix.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.inputmethod.updates.openURI

@Composable
fun SetupContainer(inner: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.75f)
        ) {
            Image(
                painter = painterResource(id = R.drawable.wrenboard_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .align(Alignment.Center)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterVertically)
                    .padding(32.dp)
            ) {
                Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                    inner()
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.25f))
    }
}


@Composable
fun Step(fraction: Float, text: String) {
    Column(modifier = Modifier
        .padding(16.dp)
        .clearAndSetSemantics {
            this.text = AnnotatedString(text)
        }
    ) {
        Text(text, style = Typography.SmallMl)
        LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
    }
}

// TODO: May wish to have a skip option
@Composable
@Preview(showBackground = true)
fun SetupEnableIME() {
    val context = LocalContext.current

    val launchImeOptions = {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)

        intent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY

        context.startActivity(intent)
    }

    SetupContainer {
        Column {
            Step(fraction = 1.0f/3.0f, text = stringResource(R.string.setup_step_1))

            Text(
                stringResource(R.string.setup_welcome_text),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = launchImeOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.setup_open_input_settings))
            }
        }
    }
}


@Composable
@Preview(showBackground = true)
fun SetupChangeDefaultIME(doublePackage: Boolean = true) {
    val context = LocalContext.current

    val launchImeOptions = {
        val inputMethodManager =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        inputMethodManager.showInputMethodPicker()

        (context as SettingsActivity).updateSystemState()
    }

    SetupContainer {
        Column {
            if(doublePackage) {
                Tip(stringResource(R.string.setup_warning_multiple_versions))
            }

            Step(fraction = 2.0f/3.0f, text = stringResource(R.string.setup_step_2))

            Text(
                stringResource(R.string.setup_active_input_method),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = launchImeOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.setup_switch_input_methods))
            }
        }
    }
}




val DirectBootWarningDismissed = SettingsKey(
    booleanPreferencesKey("nightly_setup_direct_boot_warning_dismissed"),
    false
)

@Composable
fun needsToShowDirectBootWarning(): Boolean {
    if(BuildConfig.FLAVOR != "unstable") return false

    val context = LocalContext.current
    val isGraphene = remember {
        context.packageManager.systemAvailableFeatures.any { it.name?.contains("grapheneos") == true }
                || Build.HOST == "r-0123456789abcdef-0123"
    }
    if(!isGraphene) return false

    return !useDataStoreValue(DirectBootWarningDismissed)
}

@Composable
@Preview(showBackground = true)
fun SetupDirectBootWarning() {
    val context = LocalContext.current

    SetupContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Warning: You're running the unstable nightly version of the keyboard, on an operating system that lets you disable the USB port. You should not rely on unstable software as your only way of unlocking your phone.",
                textAlign = TextAlign.Left,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "We strongly advise you to either use a PIN screen lock instead of a Password screen lock, or keep the USB-C port system setting set to either \"On\" or \"Charging-only when locked, except before first unlock\"",
                textAlign = TextAlign.Left,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    context.openURI("https://docs.keyboard.futo.tech/improvements/nightly#risk-of-using-nightly-with-password-screen-lock-type")
                },
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text("Read more information")
            }

            Button(
                onClick = {
                    runBlocking { context.setSetting(DirectBootWarningDismissed, true) }
                },
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text("Acknowledge (will not be shown again)")
            }
        }
    }
}


/**
 * WrenBoard 1.2: replaces the full-screen setup steps. Shown at the top of Settings while WrenBoard is not enabled
 * or not the active keyboard. Not enabled: the button opens Android's keyboard list. Enabled but not active: the
 * button opens the keyboard picker (the old Step 2 "Switch Input Methods").
 */
@Composable
fun SetupBanner(inputMethodEnabled: Boolean, doublePackage: Boolean) {
    val context = LocalContext.current
    val onClick = {
        if (!inputMethodEnabled) {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
            context.startActivity(intent)
        } else {
            val inputMethodManager =
                context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.showInputMethodPicker()
            (context as? SettingsActivity)?.updateSystemState()
        }
        Unit
    }

    Surface(
        color = Color(0xFF2A2A2A),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (inputMethodEnabled) R.string.setup_banner_not_active else R.string.setup_banner_not_enabled
                    ),
                    color = Color(0xFFF2F2F2),
                    style = Typography.Body.RegularMl,
                    modifier = Modifier.weight(1.0f)
                )
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8A14), contentColor = Color(0xFF141414)),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        stringResource(
                            if (inputMethodEnabled) R.string.setup_banner_switch else R.string.setup_banner_turn_on
                        )
                    )
                }
            }
            if (doublePackage) {
                Text(
                    stringResource(R.string.setup_warning_multiple_versions),
                    color = Color(0xFFC7C7C7),
                    style = Typography.Body.RegularMl
                )
            }
        }
    }
}
