package com.btmouse.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.ui.theme.Control
import com.btmouse.app.ui.theme.DangerContainer
import com.btmouse.app.ui.theme.Ink
import com.btmouse.app.ui.theme.OnDanger
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.OnInkFaint
import com.btmouse.app.ui.theme.OnInkMuted
import com.btmouse.app.ui.theme.Outline
import com.btmouse.app.ui.theme.Panel
import com.btmouse.app.ui.theme.Selected
import com.btmouse.app.ui.theme.SelectedSegment

/** How a button stands out: white for the one main action, grey when on or selected, quiet otherwise. */
enum class PillStyle { Primary, Selected, Tonal, Outline, Danger, Quiet }

private fun PillStyle.colors(): Pair<Color, Color> = when (this) {
    PillStyle.Primary -> OnInk to Ink
    PillStyle.Selected -> Selected to Color.White
    PillStyle.Tonal -> Control to OnInk
    PillStyle.Outline -> Color.Transparent to OnInk
    PillStyle.Danger -> DangerContainer to OnDanger
    PillStyle.Quiet -> Color.Transparent to OnInk
}

/** Pressed controls brighten slightly, the same feedback the desktop companion gives. */
private fun Color.pressed(pressed: Boolean): Color =
    if (!pressed) this else if (this == Color.Transparent) Control else lerpTowardWhite(this)

private fun lerpTowardWhite(c: Color): Color =
    Color(c.red + (1f - c.red) * 0.12f, c.green + (1f - c.green) * 0.12f, c.blue + (1f - c.blue) * 0.12f, c.alpha)

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Tonal,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp)
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (bg, fg) = style.colors()
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = modifier
            .heightIn(min = height)
            .clip(CircleShape)
            .background(bg.pressed(pressed))
            .then(if (style == PillStyle.Outline) Modifier.border(BorderStroke(1.dp, Outline), CircleShape) else Modifier)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg.copy(alpha = alpha), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Icon-only round button with a spoken label. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Tonal,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    tint: Color? = null
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (bg, fg) = style.colors()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg.pressed(pressed))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint ?: fg, modifier = Modifier.size(iconSize))
    }
}

/** A row of equal choices with the chosen one lifted in grey. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Control)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(if (on) SelectedSegment else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) Color.White else OnInkMuted,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Icon over a short label; used for quick actions and power buttons. */
@Composable
fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Quiet,
    height: Dp = 64.dp,
    tint: Color? = null
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (bg, fg) = style.colors()
    Column(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .background(bg.pressed(pressed))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(5.dp))
        Text(label, color = tint ?: fg, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** Black-and-white switch matching the desktop: white track with a black knob when on. */
@Composable
fun MonoSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (checked) OnInk else SelectedSegment)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(if (checked) Ink else Color(0xFF9A9A9A)))
    }
}

/** A rounded panel one shade lighter than the background. */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Panel)
            .padding(padding),
        content = content
    )
}

/** Section title with an optional underlined text action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = OnInk, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    action,
                    color = OnInk,
                    fontSize = 15.sp,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )
            }
        }
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Secondary line of text under a title. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = OnInkMuted) {
    Text(text, color = color, fontSize = 14.sp, lineHeight = 20.sp, modifier = modifier)
}

@Composable
fun FaintNote(text: String, modifier: Modifier = Modifier) {
    Text(text, color = OnInkFaint, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth())
}

/** Rounded status pill with a dot, e.g. "Live on PC" or "Off". Never wraps. */
@Composable
fun StatusPill(text: String, live: Boolean, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Control)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        StatusDot(dot ?: if (live) com.btmouse.app.ui.theme.StatusGreen else OnInkFaint, size = 7.dp)
        Text(text, color = if (live) OnInk else OnInkMuted, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

/** Card title with one line of detail on the left and a status pill on the right. */
@Composable
fun CardHeader(title: String, detail: String, status: String, live: Boolean, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OnInk, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, color = OnInkMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        StatusPill(status, live, dot = dot)
    }
}
