package com.btmouse.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.connection.RecordingEntry
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary
import java.io.File

@Composable
fun UtilitiesScreen(coordinator: ConnectionCoordinator, settings: SettingsRepository) {
    var activeSection by remember { mutableStateOf("apps") }
    val hapticsEnabled by settings.hapticFeedback.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("apps" to "Quick Apps", "tasks" to "Tasks & Stats", "camera" to "Camera & Mic", "recordings" to "Recordings", "power" to "Power & Lock").forEach { (key, label) ->
                FilterChip(
                    selected = activeSection == key,
                    onClick = { activeSection = key },
                    label = { Text(label, fontSize = 11.sp) },
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
            "camera" -> CameraAndRecorderSection(coordinator)
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
    val pcToPhoneActive = coordinator.isPcLiveAudioActive?.collectAsState()?.value ?: false
    val phoneToPcActive = coordinator.isPhoneLiveAudioActive?.collectAsState()?.value ?: false
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

    Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live two-way audio", color = TextPrimary, fontSize = 15.sp)
            Text(
                "Stream the PC microphone to this phone over the selected connection. Lower audio quality and longer buffering may apply on Bluetooth or WAN.",
                color = TextSecondary, fontSize = 11.sp
            )
            Text(
                when {
                    pcToPhoneActive && pcRecording || phoneToPcActive && phoneRecording -> "● Live audio is recording on the PC."
                    pcToPhoneActive || phoneToPcActive -> "● Live audio is active and is not being recorded."
                    else -> "Live audio is off. Recording can be enabled before starting a session."
                },
                color = if ((pcToPhoneActive && pcRecording) || (phoneToPcActive && phoneRecording)) AccentCyan else TextSecondary,
                fontSize = 11.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = recordOnPc, onCheckedChange = { recordOnPc = it }, enabled = !pcToPhoneActive && !phoneToPcActive)
                Text("Also save this session on the PC", color = TextPrimary, fontSize = 12.sp)
            }
            Button(
                onClick = { if (pcToPhoneActive) coordinator.stopPcLiveAudio() else coordinator.startPcLiveAudio(recordOnPc) },
                enabled = connected,
                colors = ButtonDefaults.buttonColors(containerColor = if (pcToPhoneActive) DarkSurface else PrimaryIndigo)
            ) {
                Text(if (pcToPhoneActive) "Stop listening to PC mic" else "Listen to PC microphone", fontSize = 12.sp)
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
                colors = ButtonDefaults.buttonColors(containerColor = if (phoneToPcActive) DarkSurface else PrimaryIndigo)
            ) {
                Text(if (phoneToPcActive) "Stop phone microphone" else "Speak through PC speakers", fontSize = 12.sp)
            }
            Text(
                when {
                    !connected -> "Connect to the PC to start live audio."
                    phoneToPcActive && connectionMessage.contains("waiting for phone microphone", ignoreCase = true) -> connectionMessage
                    pcToPhoneActive || phoneToPcActive -> connectionMessage.takeIf { it.contains("audio", ignoreCase = true) }
                        ?: "Live audio is active. Keep this screen open while using the microphone."
                    connectionMessage.startsWith("Could not start") -> connectionMessage
                    else -> "Live audio is off. Phone microphone access is requested only when you start the phone-to-PC session."
                },
                color = if (pcToPhoneActive || phoneToPcActive) AccentCyan else TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun RecordingLibrarySection(coordinator: ConnectionCoordinator) {
    val context = LocalContext.current
    val recordings = coordinator.recordings?.collectAsState()?.value.orEmpty()
    val status = coordinator.recordingTransferStatus?.collectAsState()?.value.orEmpty()
    val streamUrl = coordinator.recordingStreamUrl?.collectAsState()?.value
    val connected by coordinator.isConnected.collectAsState()

    LaunchedEffect(connected) {
        if (connected) coordinator.queryRecordings()
    }
    var activeVideoView by remember(streamUrl) { mutableStateOf<VideoView?>(null) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (streamUrl != null) {
            DisposableEffect(streamUrl) {
                onDispose {
                    activeVideoView?.stopPlayback()
                    activeVideoView = null
                    coordinator.stopRecordingStream()
                }
            }
            AndroidView(
                factory = { viewContext ->
                    VideoView(viewContext).apply {
                        activeVideoView = this
                        tag = streamUrl
                        setMediaController(MediaController(viewContext))
                        setOnPreparedListener { it.start() }
                        setVideoURI(Uri.parse(streamUrl))
                    }
                },
                update = { videoView ->
                    if (videoView.tag != streamUrl) {
                        videoView.tag = streamUrl
                        videoView.setVideoURI(Uri.parse(streamUrl))
                    }
                },
                modifier = Modifier.fillMaxWidth().height(240.dp)
            )
            Button(onClick = { coordinator.stopRecordingStream() }) { Text("Close online playback") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Recordings", color = TextPrimary, fontSize = 15.sp)
            Button(onClick = { coordinator.queryRecordings() }, enabled = connected) {
                Text("Refresh", fontSize = 12.sp)
            }
        }
        if (status.isNotBlank()) Text(status, color = AccentCyan, fontSize = 11.sp)
        if (recordings.isEmpty()) {
            Text(if (connected) "No recordings found on the PC or this phone." else "Connect to the PC to browse its recordings.",
                color = TextPrimary, fontSize = 12.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recordings, key = { "${it.id}_${it.localPath ?: "remote"}" }) { recording ->
                    RecordingRow(recording, connected, coordinator) {
                        val file = recording.localPath?.let(::File) ?: return@RecordingRow
                        try {
                            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val mime = if (recording.mediaType == "audio") "audio/*" else "video/*"
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
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
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(recording.name, color = TextPrimary, fontSize = 12.sp)
            Text("${recording.mediaType.replaceFirstChar { it.uppercase() }} · ${recording.size / (1024 * 1024)} MB",
                color = AccentCyan, fontSize = 10.sp)
            if (recording.localPath != null) {
                Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)) {
                    Text("Open on phone", fontSize = 11.sp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { coordinator.streamRecording(recording.id) },
                        enabled = connected,
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)
                    ) { Text("Play online", fontSize = 11.sp) }
                    Button(
                        onClick = { coordinator.downloadRecording(recording.id) },
                        enabled = connected,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                    ) { Text("Download", fontSize = 11.sp) }
                }
            }
        }
    }
}
