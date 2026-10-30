package com.btmouse.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.btmouse.app.ui.theme.Ink
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.OnInkMuted
import com.btmouse.app.ui.theme.Raised

private const val MAX_ZOOM = 5f

/** Zoom and pan for a picture. The top-left of the content sits at [offset], scaled by [scale]. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    internal var size = IntSize.Zero

    val percent: String get() = "${(scale * 100).toInt()}%"

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** Zoom to [target] keeping the point under [focus] (in viewer pixels) still. */
    fun zoomTo(target: Float, focus: Offset = Offset(size.width / 2f, size.height / 2f)) {
        val next = target.coerceIn(1f, MAX_ZOOM)
        val ratio = next / scale
        scale = next
        offset = clamp(focus - (focus - offset) * ratio)
    }

    fun panBy(delta: Offset) {
        offset = clamp(offset + delta)
    }

    private fun clamp(o: Offset): Offset = Offset(
        o.x.coerceIn(size.width * (1 - scale), 0f),
        o.y.coerceIn(size.height * (1 - scale), 0f)
    )
}

@Composable
fun rememberZoomState(): ZoomState = remember { ZoomState() }

/**
 * A picture you can pinch, drag and double-tap, with − / + and full-screen buttons.
 * Works the same in a small panel and in full screen.
 */
@Composable
fun ZoomableFrame(
    modifier: Modifier = Modifier,
    state: ZoomState = rememberZoomState(),
    onFullscreen: (() -> Unit)? = null,
    showControls: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Raised)
            .onSizeChanged { state.size = it }
            .pointerInput(state) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (zoom != 1f) state.zoomTo(state.scale * zoom, centroid)
                    if (state.scale > 1f) state.panBy(pan)
                }
            }
            .pointerInput(state) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (state.scale > 1f) state.reset() else state.zoomTo(2.5f, tap)
                })
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x
                    translationY = state.offset.y
                },
            content = content
        )
        overlay()
        if (showControls) {
            ZoomControls(state, onFullscreen, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
    }
}

@Composable
fun ZoomControls(state: ZoomState, onFullscreen: (() -> Unit)?, modifier: Modifier = Modifier, fullscreenExit: Boolean = false) {
    Row(
        modifier = modifier.clip(CircleShape).background(Ink.copy(alpha = 0.78f)).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundIconButton(Icons.Default.Remove, "Zoom out", { state.zoomTo(state.scale / 1.5f) }, style = PillStyle.Quiet, size = 40.dp, iconSize = 18.dp)
        Text(state.percent, color = OnInk, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 40.dp))
        RoundIconButton(Icons.Default.Add, "Zoom in", { state.zoomTo(state.scale * 1.5f) }, style = PillStyle.Quiet, size = 40.dp, iconSize = 18.dp)
        if (onFullscreen != null) {
            RoundIconButton(
                if (fullscreenExit) Icons.Default.FullscreenExit else Icons.Default.OpenInFull,
                if (fullscreenExit) "Exit full screen" else "Full screen",
                onFullscreen, style = PillStyle.Quiet, size = 40.dp, iconSize = 18.dp
            )
        }
    }
}

/** A frame or a short message when there is nothing to show yet. */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    state: ZoomState = rememberZoomState(),
    emptyText: String = "Waiting for the picture…",
    onFullscreen: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    ZoomableFrame(modifier = modifier, state = state, onFullscreen = onFullscreen, showControls = bitmap != null, overlay = overlay) {
        if (bitmap != null) {
            Image(bitmap, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text(
                emptyText,
                color = OnInkMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Full screen in landscape with the system bars hidden. Pinch, drag and the zoom buttons
 * keep working; [extraControls] sit at the bottom left (for example slide buttons).
 */
@Composable
fun FullscreenViewer(
    onDismiss: () -> Unit,
    state: ZoomState = rememberZoomState(),
    aspectRatio: Float = 16f / 9f,
    extraControls: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            ZoomableFrame(
                modifier = Modifier.fillMaxHeight().aspectRatio(aspectRatio, matchHeightConstraintsFirst = true),
                state = state,
                showControls = false,
                content = content
            )
            RoundIconButton(
                Icons.Default.FullscreenExit, "Exit full screen", onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                style = PillStyle.Tonal, size = 48.dp
            )
            extraControls()
            ZoomControls(state, null, Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }
}
