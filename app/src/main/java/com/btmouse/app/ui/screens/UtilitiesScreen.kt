package com.btmouse.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.connection.RecordingEntry
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.theme.AccentAmber
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
import java.io.File

/**
 * Dedicated top-level Cameras & Live Audio Studio screen so users don't have to hunt
 * inside utilities to broadcast their phone camera or view the PC webcam.
 */
@Composable
fun CamerasAndAudioScreen(coordinator: ConnectionCoordinator) {
    var activeTab by remember { mutableStateOf("phone_cam") }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "phone_cam" to "📱 Phone Webcam",
                "pc_cam" to "💻 PC Webcam",
                "audio" to "🎙️ Live Audio & Mic",
                "all" to "🎛️ All Studio"
            ).forEach { (key, label) ->
                val selected = activeTab == key
                FilterChip(
                    selected = selected,
                    onClick = { activeTab = key },
                    label = { Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.38f),
                        selectedLabelColor = AccentCyan,
                        containerColor = DarkSurfaceVariant,
                        labelColor = TextPrimary
                    )
                )
            }
        }

        CameraAndRecorderSection(coordinator = coordinator, initialTab = activeTab)
    }
}

@Composable
fun UtilitiesScreen(coordinator: ConnectionCoordinator, settings: SettingsRepository) {
    var activeSection by remember { mutableStateOf("recordings") }
    val hapticsEnabled by settings.hapticFeedback.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "recordings" to "📂 Media Library (Videos/Audios/Snaps)",
                "apps" to "🚀 Quick Apps",
                "tasks" to "📊 Tasks & Stats",
                "power" to "⚡ Power & Lock"
            ).forEach { (key, label) ->
                val selected = activeSection == key
                FilterChip(
                    selected = selected,
                    onClick = { activeSection = key },
                    label = { Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                        selectedLabelColor = AccentCyan,
                        containerColor = DarkSurface,
                        labelColor = TextPrimary
                    )
                )
            }
        }
        when (activeSection) {
            "tasks" -> TasksAndHardwareSection(coordinator)
            "recordings" -> RecordingLibrarySection(coordinator)
            "power" -> PowerAndUnlockSection(coordinator)
            else -> QuickAppsSection(coordinator, settings, hapticsEnabled)
        }
    }
}

@Composable
fun LiveAudioSection(coordinator: ConnectionCoordinator) {
    val context = LocalContext.current
    val connected by coordinator.isConnected.collectAsState()
    val pcToPhoneActive = coordinator.isPcLiveAudioActive.collectAsState().value
    val phoneToPcActive = coordinator.isPhoneLiveAudioActive.collectAsState().value
    val pcRecording = coordinator.isPcLiveAudioRecording.collectAsState().value
    val phoneRecording = coordinator.isPhoneLiveAudioRecording.collectAsState().value
    val connectionMessage by coordinator.statusMessage.collectAsState()
    var recordOnPc by remember { mutableStateOf(true) }
    var startPhoneAfterPermission by remember { mutableStateOf(false) }
    val currentRecordSetting by rememberUpdatedState(recordOnPc)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (startPhoneAfterPermission && granted) coordinator.startPhoneLiveAudio(currentRecordSetting)
        startPhoneAfterPermission = false
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Two-Way Audio Relay", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("📁 Audios/", color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Stream the PC microphone to this phone or speak from your phone through the PC speakers. Survives Wi-Fi / Bluetooth failover automatically.",
                color = TextSecondary,
                fontSize = 11.sp
            )
            Text(
                when {
                    pcToPhoneActive && pcRecording || phoneToPcActive && phoneRecording -> "● Live audio active & saving to PC Audios/ folder"
                    pcToPhoneActive || phoneToPcActive -> "● Live audio active (not recording)"
                    else -> "Live audio standby"
                },
                color = if (pcToPhoneActive || phoneToPcActive) AccentEmerald else TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = recordOnPc,
                    onCheckedChange = { recordOnPc = it },
                    enabled = !pcToPhoneActive && !phoneToPcActive
                )
                Text("Also save audio session into PC Audios/ folder", color = TextPrimary, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (pcToPhoneActive) coordinator.stopPcLiveAudio() else coordinator.startPcLiveAudio(recordOnPc) },
                    enabled = connected,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (pcToPhoneActive) AccentRose else PrimaryIndigo)
                ) {
                    Text(if (pcToPhoneActive) "Stop PC Mic" else "Listen to PC Mic", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        if (phoneToPcActive) {
                            coordinator.stopPhoneLiveAudio()
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            coordinator.startPhoneLiveAudio(recordOnPc)
                        } else {
                            startPhoneAfterPermission = true
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = connected,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (phoneToPcActive) AccentRose else AccentCyan)
                ) {
                    Text(
                        if (phoneToPcActive) "Stop Phone Mic" else "Speak to PC",
                        color = if (phoneToPcActive) TextPrimary else com.btmouse.app.ui.theme.DarkBackground,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (!connected) {
                Text("Connect to the PC to start live audio.", color = TextMuted, fontSize = 11.sp)
            } else if (connectionMessage.contains("audio", ignoreCase = true) || connectionMessage.contains("microphone", ignoreCase = true)) {
                Text(connectionMessage, color = AccentCyan, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun RecordingLibrarySection(coordinator: ConnectionCoordinator) {
    val context = LocalContext.current
    val recordings = coordinator.recordings.collectAsState().value
    val status = coordinator.recordingTransferStatus.collectAsState().value
    val streamUrl = coordinator.recordingStreamUrl.collectAsState().value
    val connected by coordinator.isConnected.collectAsState()
    var selectedCategory by remember { mutableStateOf("all") }

    LaunchedEffect(connected) {
        if (connected) coordinator.queryRecordings()
    }
    var activeVideoView by remember(streamUrl) { mutableStateOf<VideoView?>(null) }
    var isFullscreenPlayer by remember(streamUrl) { mutableStateOf(false) }
    var playerErrorText by remember(streamUrl) { mutableStateOf<String?>(null) }

    val filteredRecordings = remember(recordings, selectedCategory) {
        when (selectedCategory) {
            "video" -> recordings.filter { it.mediaType == "video" }
            "audio" -> recordings.filter { it.mediaType == "audio" }
            "snapshot" -> recordings.filter { it.mediaType == "snapshot" }
            else -> recordings
        }
    }

    val videoCount = recordings.count { it.mediaType == "video" }
    val audioCount = recordings.count { it.mediaType == "audio" }
    val snapCount = recordings.count { it.mediaType == "snapshot" }

    // Fullscreen Video Player Modal
    if (streamUrl != null && isFullscreenPlayer) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { isFullscreenPlayer = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
            ) {
                AndroidView(
                    factory = { viewContext ->
                        VideoView(viewContext).apply {
                            setMediaController(MediaController(viewContext))
                            setOnPreparedListener { mp ->
                                mp.isLooping = false
                                start()
                            }
                            setOnErrorListener { _, _, _ ->
                                playerErrorText = "Built-in codec could not decode this stream; tap 'External App' to open."
                                true
                            }
                            setVideoURI(Uri.parse(streamUrl))
                        }
                    },
                    modifier = Modifier.fillMaxSize().align(Alignment.Center)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(DarkBackground.copy(alpha = 0.8f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Fullscreen Playback", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { isFullscreenPlayer = false },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRose),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Exit Fullscreen", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (streamUrl != null) {
            DisposableEffect(streamUrl) {
                onDispose {
                    activeVideoView?.stopPlayback()
                    activeVideoView = null
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("▶ Now Playing on Phone", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { isFullscreenPlayer = true },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                            ) {
                                Text("⛶ Fullscreen", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    activeVideoView?.stopPlayback()
                                    activeVideoView = null
                                    coordinator.stopRecordingStream()
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRose)
                            ) {
                                Text("✕ Close", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(androidx.compose.ui.graphics.Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { viewContext ->
                                VideoView(viewContext).apply {
                                    activeVideoView = this
                                    tag = streamUrl
                                    val controller = MediaController(viewContext)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)
                                    setOnPreparedListener { mp ->
                                        playerErrorText = null
                                        mp.start()
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        playerErrorText = "Could not decode inside embedded view. Use 'External App' button below."
                                        true
                                    }
                                    setVideoURI(Uri.parse(streamUrl))
                                    requestFocus()
                                }
                            },
                            update = { videoView ->
                                if (videoView.tag != streamUrl) {
                                    videoView.tag = streamUrl
                                    playerErrorText = null
                                    videoView.setVideoURI(Uri.parse(streamUrl))
                                    videoView.start()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (playerErrorText != null) {
                        Text(playerErrorText!!, color = AccentAmber, fontSize = 11.sp)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Organized Media Folders", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Saved separately in Videos/, Audios/, and Snapshots/ on Phone & PC", color = TextMuted, fontSize = 10.sp)
            }
            Button(
                onClick = { coordinator.queryRecordings() },
                enabled = connected,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
            ) {
                Text("Refresh", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Folder Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "all" to "All (${recordings.size})",
                "video" to "📹 Videos ($videoCount)",
                "audio" to "🎙️ Audios ($audioCount)",
                "snapshot" to "📷 Snapshots ($snapCount)"
            ).forEach { (key, label) ->
                FilterChip(
                    selected = selectedCategory == key,
                    onClick = { selectedCategory = key },
                    label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.4f),
                        selectedLabelColor = AccentCyan,
                        containerColor = DarkSurface,
                        labelColor = TextSecondary
                    )
                )
            }
        }

        if (status.isNotBlank()) Text(status, color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)

        if (filteredRecordings.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        if (connected) "No items in this folder yet." else "Connect to the PC to browse its Videos, Audios, and Snapshots.",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "• Videos are saved to BTMouse/Videos\n• Audio recordings are saved to BTMouse/Audios\n• Camera snapshots are saved to BTMouse/Snapshots",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredRecordings, key = { "${it.id}_${it.localPath ?: "remote"}" }) { recording ->
                    RecordingRow(recording, connected, coordinator) {
                        val file = recording.localPath?.let(::File) ?: return@RecordingRow
                        try {
                            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val mime = when (recording.mediaType) {
                                "audio" -> "audio/*"
                                "snapshot" -> "image/*"
                                else -> "video/*"
                            }
                            val intent = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingEntry,
    connected: Boolean,
    coordinator: ConnectionCoordinator,
    onOpen: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${recording.name}?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("This will remove ${recording.name} from the ${recording.folder}/ folder.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        coordinator.deleteRecording(recording.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRose)
                ) { Text("Delete") }
            },
            dismissButton = {
                Button(
                    onClick = { showDeleteConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)
                ) { Text("Cancel") }
            },
            containerColor = DarkSurfaceVariant
        )
    }

    val icon = when (recording.mediaType) {
        "audio" -> "🎙️"
        "snapshot" -> "📷"
        else -> "📹"
    }
    val sizeText = if (recording.size < 1024 * 1024) {
        "${recording.size / 1024} KB"
    } else {
        "%.1f MB".format(recording.size / (1024.0 * 1024.0))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$icon  ${recording.name}",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("📁 ${recording.folder}/", color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text(
                "${recording.mediaType.replaceFirstChar { it.uppercase() }}  ·  $sizeText  ·  ${if (recording.localPath != null) "Saved on Phone (${recording.folder}/)" else "Stored on PC (${recording.folder}/)"}",
                color = TextMuted,
                fontSize = 11.sp
            )

            if (recording.localPath != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (recording.mediaType != "snapshot") {
                        Button(
                            onClick = { coordinator.streamRecording(recording.id) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                        ) {
                            Text("▶ Play Here", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = onOpen,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Text("↗ External App", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { showDeleteConfirm = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRose)
                    ) {
                        Text("Delete", fontSize = 11.sp)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (recording.mediaType != "snapshot") {
                        Button(
                            onClick = { coordinator.streamRecording(recording.id) },
                            enabled = connected,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                        ) { Text("▶ Play on Phone", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                    Button(
                        onClick = { coordinator.downloadRecording(recording.id) },
                        enabled = connected,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant)
                    ) { Text("⬇ Save to ${recording.folder}/", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = { showDeleteConfirm = true },
                        enabled = connected,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRose)
                    ) {
                        Text("Delete", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
