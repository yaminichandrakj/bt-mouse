package com.btmouse.app.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.HidConstants
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.FaintNote
import com.btmouse.app.ui.components.MonoSwitch
import com.btmouse.app.ui.components.PillButton
import com.btmouse.app.ui.components.PillStyle
import com.btmouse.app.ui.components.RoundIconButton
import com.btmouse.app.ui.components.ScreenPreviewPanel
import com.btmouse.app.ui.components.SegmentedControl
import com.btmouse.app.ui.components.ZoomState
import com.btmouse.app.ui.theme.Control
import com.btmouse.app.ui.theme.Hairline
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.OnInkFaint
import com.btmouse.app.ui.theme.OnInkMuted
import com.btmouse.app.ui.theme.OnInkSoft
import com.btmouse.app.ui.theme.Panel
import com.btmouse.app.ui.theme.SelectedSegment
import kotlinx.coroutines.delay
import java.util.Locale

/** Media tab: what is playing on the PC, or a presentation clicker with the slide in view. */
@Composable
fun MediaTab(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository,
    slides: Boolean,
    onSlidesChange: (Boolean) -> Unit,
    screenZoom: ZoomState,
    onScreenFullscreen: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SegmentedControl(listOf("Now playing", "Slides"), if (slides) 1 else 0, { onSlidesChange(it == 1) })
        if (slides) {
            SlidesPanel(coordinator, settings, screenZoom, onScreenFullscreen)
        } else {
            NowPlaying(coordinator)
        }
    }
}

private fun clock(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.NowPlaying(coordinator: ConnectionCoordinator) {
    val media by coordinator.mediaStatus.collectAsState()
    val connected by coordinator.isConnected.collectAsState()
    LaunchedEffect(connected, media.title) {
        while (connected) {
            coordinator.queryMedia(includePreview = media.hasMedia && media.artworkBase64.isBlank())
            delay(1000)
        }
    }
    val artwork = remember(media.artworkBase64) {
        media.artworkBase64.takeIf { it.isNotBlank() }?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }.getOrNull()
        }
    }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var volume by remember { mutableStateOf<Float?>(null) }
    val playing = media.status.equals("Playing", ignoreCase = true)

    Box(
        Modifier.padding(top = 8.dp).size(230.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(30.dp)).background(Panel),
        contentAlignment = Alignment.Center
    ) {
        if (artwork != null) Image(artwork, "Album art", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color4A, modifier = Modifier.size(60.dp))
    }
    Column(Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp)) {
        Text(
            if (!connected) "Not connected" else media.playerName.ifBlank { "Playing on PC" },
            color = OnInkMuted, fontSize = 14.sp
        )
        Text(
            if (media.hasMedia) media.title.ifBlank { "Unknown title" } else "Nothing is playing",
            color = OnInk, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (media.artist.isNotBlank()) Text(media.artist, color = OnInkSoft, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (media.duration > 0f) {
        Column {
            Slider(
                value = (scrubbing ?: media.position).coerceIn(0f, media.duration),
                onValueChange = { scrubbing = it },
                onValueChangeFinished = { scrubbing?.let { coordinator.sendMediaSeek(it) }; scrubbing = null },
                valueRange = 0f..media.duration,
                colors = SliderDefaults.colors(thumbColor = OnInk, activeTrackColor = OnInk, inactiveTrackColor = Hairline)
            )
            Row(Modifier.fillMaxWidth()) {
                Text(clock(scrubbing ?: media.position), color = OnInkMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(clock(media.duration), color = OnInkMuted, fontSize = 13.sp)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Icons.Default.SkipPrevious, "Previous track", { coordinator.sendMediaPrev() }, style = PillStyle.Quiet, size = 56.dp, iconSize = 30.dp)
        RoundIconButton(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play",
            { coordinator.sendMediaToggle() }, style = PillStyle.Primary, size = 80.dp, iconSize = 34.dp
        )
        RoundIconButton(Icons.Default.SkipNext, "Next track", { coordinator.sendMediaNext() }, style = PillStyle.Quiet, size = 56.dp, iconSize = 30.dp)
    }
    Spacer(Modifier.weight(1f))
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(Control).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "PC volume", tint = OnInkSoft, modifier = Modifier.size(20.dp))
        Slider(
            value = volume ?: media.volume,
            onValueChange = { volume = it },
            onValueChangeFinished = { volume?.let { coordinator.setVolume(it) }; volume = null },
            enabled = media.volumeAvailable,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            colors = SliderDefaults.colors(thumbColor = OnInk, activeTrackColor = OnInk, inactiveTrackColor = SelectedSegment)
        )
        Text("${((volume ?: media.volume) * 100).toInt()}", color = OnInkSoft, fontSize = 14.sp)
    }
}

private val Color4A = androidx.compose.ui.graphics.Color(0xFF4A4B4D)

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SlidesPanel(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository,
    screenZoom: ZoomState,
    onScreenFullscreen: () -> Unit
) {
    val volumeKeys by settings.presentationVolumeNavigation.collectAsState()
    var slide by rememberSaveable { mutableIntStateOf(1) }
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var blank by remember { mutableStateOf(false) }
    var elapsed by remember { mutableFloatStateOf(0f) }

    // The volume keys only turn slides while this view is on screen.
    DisposableEffect(Unit) {
        settings.setMediaRemoteVisible(true)
        onDispose { settings.setMediaRemoteVisible(false) }
    }
    LaunchedEffect(startedAt) {
        while (startedAt > 0L) {
            elapsed = (System.currentTimeMillis() - startedAt) / 1000f
            delay(1000)
        }
    }

    fun key(code: Byte) = coordinator.sendKeyStroke(code, 0)

    ScreenPreviewPanel(coordinator, screenZoom, onScreenFullscreen)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text("Slide $slide", color = OnInk, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(if (startedAt > 0L) clock(elapsed) else "Not started", color = OnInkMuted, fontSize = 15.sp)
    }
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigSlideButton("Previous", Icons.Default.ChevronLeft, PillStyle.Tonal, Modifier.weight(1f)) {
            key(HidConstants.KEY_PAGE_UP); slide = (slide - 1).coerceAtLeast(1); blank = false
        }
        BigSlideButton("Next", Icons.Default.ChevronRight, PillStyle.Primary, Modifier.weight(2f)) {
            key(HidConstants.KEY_PAGE_DOWN); slide += 1; blank = false
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("Blank screen", { key(HidConstants.KEY_B); blank = !blank }, Modifier.weight(1f),
            style = if (blank) PillStyle.Selected else PillStyle.Tonal, height = 48.dp)
        if (startedAt == 0L) {
            PillButton("Start show", { key(HidConstants.KEY_F5); slide = 1; startedAt = System.currentTimeMillis() },
                Modifier.weight(1f), style = PillStyle.Outline, height = 48.dp)
        } else {
            PillButton("End show", { key(HidConstants.KEY_ESC); startedAt = 0L; elapsed = 0f },
                Modifier.weight(1f), style = PillStyle.Outline, height = 48.dp)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Volume keys change slides", color = OnInkSoft, fontSize = 14.sp, modifier = Modifier.weight(1f))
        MonoSwitch(volumeKeys, { settings.setPresentationVolumeNavigation(it) })
    }
    FaintNote("The slide number is counted on this phone from when you start.")
}

@Composable
private fun BigSlideButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, style: PillStyle, modifier: Modifier, onClick: () -> Unit) {
    com.btmouse.app.ui.components.ActionTile(icon, label, onClick, modifier.padding(0.dp), style = style, height = 128.dp)
}
