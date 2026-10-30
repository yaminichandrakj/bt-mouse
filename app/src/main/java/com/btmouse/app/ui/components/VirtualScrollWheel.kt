package com.btmouse.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkSurface
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
    var activeTouchY by remember { mutableStateOf<Float?>(null) }
    var accumulatedDelta by remember { mutableFloatStateOf(0f) }

    val isTouching = activeTouchY != null

    Box(
        modifier = modifier
            .width(40.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurface)
            .border(1.dp, if (isTouching) com.btmouse.app.ui.theme.Outline else DarkSurface, RoundedCornerShape(20.dp))
            .pointerInteropFilter { motionEvent ->
                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastY = motionEvent.y
                        activeTouchY = motionEvent.y
                        accumulatedDelta = 0f
                        if (hapticsEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val currentY = motionEvent.y
                        activeTouchY = currentY
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
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activeTouchY = null
                    }
                }
                true
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val tickSpacing = 14.dp.toPx()
            val paddingV = 20.dp.toPx()
            val centerX = size.width / 2f
            var y = paddingV
            while (y <= size.height - paddingV) {
                val dist = activeTouchY?.let { abs(it - y) } ?: 999f
                val isNear = dist < 36.dp.toPx()
                val halfWidth = if (isNear) 11.dp.toPx() else 6.dp.toPx()
                val color = if (isNear) AccentCyan else TextMuted.copy(alpha = 0.45f)
                drawLine(
                    color = color,
                    start = Offset(centerX - halfWidth, y),
                    end = Offset(centerX + halfWidth, y),
                    strokeWidth = if (isNear) 2.5.dp.toPx() else 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                y += tickSpacing
            }
        }
    }
}
