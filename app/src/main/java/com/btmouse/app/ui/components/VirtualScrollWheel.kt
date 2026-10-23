package com.btmouse.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.btmouse.app.data.HidConstants
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.TextMuted
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VirtualScrollWheel(
    modifier: Modifier = Modifier,
    scrollSpeed: Float = 1.0f,
    invertScroll: Boolean = false,
    hapticsEnabled: Boolean = true,
    onScroll: (vScroll: Int) -> Unit
) {
    val view = LocalView.current
    var lastY by remember { mutableFloatStateOf(0f) }
    var accumulatedDelta by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .width(44.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
            .pointerInteropFilter { motionEvent ->
                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastY = motionEvent.y
                        accumulatedDelta = 0f
                        if (hapticsEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val currentY = motionEvent.y
                        val dy = currentY - lastY
                        accumulatedDelta += dy

                        val threshold = 18f / scrollSpeed
                        if (abs(accumulatedDelta) >= threshold) {
                            val direction = if (accumulatedDelta > 0) -1 else 1
                            val finalDirection = if (invertScroll) -direction else direction
                            val steps = (abs(accumulatedDelta) / threshold).roundToInt()

                            onScroll(finalDirection * steps * 2)

                            if (hapticsEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            accumulatedDelta = 0f
                        }
                        lastY = currentY
                    }
                }
                true
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.UnfoldMore,
            contentDescription = "Scroll Wheel",
            tint = TextMuted
        )
    }
}
