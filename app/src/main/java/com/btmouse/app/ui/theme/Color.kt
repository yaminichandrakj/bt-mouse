package com.btmouse.app.ui.theme

import androidx.compose.ui.graphics.Color

// Dark monochrome palette shared with the desktop companion: black ground, tonal greys,
// white for what is selected or primary, and colour only for status.
val Ink = Color(0xFF000000)
val Raised = Color(0xFF0E0E0F)
val Panel = Color(0xFF141415)
val Control = Color(0xFF1B1B1C)
val ControlHigh = Color(0xFF1F1F21)
val Selected = Color(0xFF2E2E30)
val SelectedSegment = Color(0xFF333436)
val Hairline = Color(0xFF2A2B2D)
val Outline = Color(0xFF3C3D3F)
val OnInk = Color(0xFFE8E8E8)
val OnInkSoft = Color(0xFFC8C8C8)
val OnInkMuted = Color(0xFFABABAB)
val OnInkFaint = Color(0xFF8E8E8E)
val StatusGreen = Color(0xFF6DD58C)
val StatusRed = Color(0xFFE46962)
val StatusRedText = Color(0xFFF2B8B5)
val DangerContainer = Color(0xFF8C1D18)
val OnDanger = Color(0xFFF9DEDC)

// Older names, kept so every screen picks up the new palette.
val DarkBackground = Ink
val DarkSurface = Panel
val DarkSurfaceVariant = Control
val DarkHeaderSurface = Raised
val DarkBorder = Hairline

val PrimaryIndigo = Selected
val PrimaryIndigoVariant = SelectedSegment
val AccentCyan = OnInk
val AccentTeal = OnInkSoft
val AccentEmerald = StatusGreen
val AccentRose = StatusRed
val AccentAmber = Color(0xFFF2C46D)

val TextPrimary = OnInk
val TextSecondary = OnInkSoft
val TextMuted = OnInkMuted

val TrackpadBackground = Panel
val TrackpadBorder = Hairline
val ButtonDark = Control
val ButtonPressed = Selected
