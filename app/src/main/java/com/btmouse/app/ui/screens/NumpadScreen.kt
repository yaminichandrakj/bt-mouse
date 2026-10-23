package com.btmouse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.HidConstants
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.KeyButton
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.TextSecondary

@Composable
fun NumpadScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository
) {
    val hapticsEnabled by settings.hapticFeedback.collectAsState()

    fun triggerKey(keyCode: Byte) {
        coordinator.sendKeyStroke(keyCode, 0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Numeric Keypad", color = TextSecondary, fontSize = 14.sp)

        // Row 1: NumLock, /, *, -
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KeyButton(modifier = Modifier.weight(1f), label = "Num", minHeight = 56.dp, fontSize = 16.sp, isAccent = true, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_NUM_LOCK) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "/", minHeight = 56.dp, fontSize = 20.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_DIVIDE) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "*", minHeight = 56.dp, fontSize = 20.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_MULTIPLY) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "-", minHeight = 56.dp, fontSize = 20.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_SUBTRACT) }, onUp = {})
        }

        // Row 2: 7, 8, 9, +
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KeyButton(modifier = Modifier.weight(1f), label = "7", minHeight = 56.dp, fontSize = 22.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_7) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "8", minHeight = 56.dp, fontSize = 22.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_8) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "9", minHeight = 56.dp, fontSize = 22.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_9) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "+", minHeight = 56.dp, fontSize = 20.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_ADD) }, onUp = {})
        }

        // Row 3 & 4 with double-height Enter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(3f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 4, 5, 6
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KeyButton(modifier = Modifier.weight(1f), label = "4", minHeight = 56.dp, fontSize = 22.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_4) }, onUp = {})
                    KeyButton(modifier = Modifier.weight(1f), label = "5", minHeight = 56.dp, fontSize = 22.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_5) }, onUp = {})
                    KeyButton(modifier = Modifier.weight(1f), label = "6", minHeight = 56.dp, fontSize = 22.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_6) }, onUp = {})
                }
                // 1, 2, 3
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KeyButton(modifier = Modifier.weight(1f), label = "1", minHeight = 56.dp, fontSize = 22.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_1) }, onUp = {})
                    KeyButton(modifier = Modifier.weight(1f), label = "2", minHeight = 56.dp, fontSize = 22.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_2) }, onUp = {})
                    KeyButton(modifier = Modifier.weight(1f), label = "3", minHeight = 56.dp, fontSize = 22.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_3) }, onUp = {})
                }
            }

            // Big Enter Key
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Enter",
                minHeight = 122.dp,
                fontSize = 16.sp,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { triggerKey(HidConstants.KEYPAD_ENTER) },
                onUp = {}
            )
        }

        // Bottom Row: 0 (wide), Dot, Backspace
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KeyButton(modifier = Modifier.weight(2f), label = "0", minHeight = 56.dp, fontSize = 22.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_0) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = ".", minHeight = 56.dp, fontSize = 24.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEYPAD_DOT) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "⌫", minHeight = 56.dp, fontSize = 18.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_BACKSPACE) }, onUp = {})
        }
    }
}
