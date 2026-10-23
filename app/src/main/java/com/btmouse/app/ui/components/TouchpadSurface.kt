package com.btmouse.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.btmouse.app.data.HidConstants
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TrackpadBackground
import com.btmouse.app.ui.theme.TrackpadBorder
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

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(TrackpadBackground)
            .border(1.5.dp, TrackpadBorder, RoundedCornerShape(20.dp))
            .pointerInteropFilter { motionEvent ->
                val pointerCount = motionEvent.pointerCount
                if (pointerCount > maxPointerCount) {
                    maxPointerCount = pointerCount
                }

                // Collect touch points for visual feedback
                val currentPoints = mutableListOf<Offset>()
                for (i in 0 until pointerCount) {
                    currentPoints.add(Offset(motionEvent.getX(i), motionEvent.getY(i)))
                }
                touchPoints = currentPoints

                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downTime = System.currentTimeMillis()
                        totalMovedDistance = 0f
                        maxPointerCount = 1
                        lastX = motionEvent.x
                        lastY = motionEvent.y

                        // Check for double-tap-and-hold (Drag Selection)
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

                    MotionEvent.ACTION_MOVE -> {
                        if (pointerCount == 1) {
                            val currentX = motionEvent.x
                            val currentY = motionEvent.y
                            val rawDx = currentX - lastX
                            val rawDy = currentY - lastY
                            totalMovedDistance += abs(rawDx) + abs(rawDy)

                            // Apply sensitivity
                            val dx = (rawDx * pointerSpeed).roundToInt()
                            val dy = (rawDy * pointerSpeed).roundToInt()

                            val currentButton = if (isDraggingSelection) HidConstants.MOUSE_BUTTON_LEFT else HidConstants.MOUSE_BUTTON_NONE
                            if (dx != 0 || dy != 0) {
                                onMouseMove(dx, dy, currentButton, 0, 0)
                            }

                            lastX = currentX
                            lastY = currentY
                        } else if (pointerCount == 2) {
                            // Two-finger scroll
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
                                    // Left click
                                    performHaptic()
                                    scope.launch {
                                        onMouseButton(HidConstants.MOUSE_BUTTON_LEFT)
                                        delay(30)
                                        onMouseButton(HidConstants.MOUSE_BUTTON_NONE)
                                    }
                                    lastTapTime = now
                                }
                                2 -> {
                                    // Right click
                                    performHaptic(HapticFeedbackConstants.LONG_PRESS)
                                    scope.launch {
                                        onMouseButton(HidConstants.MOUSE_BUTTON_RIGHT)
                                        delay(30)
                                        onMouseButton(HidConstants.MOUSE_BUTTON_NONE)
                                    }
                                }
                                3 -> {
                                    // Middle click
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
        // Visual touch indicators
        Canvas(modifier = Modifier.fillMaxSize()) {
            touchPoints.forEach { point ->
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(AccentCyan.copy(alpha = 0.5f), Color.Transparent),
                        center = point,
                        radius = 80f
                    ),
                    radius = 80f,
                    center = point
                )
                drawCircle(
                    color = AccentCyan.copy(alpha = 0.8f),
                    radius = 16f,
                    center = point
                )
            }
        }
    }
}
