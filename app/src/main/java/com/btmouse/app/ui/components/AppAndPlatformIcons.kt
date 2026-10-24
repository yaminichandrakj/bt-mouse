package com.btmouse.app.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Resolves a normalized brand/site key from an app name, command path, or media player string.
 */
fun resolveBrandKey(name: String = "", pathOrCmd: String = "", explicitKey: String = ""): String {
    if (explicitKey.isNotBlank()) return explicitKey.lowercase()
    val hay = "$name $pathOrCmd".lowercase()
    return when {
        "youtube music" in hay || "music.youtube" in hay -> "youtube_music"
        "youtube" in hay || "youtu.be" in hay -> "youtube"
        "crunchyroll" in hay -> "crunchyroll"
        "netflix" in hay -> "netflix"
        "prime video" in hay || "amazon video" in hay || "primevideo" in hay -> "prime_video"
        "hotstar" in hay || "jiohotstar" in hay -> "hotstar"
        "disney+" in hay || "disneyplus" in hay || "disney" in hay -> "disney_plus"
        "twitch" in hay -> "twitch"
        "spotify" in hay -> "spotify"
        "apple music" in hay || "itunes" in hay -> "apple_music"
        "soundcloud" in hay -> "soundcloud"
        "tidal" in hay -> "tidal"
        "vlc" in hay -> "vlc"
        "windows media player" in hay || "wmplayer" in hay || "groove" in hay || "zunemusic" in hay -> "wmp"
        "chrome" in hay -> "chrome"
        "msedge" in hay || "microsoft edge" in hay || "edge" in hay -> "edge"
        "brave" in hay -> "brave"
        "firefox" in hay -> "firefox"
        "opera" in hay -> "opera"
        "vivaldi" in hay -> "vivaldi"
        "steam" in hay -> "steam"
        "discord" in hay -> "discord"
        "vscode" in hay || "visual studio code" in hay || "code.exe" in hay -> "vscode"
        "obs" in hay -> "obs"
        "blender" in hay -> "blender"
        "onenote" in hay -> "onenote"
        "winword" in hay || "word" in hay -> "word"
        "excel" in hay -> "excel"
        "powerpnt" in hay || "powerpoint" in hay -> "powerpoint"
        "calculator" in hay || "calc" in hay -> "calculator"
        "terminal" in hay || "powershell" in hay || "cmd" in hay -> "terminal"
        "task manager" in hay || "taskmgr" in hay -> "taskmgr"
        "explorer" in hay -> "explorer"
        "notepad" in hay -> "notepad"
        else -> ""
    }
}

private val OTT_STREAMING_KEYS = setOf(
    "youtube", "youtube_music", "netflix", "prime_video", "crunchyroll",
    "disney_plus", "hotstar", "twitch", "spotify", "apple_music", "soundcloud", "tidal"
)

fun isOttStreamingSite(brandKey: String): Boolean = brandKey.lowercase() in OTT_STREAMING_KEYS

/**
 * Renders either:
 * 1. The authentic OTT / Streaming platform vector emblem (when `preferOttBrand` is true and an OTT site like YouTube, Netflix, Prime Video, or Crunchyroll is active in a browser),
 * 2. The actual Windows laptop application icon extracted by the PC companion (`iconBase64`),
 * 3. Or a high-contrast vector brand emblem for popular PC apps and browsers.
 */
@Composable
fun AppOrPlatformIconBadge(
    name: String,
    pathOrCmd: String = "",
    iconBase64: String = "",
    brandKey: String = "",
    preferOttBrand: Boolean = false,
    size: Dp = 38.dp,
    cornerRadius: Dp = 10.dp
) {
    val resolvedKey = remember(name, pathOrCmd, brandKey) {
        resolveBrandKey(name, pathOrCmd, brandKey)
    }

    val decodedBitmap = remember(iconBase64) {
        runCatching {
            if (iconBase64.isNotBlank()) {
                val bytes = Base64.decode(iconBase64, Base64.DEFAULT)
                if (bytes.isNotEmpty()) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() else null
            } else null
        }.getOrNull()
    }

    // If playing an OTT site inside a browser (like YouTube, Netflix, Prime Video, Crunchyroll),
    // prefer the OTT site emblem over the generic browser executable icon.
    val showOttFirst = preferOttBrand && isOttStreamingSite(resolvedKey)

    if (!showOttFirst && decodedBitmap != null) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .background(Color(0xFF1E293B)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = decodedBitmap,
                contentDescription = name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp),
                contentScale = ContentScale.Fit
            )
        }
        return
    }

    PlatformVectorBadge(
        brandKey = resolvedKey,
        fallbackName = name,
        decodedFallbackBitmap = decodedBitmap,
        size = size,
        cornerRadius = cornerRadius
    )
}

@Composable
fun PlatformVectorBadge(
    brandKey: String,
    fallbackName: String,
    decodedFallbackBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    size: Dp = 38.dp,
    cornerRadius: Dp = 10.dp
) {
    when (brandKey) {
        "youtube" -> {
            // Signature YouTube red rounded rectangle with white play triangle
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFFFF0000)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.52f)) {
                    val path = Path().apply {
                        moveTo(this@Canvas.size.width * 0.22f, this@Canvas.size.height * 0.15f)
                        lineTo(this@Canvas.size.width * 0.88f, this@Canvas.size.height * 0.50f)
                        lineTo(this@Canvas.size.width * 0.22f, this@Canvas.size.height * 0.85f)
                        close()
                    }
                    drawPath(path, Color.White)
                }
            }
        }
        "youtube_music" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF121212)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.78f)) {
                    drawCircle(color = Color(0xFFFF0000), radius = this.size.minDimension / 2f)
                    drawCircle(
                        color = Color.White,
                        radius = this.size.minDimension * 0.30f,
                        style = Stroke(width = this.size.minDimension * 0.06f)
                    )
                    val play = Path().apply {
                        moveTo(this@Canvas.size.width * 0.42f, this@Canvas.size.height * 0.36f)
                        lineTo(this@Canvas.size.width * 0.65f, this@Canvas.size.height * 0.50f)
                        lineTo(this@Canvas.size.width * 0.42f, this@Canvas.size.height * 0.64f)
                        close()
                    }
                    drawPath(play, Color.White)
                }
            }
        }
        "netflix" -> {
            // Cinematic dark tile with Netflix Red 'N' ribbon
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF141414)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.62f)) {
                    val w = this.size.width
                    val h = this.size.height
                    val strokeW = w * 0.24f
                    drawRect(color = Color(0xFFB20710), topLeft = Offset(w * 0.12f, 0f), size = Size(strokeW, h))
                    drawRect(color = Color(0xFFB20710), topLeft = Offset(w - w * 0.12f - strokeW, 0f), size = Size(strokeW, h))
                    val diag = Path().apply {
                        moveTo(w * 0.12f, 0f)
                        lineTo(w * 0.12f + strokeW, 0f)
                        lineTo(w - w * 0.12f, h)
                        lineTo(w - w * 0.12f - strokeW, h)
                        close()
                    }
                    drawPath(diag, Color(0xFFE50914))
                }
            }
        }
        "prime_video" -> {
            // Prime Video deep blue/cyan gradient with white "prime" + smile arc
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF00A8E1), Color(0xFF005792))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = this.size.width
                    val h = this.size.height
                    // Smile curve at bottom
                    val smile = Path().apply {
                        moveTo(w * 0.22f, h * 0.68f)
                        quadraticBezierTo(w * 0.50f, h * 0.86f, w * 0.78f, h * 0.66f)
                    }
                    drawPath(smile, Color.White, style = Stroke(width = w * 0.07f, cap = StrokeCap.Round))
                }
                Text(
                    "prime",
                    color = Color.White,
                    fontSize = (size.value * 0.24f).sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(bottom = (size.value * 0.14f).dp)
                )
            }
        }
        "crunchyroll" -> {
            // Crunchyroll vibrant orange with signature white swirl/eye
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFFF47521)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.72f)) {
                    val r = this.size.minDimension / 2f
                    drawCircle(color = Color.White, radius = r)
                    drawCircle(
                        color = Color(0xFFF47521),
                        radius = r * 0.68f,
                        center = Offset(center.x + r * 0.14f, center.y + r * 0.10f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = r * 0.25f,
                        center = Offset(center.x + r * 0.26f, center.y - r * 0.12f)
                    )
                }
            }
        }
        "disney_plus", "hotstar" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF0A174E), Color(0xFF0063E5))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (brandKey == "hotstar") "D+H" else "D+",
                    color = Color(0xFF67E8F9),
                    fontSize = (size.value * 0.34f).sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
        "spotify" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF1DB954)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.65f)) {
                    val w = this.size.width
                    val h = this.size.height
                    for ((i, yRatio) in listOf(0.30f, 0.52f, 0.74f).withIndex()) {
                        val inset = i * 0.07f
                        val p = Path().apply {
                            moveTo(w * (0.12f + inset), h * yRatio)
                            quadraticBezierTo(w * 0.50f, h * (yRatio - 0.10f), w * (0.88f - inset), h * (yRatio + 0.04f))
                        }
                        drawPath(p, Color(0xFF121212), style = Stroke(width = w * (0.11f - i * 0.015f), cap = StrokeCap.Round))
                    }
                }
            }
        }
        "twitch" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF9146FF)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.62f)) {
                    val w = this.size.width
                    val h = this.size.height
                    drawRoundRect(color = Color.White, size = Size(w, h * 0.82f), cornerRadius = CornerRadius(w * 0.15f))
                    drawRect(color = Color(0xFF9146FF), topLeft = Offset(w * 0.32f, h * 0.22f), size = Size(w * 0.11f, h * 0.32f))
                    drawRect(color = Color(0xFF9146FF), topLeft = Offset(w * 0.60f, h * 0.22f), size = Size(w * 0.11f, h * 0.32f))
                }
            }
        }
        "apple_music" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(
                        Brush.linearGradient(colors = listOf(Color(0xFFFA243C), Color(0xFFFF5E7E)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = "Apple Music", tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
        }
        "soundcloud" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(
                        Brush.linearGradient(colors = listOf(Color(0xFFFF7700), Color(0xFFFF3300)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("SC", color = Color.White, fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.Black)
            }
        }
        "vlc" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFFFF8800)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.68f)) {
                    val w = this.size.width
                    val h = this.size.height
                    val cone = Path().apply {
                        moveTo(w * 0.50f, h * 0.08f)
                        lineTo(w * 0.86f, h * 0.88f)
                        lineTo(w * 0.14f, h * 0.88f)
                        close()
                    }
                    drawPath(cone, Color.White)
                    drawRect(color = Color(0xFFFF8800), topLeft = Offset(w * 0.25f, h * 0.42f), size = Size(w * 0.50f, h * 0.14f))
                }
            }
        }
        "chrome" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.74f)) {
                    val r = this.size.minDimension / 2f
                    drawArc(Color(0xFFEA4335), startAngle = 210f, sweepAngle = 120f, useCenter = true)
                    drawArc(Color(0xFFFBBC05), startAngle = 330f, sweepAngle = 120f, useCenter = true)
                    drawArc(Color(0xFF34A853), startAngle = 90f, sweepAngle = 120f, useCenter = true)
                    drawCircle(Color.White, radius = r * 0.46f)
                    drawCircle(Color(0xFF4285F4), radius = r * 0.36f)
                }
            }
        }
        "edge" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(
                        Brush.linearGradient(colors = listOf(Color(0xFF0078D4), Color(0xFF28C7FA), Color(0xFF36D399)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("e", color = Color.White, fontSize = (size.value * 0.48f).sp, fontWeight = FontWeight.Black)
            }
        }
        "brave" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(
                        Brush.linearGradient(colors = listOf(Color(0xFFFF5500), Color(0xFFFF1E00)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("B", color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Black)
            }
        }
        "firefox" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(
                        Brush.linearGradient(colors = listOf(Color(0xFFFF9500), Color(0xFFFF2965), Color(0xFF7542E5)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("FF", color = Color.White, fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.Black)
            }
        }
        "steam" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Brush.linearGradient(colors = listOf(Color(0xFF171A21), Color(0xFF2A475E)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SportsEsports, contentDescription = "Steam", tint = Color(0xFF66C0F4), modifier = Modifier.size(size * 0.6f))
            }
        }
        "discord" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF5865F2)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SportsEsports, contentDescription = "Discord", tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
        }
        "vscode" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF007ACC)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Code, contentDescription = "VS Code", tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
        }
        "obs" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF27272A)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Videocam, contentDescription = "OBS", tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
        }
        "calculator" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF0284C7)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Calculate, contentDescription = "Calculator", tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
        }
        "terminal" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF0F172A)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Terminal, contentDescription = "Terminal", tint = Color(0xFF38BDF8), modifier = Modifier.size(size * 0.6f))
            }
        }
        "taskmgr" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFF0D9488)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MonitorHeart, contentDescription = "Task Manager", tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
        }
        "explorer" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color(0xFFF59E0B)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Folder, contentDescription = "Explorer", tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
        }
        "notepad", "onenote", "word", "excel", "powerpoint" -> {
            val bg = when (brandKey) {
                "onenote" -> Color(0xFF7719AA)
                "word" -> Color(0xFF2B579A)
                "excel" -> Color(0xFF217346)
                "powerpoint" -> Color(0xFFB7472A)
                else -> Color(0xFF0284C7)
            }
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, contentDescription = fallbackName, tint = Color.White, modifier = Modifier.size(size * 0.58f))
            }
        }
        else -> {
            if (decodedFallbackBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(Color(0xFF1E293B)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = decodedFallbackBitmap,
                        contentDescription = fallbackName,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            } else {
                val initials = fallbackName.trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString("") { it.first().uppercaseChar().toString() }
                    .ifBlank { "PC" }
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF4F46E5), Color(0xFF06B6D4))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (initials.isNotEmpty()) {
                        Text(
                            initials,
                            color = Color.White,
                            fontSize = (size.value * 0.34f).sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    } else {
                        Icon(
                            Icons.Default.RocketLaunch,
                            contentDescription = fallbackName,
                            tint = Color.White,
                            modifier = Modifier.size(size * 0.55f)
                        )
                    }
                }
            }
        }
    }
}
