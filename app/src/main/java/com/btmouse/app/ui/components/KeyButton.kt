package com.btmouse.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.ButtonDark
import com.btmouse.app.ui.theme.ButtonPressed
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeyButton(
    modifier: Modifier = Modifier,
    label: String = "",
    icon: ImageVector? = null,
    iconSize: Dp = 13.dp,
    fontSize: TextUnit = 11.sp,
    minHeight: Dp = 42.dp,
    isActive: Boolean = false,
    isAccent: Boolean = false,
    hapticsEnabled: Boolean = true,
    onDown: () -> Unit,
    onUp: () -> Unit = {}
) {
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }

    val bgColor = when {
        isActive -> PrimaryIndigo
        isPressed -> ButtonPressed
        isAccent -> PrimaryIndigo.copy(alpha = 0.25f)
        else -> ButtonDark
    }

    val borderColor = when {
        isActive -> AccentCyan
        isPressed -> AccentCyan.copy(alpha = 0.7f)
        isAccent -> PrimaryIndigo.copy(alpha = 0.6f)
        else -> DarkBorder
    }

    val contentColor = when {
        isActive || isPressed -> TextPrimary
        isAccent -> AccentCyan
        else -> TextSecondary
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressed = true
                        if (hapticsEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        onDown()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        onUp()
                    }
                }
                true
            }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label.ifEmpty { null },
                    modifier = Modifier.size(iconSize),
                    tint = contentColor
                )
                if (label.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(3.dp))
                }
            }
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = fontSize,
                    fontWeight = if (isActive || isPressed || isAccent) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
