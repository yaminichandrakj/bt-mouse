package com.btmouse.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.connection.ScreenFrame
import com.btmouse.app.ui.theme.Ink
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.StatusGreen
import com.btmouse.app.ui.theme.StatusRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps asking the PC for screen pictures while [active]. The next request goes out as soon as
 * a picture arrives (or after a short wait), so the rate follows what the link can carry.
 */
@Composable
fun ScreenFeedPoller(coordinator: ConnectionCoordinator, active: Boolean) {
    val connected by coordinator.isConnected.collectAsState()
    LaunchedEffect(active, connected) {
        if (!active || !connected) return@LaunchedEffect
        var resume = true
        while (true) {
            val before = coordinator.screenFrame.value
            coordinator.requestScreenFrame(resume)
            resume = false
            val next = withTimeoutOrNull(1500) { coordinator.screenFrame.first { it !== before } }
            delay(if (next?.error != null) 1500 else 120)
        }
    }
}

@Composable
fun rememberScreenBitmap(frame: ScreenFrame?): ImageBitmap? {
    val bytes = frame?.jpeg
    return remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
}

/** The PC screen in a zoomable 16:9 panel; the poller in the main screen keeps it fresh. */
@Composable
fun ScreenPreviewPanel(
    coordinator: ConnectionCoordinator,
    state: ZoomState,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val frame by coordinator.screenFrame.collectAsState()
    val connected by coordinator.isConnected.collectAsState()
    val bitmap = rememberScreenBitmap(frame)
    val message = when {
        !connected -> "Connect to your PC to see its screen"
        frame?.error != null -> frame?.error
        else -> "Waiting for the PC screen…"
    }
    ZoomableImage(
        bitmap = if (connected && frame?.error == null) bitmap else null,
        contentDescription = "Live view of the PC screen",
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
        state = state,
        emptyText = message ?: "",
        onFullscreen = onFullscreen,
        overlay = { if (bitmap != null && connected && frame?.error == null) LiveBadge("Live", StatusGreen) }
    )
}

@Composable
fun androidx.compose.foundation.layout.BoxScope.LiveBadge(text: String, dot: androidx.compose.ui.graphics.Color = StatusRed) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(10.dp)
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        StatusDot(dot, size = 7.dp)
        Text(text, color = OnInk, fontSize = 13.sp)
    }
}
