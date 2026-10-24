package com.btmouse.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.data.HidConstants
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextMuted
import com.btmouse.app.ui.theme.TrackpadBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchpadSurface(
    modifier: Modifier = Modifier,
    pointerSpeed: Float = 1.2f,
    scrollSpeed: Float = 1.0f,
    invertScroll: Boolean = false,
    tapToClick: Boolean = true,
    hapticsEnabled: Boolean = true,
    onMouseMove: (dx: Int, dy: Int, buttons: Int, vScroll: Int, hScroll: Int) -> Unit,
    onMouseButton: (buttons: Int) -> Unit
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var touchPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }
    var lastTwoFingerY by remember { mutableFloatStateOf(0f) }
    var lastTwoFingerX by remember { mutableFloatStateOf(0f) }

    var accumulatedDx by remember { mutableFloatStateOf(0f) }
    var accumulatedDy by remember { mutableFloatStateOf(0f) }

    var downTime by remember { mutableLongStateOf(0L) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var totalMovedDistance by remember { mutableFloatStateOf(0f) }
    var isDraggingSelection by remember { mutableStateOf(false) }
    var maxPointerCount by remember { mutableIntStateOf(1) }

    fun performHaptic(constant: Int = HapticFeedbackConstants.VIRTUAL_KEY) {
        if (hapticsEnabled) {
            view.performHapticFeedback(constant)
        }
    }

    val activeBorderColor = when {
        isDraggingSelection -> AccentCyan
        touchPoints.isNotEmpty() -> PrimaryIndigo.copy(alpha = 0.85f)
        else -> DarkBorder
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(TrackpadBackground)
            .border(1.5.dp, activeBorderColor, RoundedCornerShape(22.dp))
            .pointerInteropFilter { motionEvent ->
                val pointerCount = motionEvent.pointerCount
                if (pointerCount > maxPointerCount) {
                    maxPointerCount = pointerCount
                }

                val currentPoints = mutableListOf<Offset>()
                for (i in 0 until pointerCount) {
                    currentPoints.add(Offset(motionEvent.getX(i), motionEvent.getY(i)))
                }
                touchPoints = currentPoints

                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downTime = System.currentTimeMillis()
                        totalMovedDistance = 0f
                        accumulatedDx = 0f
                        accumulatedDy = 0f
                        maxPointerCount = 1
                        lastX = motionEvent.x
                        lastY = motionEvent.y

                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 280) {
                            isDraggingSelection = true
                            performHaptic(HapticFeedbackConstants.LONG_PRESS)
                            onMouseButton(HidConstants.MOUSE_BUTTON_LEFT)
                        }
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (pointerCount == 2) {
                            lastTwoFingerX = (motionEvent.getX(0) + motionEvent.getX(1)) / 2f
                            lastTwoFingerY = (motionEvent.getY(0) + motionEvent.getY(1)) / 2f
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP -> {
                        if (pointerCount == 2) {
                            val remainingIndex = if (motionEvent.actionIndex == 0) 1 else 0
                            lastX = motionEvent.getX(remainingIndex)
                            lastY = motionEvent.getY(remainingIndex)
                            accumulatedDx = 0f
                            accumulatedDy = 0f
                        } else if (pointerCount > 2) {
                            val remainingIndices = (0 until pointerCount).filter { it != motionEvent.actionIndex }.take(2)
                            if (remainingIndices.size == 2) {
                                lastTwoFingerX = (motionEvent.getX(remainingIndices[0]) + motionEvent.getX(remainingIndices[1])) / 2f
                                lastTwoFingerY = (motionEvent.getY(remainingIndices[0]) + motionEvent.getY(remainingIndices[1])) / 2f
                            }
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (pointerCount == 1) {
                            val currentX = motionEvent.x
                            val currentY = motionEvent.y
                            val rawDx = currentX - lastX
                            val rawDy = currentY - lastY
                            totalMovedDistance += abs(rawDx) + abs(rawDy)

                            accumulatedDx += rawDx * pointerSpeed
                            accumulatedDy += rawDy * pointerSpeed

                            val intDx = accumulatedDx.toInt()
                            val intDy = accumulatedDy.toInt()

                            val currentButton = if (isDraggingSelection) HidConstants.MOUSE_BUTTON_LEFT else HidConstants.MOUSE_BUTTON_NONE
                            if (intDx != 0 || intDy != 0) {
                                accumulatedDx -= intDx
                                accumulatedDy -= intDy
                                onMouseMove(intDx, intDy, currentButton, 0, 0)
                            }

                            lastX = currentX
                            lastY = currentY
                        } else if (pointerCount == 2) {
                            val avgX = (motionEvent.getX(0) + motionEvent.getX(1)) / 2f
                            val avgY = (motionEvent.getY(0) + motionEvent.getY(1)) / 2f

                            val rawScrollY = avgY - lastTwoFingerY
                            val rawScrollX = avgX - lastTwoFingerX
                            totalMovedDistance += abs(rawScrollY) + abs(rawScrollX)

                            val scrollMultiplier = if (invertScroll) -scrollSpeed else scrollSpeed
                            val vScroll = (rawScrollY * scrollMultiplier * 0.25f).roundToInt()
                            val hScroll = (rawScrollX * scrollMultiplier * 0.25f).roundToInt()

                            if (vScroll != 0 || hScroll != 0) {
                                onMouseMove(0, 0, HidConstants.MOUSE_BUTTON_NONE, vScroll, hScroll)
                            }

                            lastTwoFingerX = avgX
                            lastTwoFingerY = avgY
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - downTime
                        val now = System.currentTimeMillis()

                        if (isDraggingSelection) {
                            isDraggingSelection = false
                            onMouseButton(HidConstants.MOUSE_BUTTON_NONE)
                            performHaptic()
                        } else if (tapToClick && totalMovedDistance < 25f && duration < 300) {
                            when (maxPointerCount) {
                                1 -> {
                                    performHaptic()
                                    scope.launch {
                                        onMouseButton(HidConstants.MOUSE_BUTTON_LEFT)
                                        delay(30)
                                        onMouseButton(HidConstants.MOUSE_BUTTON_NONE)
                                    }
                                    lastTapTime = now
                                }
                                2 -> {
                                    performHaptic(HapticFeedbackConstants.LONG_PRESS)
                                    scope.launch {
                                        onMouseButton(HidConstants.MOUSE_BUTTON_RIGHT)
                                        delay(30)
                                        onMouseButton(HidConstants.MOUSE_BUTTON_NONE)
                                    }
                                }
                                3 -> {
                                    performHaptic()
                                    scope.launch {
                                        onMouseButton(HidConstants.MOUSE_BUTTON_MIDDLE)
                                        delay(30)
                                        onMouseButton(HidConstants.MOUSE_BUTTON_NONE)
                                    }
                                }
                            }
                        }

                        touchPoints = emptyList()
                        maxPointerCount = 1
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (isDraggingSelection) {
                            isDraggingSelection = false
                            onMouseButton(HidConstants.MOUSE_BUTTON_NONE)
                        }
                        touchPoints = emptyList()
                    }
                }
                true
            }
    ) {
        // Precision Glass Grid & Active Touch Halo Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spacing = 36.dp.toPx()
            val dotColor = DarkBorder.copy(alpha = 0.38f)
            var x = spacing
            while (x < size.width - spacing * 0.5f) {
                var y = spacing
                while (y < size.height - spacing * 0.5f) {
                    drawCircle(color = dotColor, radius = 1.3f, center = Offset(x, y))
                    y += spacing
                }
                x += spacing
            }

            touchPoints.forEach { point ->
                val haloColor = if (isDraggingSelection) AccentCyan else PrimaryIndigo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(haloColor.copy(alpha = 0.42f), Color.Transparent),
                        center = point,
                        radius = 96f
                    ),
                    radius = 96f,
                    center = point
                )
                drawCircle(
                    color = AccentCyan.copy(alpha = 0.65f),
                    radius = 28f,
                    center = point,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = AccentCyan,
                    radius = 10f,
                    center = point
                )
            }
        }

        // Subtle Gesture Legend at Bottom of Trackpad
        Text(
            text = if (isDraggingSelection) {
                "● DRAG LOCK ACTIVE — Lift finger to release"
            } else {
                "1-Tap: Left  ·  2-Tap: Right  ·  2-Finger Slide: Scroll  ·  Double-Tap Hold: Drag"
            },
            color = if (isDraggingSelection) AccentCyan else TextMuted.copy(alpha = 0.65f),
            fontSize = 10.sp,
            fontWeight = if (isDraggingSelection) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}
