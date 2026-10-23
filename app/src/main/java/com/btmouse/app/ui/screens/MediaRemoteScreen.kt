package com.btmouse.app.ui.screens

import android.view.HapticFeedbackConstants
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.HidConstants
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.KeyButton
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary

@Composable
fun MediaRemoteScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository
) {
    val hapticsEnabled by settings.hapticFeedback.collectAsState()
    val view = LocalView.current

    fun performHaptic() {
        if (hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Media Player Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Media Player Control",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                // Volume row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MediaCircleButton(
                        icon = Icons.Default.VolumeDown,
                        label = "Vol -",
                        onClick = {
                            performHaptic()
                            coordinator.sendMediaKey(HidConstants.CONSUMER_VOLUME_DOWN)
                        }
                    )
                    MediaCircleButton(
                        icon = Icons.Default.VolumeMute,
                        label = "Mute",
                        onClick = {
                            performHaptic()
                            coordinator.sendMediaKey(HidConstants.CONSUMER_MUTE)
                        }
                    )
                    MediaCircleButton(
                        icon = Icons.Default.VolumeUp,
                        label = "Vol +",
                        onClick = {
                            performHaptic()
                            coordinator.sendMediaKey(HidConstants.CONSUMER_VOLUME_UP)
                        }
                    )
                }

                // Playback row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MediaCircleButton(
                        icon = Icons.Default.FastRewind,
                        label = "Prev",
                        onClick = {
                            performHaptic()
                            coordinator.sendMediaKey(HidConstants.CONSUMER_SCAN_PREV)
                        }
                    )

                    // Large Play / Pause button
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(PrimaryIndigo)
                            .clickable {
                                performHaptic()
                                coordinator.sendMediaKey(HidConstants.CONSUMER_PLAY_PAUSE)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = TextPrimary,
                            modifier = Modifier.size(42.dp)
                        )
                    }

                    MediaCircleButton(
                        icon = Icons.Default.FastForward,
                        label = "Next",
                        onClick = {
                            performHaptic()
                            coordinator.sendMediaKey(HidConstants.CONSUMER_SCAN_NEXT)
                        }
                    )
                }
            }
        }

        // Section 2: Presentation Clicker Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Slideshow, contentDescription = null, tint = AccentCyan)
                    Text(
                        text = "Presentation Remote (Slides)",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Start & Exit Slideshow
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "Start (F5)",
                        isAccent = true,
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_F5, 0) },
                        onUp = {}
                    )
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "Exit (Esc)",
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_ESC, 0) },
                        onUp = {}
                    )
                }

                // Big Previous & Next Slide buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "◄ Prev Slide",
                        minHeight = 80.dp,
                        fontSize = 16.sp,
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_PAGE_UP, 0) },
                        onUp = {}
                    )
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "Next Slide ►",
                        minHeight = 80.dp,
                        fontSize = 16.sp,
                        isAccent = true,
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_PAGE_DOWN, 0) },
                        onUp = {}
                    )
                }

                // Screen Blanking keys
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "Black Screen (B)",
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_B, 0) },
                        onUp = {}
                    )
                    KeyButton(
                        modifier = Modifier.weight(1f),
                        label = "White Screen (W)",
                        hapticsEnabled = hapticsEnabled,
                        onDown = { coordinator.sendKeyStroke(HidConstants.KEY_W, 0) },
                        onUp = {}
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MediaCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(DarkSurfaceVariant)
                .border(1.dp, DarkBorder, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = TextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = TextSecondary, fontSize = 11.sp)
    }
}
