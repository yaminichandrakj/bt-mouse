package com.btmouse.app.data

data class MediaStatus(
    val hasMedia: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playerName: String = "",
    val status: String = "Stopped",
    val position: Float = 0f,
    val duration: Float = 0f,
    val volume: Float = 0.5f,
    val isMuted: Boolean = false,
    val artworkBase64: String = "",
    val videoPreviewBase64: String = "",
    val volumeAvailable: Boolean = true,
    val volumeError: String = ""
)
