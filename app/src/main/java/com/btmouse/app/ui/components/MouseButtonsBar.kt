package com.btmouse.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.data.HidConstants
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.ButtonDark
import com.btmouse.app.ui.theme.ButtonPressed
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MouseButton(
    modifier: Modifier = Modifier,
    label: String,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    buttonMask: Int,
    hapticsEnabled: Boolean = true,
    isAccent: Boolean = false,
    onButtonChange: (newMask: Int) -> Unit
) {
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }

    val bgColor = if (isPressed) ButtonPressed else ButtonDark

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(bgColor)
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressed = true
                        if (hapticsEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        onButtonChange(buttonMask)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        onButtonChange(HidConstants.MOUSE_BUTTON_NONE)
                    }
                }
                true
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun MouseButtonsBar(
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true,
    onButtonChange: (buttonMask: Int) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MouseButton(
            modifier = Modifier.weight(1f),
            label = "Left",
            shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 10.dp, bottomEnd = 10.dp),
            buttonMask = HidConstants.MOUSE_BUTTON_LEFT,
            hapticsEnabled = hapticsEnabled,
            onButtonChange = onButtonChange
        )
        MouseButton(
            modifier = Modifier.width(60.dp),
            label = "•",
            shape = RoundedCornerShape(10.dp),
            buttonMask = HidConstants.MOUSE_BUTTON_MIDDLE,
            hapticsEnabled = hapticsEnabled,
            onButtonChange = onButtonChange
        )
        MouseButton(
            modifier = Modifier.weight(1f),
            label = "Right",
            shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 26.dp, bottomEnd = 26.dp),
            buttonMask = HidConstants.MOUSE_BUTTON_RIGHT,
            hapticsEnabled = hapticsEnabled,
            onButtonChange = onButtonChange
        )
    }
}
