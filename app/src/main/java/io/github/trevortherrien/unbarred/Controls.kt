package io.github.trevortherrien.unbarred

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// --------------------------------------------------------------------------------------
// Building blocks for the "Bold type" UI. Live-bound controls lead while the user touches
// them, then snap to the bar's reported value (so the remote / another phone show here too).
// --------------------------------------------------------------------------------------

@Composable
fun <T> StateFlow<T>.state(): State<T> = collectAsState()

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = SectionLabelStyle, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A rounded dark panel grouping related rows. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Ub.Surface,
        border = BorderStroke(1.dp, Ub.Outline),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun RoundIconButton(
    @DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true, size: Int = 48,
    highlighted: Boolean = false,
) {
    val container = if (filled) Ub.Accent else if (highlighted) Ub.AccentSoft else Ub.Surface
    val contentColor = if (filled) Ub.OnAccent else if (highlighted) Ub.Accent else Ub.Text
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.size(size.dp), shape = CircleShape,
        color = if (enabled) container else Ub.Surface,
        contentColor = if (enabled) contentColor else Ub.Muted,
        border = when {
            filled && enabled -> null
            highlighted && enabled -> BorderStroke(1.5.dp, Ub.Accent)
            else -> BorderStroke(1.dp, Ub.Outline)
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription, Modifier.size(22.dp))
        }
    }
}

/** Slider bound to a characteristic. [showHeader] false renders the bare slider (volume hero). */
@Composable
fun LiveLevelSlider(
    label: String, char: UUID, values: Map<UUID, Int>,
    min: Int, max: Int, enabled: Boolean, showHeader: Boolean = true, onChange: (Int) -> Unit,
) {
    val barValue = values[char]
    val scope = rememberCoroutineScope()
    var local by remember { mutableFloatStateOf((barValue ?: 0).coerceIn(min, max).toFloat()) }
    var lastSent by remember { mutableIntStateOf(local.toInt()) }
    var interacting by remember { mutableStateOf(false) }

    LaunchedEffect(barValue) {
        if (!interacting && barValue != null && barValue.toFloat() != local) {
            local = barValue.coerceIn(min, max).toFloat(); lastSent = barValue
        }
    }
    Column {
        if (showHeader) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                val shown = if (barValue == null && !interacting) "—" else local.toInt().let { v ->
                    if (min < 0 && v > 0) "+$v" else "$v"
                }
                Text(shown, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = if (barValue != null && local.toInt() != 0 && min < 0) Ub.Accent else Ub.Text)
            }
        }
        Slider(
            value = local,
            onValueChange = {
                interacting = true
                local = it
                val v = it.toInt()
                if (v != lastSent) { lastSent = v; onChange(v) }
            },
            onValueChangeFinished = {
                onChange(local.toInt())
                // Keep ignoring echoes briefly so a lagging NOTIFY doesn't yank the thumb.
                scope.launch { delay(600); interacting = false }
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = if (max - min <= 40) (max - min - 1).coerceAtLeast(0) else 0,
            enabled = enabled,
            colors = SliderDefaults.colors(
                inactiveTrackColor = Ub.Track,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** Settings-style row with a switch, bound to a 0/1 characteristic. */
@Composable
fun LiveSwitchRow(
    label: String, char: UUID, values: Map<UUID, Int>, enabled: Boolean, onChange: (Boolean) -> Unit,
) {
    val barValue = values[char]
    var local by remember { mutableStateOf((barValue ?: 0) == 1) }
    LaunchedEffect(barValue) { if (barValue != null) local = (barValue == 1) }
    SwitchRow(label, null, local, enabled) { local = it; onChange(it) }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Ub.Muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = Ub.Track, uncheckedThumbColor = Ub.Muted, uncheckedBorderColor = Ub.Outline,
            ),
        )
    }
}

/**
 * Single choice bound to an enum characteristic. [big] = equal-width chunky buttons (sound mode);
 * otherwise outline pills that wrap.
 */
/** How a [LiveChoice] is drawn. LARGE and COMPACT share one look (separate equal-width buttons). */
enum class ChoiceStyle { LARGE, COMPACT, PILLS }

/**
 * Single choice bound to an enum characteristic. LARGE = chunky equal-width buttons (sound mode);
 * COMPACT = the same buttons, smaller (surround, height); PILLS = outline pills that wrap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveChoice(
    options: Map<String, Int>, barValue: Int?, enabled: Boolean,
    style: ChoiceStyle = ChoiceStyle.PILLS, onPick: (Int) -> Unit,
) {
    var selected by remember { mutableStateOf(barValue) }
    LaunchedEffect(barValue) { if (barValue != null) selected = barValue }
    if (style != ChoiceStyle.PILLS) {
        val large = style == ChoiceStyle.LARGE
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (label, v) ->
                val on = selected == v
                Surface(
                    onClick = { selected = v; onPick(v) }, enabled = enabled,
                    modifier = Modifier.weight(1f).height(if (large) 50.dp else 40.dp),
                    shape = RoundedCornerShape(if (large) 16.dp else 12.dp),
                    color = if (on) Ub.Accent else Ub.Surface,
                    contentColor = if (on) Ub.OnAccent else if (enabled) Ub.Text else Ub.Muted,
                    border = if (on) null else BorderStroke(1.dp, Ub.Outline),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, fontSize = if (large) 17.sp else 14.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (label, v) -> Pill(label, selected == v, enabled) { selected = v; onPick(v) } }
        }
    }
}

@Composable
fun Pill(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(22.dp),
        color = if (selected) Ub.AccentSoft else Color.Transparent,
        contentColor = if (selected) Ub.Accent else if (enabled) Ub.Text else Ub.Muted,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Ub.Accent else Ub.Outline),
    ) {
        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

/** Effect tile (icon, name, On/Off) bound to a 0/1 characteristic. */
@Composable
fun LiveToggleTile(
    label: String, @DrawableRes icon: Int, char: UUID, values: Map<UUID, Int>, enabled: Boolean,
    modifier: Modifier = Modifier, onChange: (Boolean) -> Unit,
) {
    val barValue = values[char]
    var on by remember { mutableStateOf((barValue ?: 0) == 1) }
    LaunchedEffect(barValue) { if (barValue != null) on = (barValue == 1) }
    Surface(
        onClick = { on = !on; onChange(on) }, enabled = enabled,
        modifier = modifier.height(88.dp),
        shape = RoundedCornerShape(18.dp),
        color = Ub.Surface,
        border = BorderStroke(if (on) 1.5.dp else 1.dp, if (on) Ub.Accent else Ub.Outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (on) Ub.Accent else Ub.Muted)
            Column {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Ub.Text else Ub.Muted)
                Text(if (barValue == null) "—" else if (on) "On" else "Off", fontSize = 12.sp,
                    color = if (on) Ub.Accent else Ub.Muted)
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = Ub.Muted)
        Text(value ?: "—", fontSize = 14.sp)
    }
}
