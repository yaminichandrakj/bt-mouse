package com.btmouse.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.TrackpadBackground
import kotlin.math.roundToInt

data class InkStroke(
    val path: Path,
    val color: Color,
    val strokeWidth: Float
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WritingPadSurface(
    modifier: Modifier = Modifier,
    currentColor: Color = Color.White,
    currentStrokeWidth: Float = 4f,
    isStylusOnly: Boolean = false,
    isAbsoluteMapping: Boolean = true,
    isEraser: Boolean = false,
    hapticsEnabled: Boolean = true,
    strokes: MutableList<InkStroke>,
    onPenEvent: (action: String, xRatio: Float, yRatio: Float, pressure: Float, isEraser: Boolean) -> Unit,
    onRelativeMove: (dx: Int, dy: Int, isDown: Boolean) -> Unit
) {
    val view = LocalView.current
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var lastPoint by remember { mutableStateOf<Offset?>(null) }
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(TrackpadBackground)
            .border(1.5.dp, DarkBorder, RoundedCornerShape(16.dp))
            .pointerInteropFilter { motionEvent ->
                val toolType = motionEvent.getToolType(0)
                val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

                // Palm rejection: If stylus only is enabled, ignore fingers
                if (isStylusOnly && !isStylus) {
                    return@pointerInteropFilter false
                }

                val x = motionEvent.x
                val y = motionEvent.y
                val width = view.width.toFloat().coerceAtLeast(1f)
                val height = view.height.toFloat().coerceAtLeast(1f)

                val xRatio = (x / width).coerceIn(0f, 1f)
                val yRatio = (y / height).coerceIn(0f, 1f)
                val pressure = motionEvent.pressure.coerceIn(0.1f, 1.0f)

                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (hapticsEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        val path = Path().apply { moveTo(x, y) }
                        currentPath = path
                        lastPoint = Offset(x, y)
                        lastX = x
                        lastY = y

                        if (isAbsoluteMapping) {
                            onPenEvent("down", xRatio, yRatio, pressure, isEraser)
                        } else {
                            onRelativeMove(0, 0, true)
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        lastPoint?.let { prev ->
                            // Quadratic Bezier smoothing for natural handwriting
                            val midX = (prev.x + x) / 2f
                            val midY = (prev.y + y) / 2f
                            currentPath?.quadraticBezierTo(prev.x, prev.y, midX, midY)
                            lastPoint = Offset(x, y)
                        }

                        if (isAbsoluteMapping) {
                            onPenEvent("move", xRatio, yRatio, pressure, isEraser)
                        } else {
                            val dx = (x - lastX).roundToInt()
                            val dy = (y - lastY).roundToInt()
                            if (dx != 0 || dy != 0) {
                                onRelativeMove(dx, dy, true)
                            }
                            lastX = x
                            lastY = y
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        currentPath?.let { path ->
                            lastPoint?.let { path.lineTo(it.x, it.y) }
                            val effectiveColor = if (isEraser) TrackpadBackground else currentColor
                            val effectiveWidth = currentStrokeWidth * (0.6f + pressure * 0.8f)
                            strokes.add(InkStroke(path, effectiveColor, effectiveWidth))
                        }
                        currentPath = null
                        lastPoint = null

                        if (isAbsoluteMapping) {
                            onPenEvent("up", xRatio, yRatio, 0f, isEraser)
                        } else {
                            onRelativeMove(0, 0, false)
                        }
                    }
                }
                true
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw completed ink strokes
            for (stroke in strokes) {
                drawPath(
                    path = stroke.path,
                    color = stroke.color,
                    style = Stroke(
                        width = stroke.strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Draw active ongoing stroke
            currentPath?.let { path ->
                val liveColor = if (isEraser) Color.Gray.copy(alpha = 0.5f) else currentColor
                drawPath(
                    path = path,
                    color = liveColor,
                    style = Stroke(
                        width = currentStrokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}
