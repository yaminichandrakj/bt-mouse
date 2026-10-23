package com.btmouse.app.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.HidConstants
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.KeyButton
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.AccentEmerald
import com.btmouse.app.ui.theme.AccentRose
import com.btmouse.app.ui.theme.ButtonDark
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextMuted
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun MediaRemoteScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository
) {
    val hapticsEnabled by settings.hapticFeedback.collectAsState()
    val mediaStatus by coordinator.mediaStatus.collectAsState()
    val isConnected by coordinator.isConnected.collectAsState()
    val volumeNavigation by settings.presentationVolumeNavigation.collectAsState()
    val view = LocalView.current

    DisposableEffect(Unit) {
        settings.setMediaRemoteVisible(true)
        onDispose { settings.setMediaRemoteVisible(false) }
    }

    // Periodically poll PC media state while on this screen
    LaunchedEffect(isConnected) {
        while (true) {
            if (isConnected) {
                coordinator.queryMedia()
            }
            delay(1000)
        }
    }

    fun performHaptic() {
        if (hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun formatSeconds(seconds: Float): String {
        val totalSec = seconds.toInt().coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubbedPos by remember { mutableFloatStateOf(0f) }

    val activePos = if (isScrubbing) scrubbedPos else mediaStatus.position
    val totalDuration = maxOf(1f, mediaStatus.duration)

    val isPlaying = mediaStatus.status.equals("Playing", ignoreCase = true)

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 1: Now Playing & Progression Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Track Info Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(PrimaryIndigo, AccentCyan.copy(alpha = 0.5f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Music",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (mediaStatus.hasMedia && mediaStatus.title.isNotEmpty()) mediaStatus.title else "No Media Playing",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (mediaStatus.hasMedia && mediaStatus.artist.isNotEmpty()) mediaStatus.artist else "Windows Media Session",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Playback Status Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isPlaying) AccentEmerald.copy(alpha = 0.15f) else PrimaryIndigo.copy(alpha = 0.2f))
                            .border(1.dp, if (isPlaying) AccentEmerald.copy(alpha = 0.4f) else DarkBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isPlaying) "PLAYING" else (if (mediaStatus.hasMedia) "PAUSED" else "IDLE"),
                            color = if (isPlaying) AccentEmerald else TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            softWrap = false
                        )
                    }
                }

                // Usable Timeline / Progression Scrubber
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = activePos.coerceIn(0f, totalDuration),
                        onValueChange = {
                            isScrubbing = true
                            scrubbedPos = it
                        },
                        onValueChangeFinished = {
                            coordinator.sendMediaSeek(scrubbedPos)
                            isScrubbing = false
                        },
                        valueRange = 0f..totalDuration,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentCyan,
                            activeTrackColor = AccentCyan,
                            inactiveTrackColor = DarkSurfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth().height(26.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatSeconds(activePos),
                            color = AccentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (mediaStatus.duration > 0f) formatSeconds(mediaStatus.duration) else "--:--",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                // Sleek Playback Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Replay 10s
                    IconButton(
                        onClick = {
                            performHaptic()
                            coordinator.sendMediaSeek(maxOf(0f, activePos - 10f))
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(ButtonDark)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Back 10s",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Previous Track
                    IconButton(
                        onClick = {
                            performHaptic()
                            coordinator.sendMediaPrev()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(ButtonDark)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Track",
                            tint = TextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Large Glowing Play/Pause FAB
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(PrimaryIndigo, Color(0xFF3B38A6))
                                )
                            )
                            .border(2.dp, AccentCyan.copy(alpha = 0.6f), CircleShape)
                            .clickable {
                                performHaptic()
                                coordinator.sendMediaToggle()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Next Track
                    IconButton(
                        onClick = {
                            performHaptic()
                            coordinator.sendMediaNext()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(ButtonDark)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = TextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Forward 10s
                    IconButton(
                        onClick = {
                            performHaptic()
                            coordinator.sendMediaSeek(minOf(totalDuration, activePos + 10f))
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(ButtonDark)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Forward 10s",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // Section 2: Sleek Master Volume Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val volIcon = when {
                            mediaStatus.isMuted || mediaStatus.volume <= 0.01f -> Icons.Default.VolumeOff
                            mediaStatus.volume < 0.33f -> Icons.Default.VolumeMute
                            mediaStatus.volume < 0.66f -> Icons.Default.VolumeDown
                            else -> Icons.Default.VolumeUp
                        }
                        Icon(
                            imageVector = volIcon,
                            contentDescription = "Volume",
                            tint = if (mediaStatus.isMuted) AccentRose else AccentCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PC Master Volume",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = if (mediaStatus.isMuted) "MUTED" else "${(mediaStatus.volume * 100).toInt()}%",
                        color = if (mediaStatus.isMuted) AccentRose else AccentCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Volume Slider
                Slider(
                    value = mediaStatus.volume,
                    onValueChange = { newVol ->
                        coordinator.setVolume(newVol)
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentCyan,
                        activeTrackColor = AccentCyan,
                        inactiveTrackColor = DarkSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().height(26.dp)
                )

                // Volume Quick Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            performHaptic()
                            coordinator.setMute(!mediaStatus.isMuted)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (mediaStatus.isMuted) AccentRose else TextSecondary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (mediaStatus.isMuted) "Unmute" else "Mute", fontSize = 11.sp, softWrap = false, maxLines = 1)
                    }

                    listOf(0.25f, 0.50f, 0.75f, 1.0f).forEach { preset ->
                        OutlinedButton(
                            onClick = {
                                performHaptic()
                                coordinator.setVolume(preset)
                                if (mediaStatus.isMuted) coordinator.setMute(false)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("${(preset * 100).toInt()}%", color = TextPrimary, fontSize = 11.sp, softWrap = false, maxLines = 1)
                        }
                    }
                }
            }
        }

        // Section 3: Presentation Clicker Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Slideshow, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                    Text(
                        text = "Presentation Remote (Slides)",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use volume buttons for slides", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Volume up: next · volume down: previous", color = TextMuted, fontSize = 10.sp)
                    }
                    Switch(
                        checked = volumeNavigation,
                        onCheckedChange = settings::setPresentationVolumeNavigation
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "Previous",
                        isAccent = true,
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_PAGE_UP, 0) }
                    )
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "Next Slide",
                        isAccent = true,
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_PAGE_DOWN, 0) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "Start (F5)",
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_F5, 0) }
                    )
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "Exit (Esc)",
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_ESC, 0) }
                    )
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "Black (B)",
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_B, 0) }
                    )
                }
            }
        }
    }
}
