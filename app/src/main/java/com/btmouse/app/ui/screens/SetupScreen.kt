package com.btmouse.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.control.RemoteControlService
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.Caption
import com.btmouse.app.ui.components.PanelCard
import com.btmouse.app.ui.components.PillButton
import com.btmouse.app.ui.components.PillStyle
import com.btmouse.app.ui.components.StatusDot
import com.btmouse.app.ui.theme.AccentAmber
import com.btmouse.app.ui.theme.Ink
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.StatusGreen
import kotlinx.coroutines.delay

/**
 * First-run guide: everything Android makes the user switch on by hand, in one place,
 * each with its own button and a green dot once done. Every step can be skipped.
 */
@Composable
fun SetupScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository,
    onOpenConnect: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val pin by settings.serverPin.collectAsState()
    val accessibilityOn by RemoteControlService.enabled.collectAsState()
    var notificationsOk by remember { mutableStateOf(true) }
    var batteryOk by remember { mutableStateOf(true) }
    var keyboardOk by remember { mutableStateOf(false) }
    var micOk by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            notificationsOk = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            batteryOk = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
            keyboardOk = isKeyboardEnabled(context)
            micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            delay(1000)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Set up Lanyard", color = OnInk, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        Caption("A few things Android asks you to turn on yourself. Do them now or later from Devices.")

        Step(
            done = pin.length == 6,
            title = "Pair with your PC",
            detail = "Open Lanyard for PC on the laptop and scan its QR code. Skip this if you only use phones and tablets.",
            action = "Scan PC code", onAction = onOpenConnect
        )
        Step(
            done = notificationsOk,
            title = "Allow notifications",
            detail = "Shows that Lanyard is connected, received files, and requests to mirror this screen.",
            action = "Allow"
        ) { if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        Step(
            done = batteryOk,
            title = "Let it run in the background",
            detail = "So it stays connected. On Xiaomi, Oppo, Vivo and Samsung also set battery use to Unrestricted in the app's settings.",
            action = "Allow"
        ) { requestBatteryExemption(context) }
        Step(
            done = accessibilityOn,
            title = "Let your other devices control this one",
            detail = "Settings › Accessibility › Lanyard control › On. Needed for the laptop's mouse and for Mirror & control.",
            action = "Open settings"
        ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Step(
            done = keyboardOk,
            title = "Turn on the Lanyard keyboard",
            detail = "Lets the laptop type anything here. Switch to it when you type from the laptop.",
            action = "Open settings"
        ) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Step(
            done = micOk,
            title = "Microphone (optional)",
            detail = "Lets a mirrored screen bring its sound along, and the phone mic stream to the PC.",
            action = "Allow"
        ) { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }

        Spacer(Modifier.height(6.dp))
        PillButton("Done", onDone, style = PillStyle.Primary)
    }
}

@Composable
private fun Step(done: Boolean, title: String, detail: String, action: String, onAction: () -> Unit) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (done) StatusGreen else AccentAmber, size = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = OnInk, fontSize = 16.sp)
                Caption(if (done) "Done" else detail)
            }
            if (!done) {
                Spacer(Modifier.width(8.dp))
                PillButton(action, onAction, height = 38.dp)
            }
        }
    }
}

@Suppress("unused")
private fun keyboardPicker(context: Context) =
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
