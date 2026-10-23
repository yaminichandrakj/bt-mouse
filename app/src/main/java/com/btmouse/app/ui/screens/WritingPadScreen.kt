package com.btmouse.app.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CropLandscape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.HidConstants
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.InkStroke
import com.btmouse.app.ui.components.WritingPadSurface
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.AccentEmerald
import com.btmouse.app.ui.theme.AccentRose
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextMuted
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary

@Composable
fun WritingPadScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository
) {
    val isConnected by coordinator.isConnected.collectAsState()
    val connectedDevice by coordinator.connectedDeviceName.collectAsState()
    val hapticsEnabled by settings.hapticFeedback.collectAsState()

    var isAbsoluteMapping by remember { mutableStateOf(true) }
    var isStylusOnly by remember { mutableStateOf(false) }
    var isEraser by remember { mutableStateOf(false) }
    var selectedColor by remember { mutableStateOf(AccentCyan) }
    var strokeThickness by remember { mutableFloatStateOf(4f) }

    val strokes = remember { mutableStateListOf<InkStroke>() }

    val colors = listOf(
        AccentCyan,
        Color.White,
        AccentEmerald,
        Color(0xFFFFD166), // Yellow
        AccentRose,
        PrimaryIndigo
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Status & Quick Toggles Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) AccentEmerald else AccentRose)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (isConnected) (connectedDevice ?: "Connected") else "Disconnected",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isAbsoluteMapping) "1:1 Screen Mapping" else "Relative Tablet Pad",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }

            // Stylus Only (Palm Rejection) & Eraser Toggles
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = isStylusOnly,
                    onClick = { isStylusOnly = !isStylusOnly },
                    label = { Text("Palm Rejection", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.PanTool,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (isStylusOnly) AccentCyan else TextMuted
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                        selectedLabelColor = AccentCyan,
                        containerColor = DarkSurface
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilterChip(
                    selected = isEraser,
                    onClick = { isEraser = !isEraser },
                    label = { Text(if (isEraser) "Eraser" else "Pen", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isEraser) Icons.Default.AutoFixHigh else Icons.Default.Draw,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (isEraser) AccentRose else AccentCyan
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (isEraser) AccentRose.copy(alpha = 0.35f) else PrimaryIndigo.copy(alpha = 0.35f),
                        selectedLabelColor = if (isEraser) AccentRose else AccentCyan,
                        containerColor = DarkSurface
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        // Toolbar: Controls & Tools
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mapping mode toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = isAbsoluteMapping,
                    onClick = { isAbsoluteMapping = true },
                    label = { Text("1:1 Monitor", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                        selectedLabelColor = AccentCyan,
                        containerColor = DarkSurface
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilterChip(
                    selected = !isAbsoluteMapping,
                    onClick = { isAbsoluteMapping = false },
                    label = { Text("Relative", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                        selectedLabelColor = AccentCyan,
                        containerColor = DarkSurface
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }

            // Color Swatches
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                colors.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (selectedColor == c && !isEraser) 2.dp else 0.dp,
                                color = if (selectedColor == c && !isEraser) TextPrimary else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable {
                                selectedColor = c
                                isEraser = false
                            }
                    )
                }
            }

            // Actions: Undo, Clear
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (strokes.isNotEmpty()) {
                            strokes.removeAt(strokes.lastIndex)
                        }
                        // Also trigger Ctrl+Z on PC
                        coordinator.sendKeyStroke(HidConstants.KEY_Z, HidConstants.MODIFIER_LEFT_CTRL)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Undo,
                        contentDescription = "Undo",
                        tint = AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { strokes.clear() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear Pad",
                        tint = AccentRose,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Active Drawing Canvas Area
        WritingPadSurface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            currentColor = selectedColor,
            currentStrokeWidth = strokeThickness,
            isStylusOnly = isStylusOnly,
            isAbsoluteMapping = isAbsoluteMapping,
            isEraser = isEraser,
            hapticsEnabled = hapticsEnabled,
            strokes = strokes,
            onPenEvent = { action, xRatio, yRatio, pressure, eraser ->
                coordinator.sendPenEvent(action, xRatio, yRatio, pressure, eraser)
            },
            onRelativeMove = { dx, dy, isDown ->
                val btn = if (isDown) (if (isEraser) HidConstants.MOUSE_BUTTON_RIGHT else HidConstants.MOUSE_BUTTON_LEFT) else HidConstants.MOUSE_BUTTON_NONE
                coordinator.sendMouseMove(dx, dy, btn)
            }
        )

        // Bottom Help Card
        Text(
            text = "💡 Tip: Open Paint, OneNote, Whiteboard, or Photoshop on your PC. Tablet movements & stylus pressure draw directly onto your screen!",
            color = TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}
