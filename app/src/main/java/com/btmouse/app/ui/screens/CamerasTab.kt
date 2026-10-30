package com.btmouse.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.ui.components.SectionHeader
import com.btmouse.app.ui.components.SegmentedControl
import com.btmouse.app.ui.theme.Ink

/**
 * Cameras tab: the phone camera the PC sees, the PC webcam on the phone, and live audio.
 * Every picture can be pinched and opened full screen. Recordings live here too, since
 * this is where they are made.
 */
@Composable
fun CamerasTab(coordinator: ConnectionCoordinator, onOpenRecordings: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SegmentedControl(listOf("Phone", "PC webcam", "Audio"), tab, { tab = it })
        SectionHeader("Recordings", action = "See all", onAction = onOpenRecordings)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CameraAndRecorderSection(
                coordinator = coordinator,
                initialTab = when (tab) {
                    0 -> "phone_cam"
                    1 -> "pc_cam"
                    else -> "audio"
                }
            )
        }
    }
}

/** Every video, audio clip and snapshot on the PC and phone, with play, save and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsSheet(coordinator: ConnectionCoordinator, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink) {
        Box(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            RecordingLibrarySection(coordinator)
        }
    }
}
