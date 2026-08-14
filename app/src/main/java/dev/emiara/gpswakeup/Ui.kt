package dev.emiara.gpswakeup

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ---- status ----------------------------------------------------------------

@Composable
fun StatusCard(status: TrackingStatus, armed: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (armed) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (armed) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (armed) {
                        stringResource(R.string.status_armed, status.targetName ?: "—")
                    } else {
                        stringResource(R.string.status_idle)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (armed) {
                val distance = status.distanceMeters
                if (distance != null) {
                    Text(
                        text = formatDistance(distance),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    val radius = status.targetRadius.toFloat()
                    val start = (status.closestSoFarMeters ?: distance).coerceAtLeast(distance)
                    val progress = if (start <= radius) {
                        1f
                    } else {
                        ((start - distance) / (start - radius)).coerceIn(0f, 1f)
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.status_waiting_fix),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                if (status.legCount > 1) {
                    LabelledRow(
                        stringResource(R.string.label_route),
                        stringResource(
                            R.string.label_leg_of,
                            status.legIndex + 1,
                            status.legCount,
                            status.routeName ?: "",
                        ),
                    )
                }
                status.nextLegName?.let {
                    LabelledRow(stringResource(R.string.label_then), it)
                }
                LabelledRow(
                    stringResource(R.string.label_ring_within),
                    stringResource(R.string.distance_m, status.targetRadius),
                )
                status.accuracyMeters?.let {
                    LabelledRow(
                        stringResource(R.string.label_accuracy),
                        stringResource(R.string.distance_m, it.roundToInt()),
                    )
                }
                status.provider?.let {
                    LabelledRow(stringResource(R.string.label_source), it)
                }
                if (status.lastFixAtMillis > 0) {
                    LabelledRow(
                        stringResource(R.string.label_last_fix),
                        clockTime(status.lastFixAtMillis),
                    )
                }
                if (status.backstopAtMillis > System.currentTimeMillis()) {
                    LabelledRow(
                        stringResource(R.string.label_backstop),
                        clockTime(status.backstopAtMillis),
                    )
                }
                if (status.snoozedUntilMillis > System.currentTimeMillis()) {
                    LabelledRow(
                        stringResource(R.string.label_snoozed_until),
                        clockTime(status.snoozedUntilMillis),
                    )
                }
                if (!status.serviceRunning) {
                    Text(
                        text = stringResource(R.string.status_service_restarting),
                        style = MaterialTheme.typography.bodySmall,
                        color = WarnAmber,
                    )
                }
                status.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = WarnAmber,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.status_idle_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Surface(
        modifier = Modifier.size(10.dp),
        shape = CircleShape,
        color = color,
    ) {}
}

// ---- stops -----------------------------------------------------------------

@Composable
fun StopsSection(
    stops: List<Stop>,
    selectedStopId: String?,
    armed: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Stop) -> Unit,
    onDelete: (Stop) -> Unit,
) {
    SectionCard(title = stringResource(R.string.section_stops)) {
        if (stops.isEmpty()) {
            Text(
                text = stringResource(R.string.stops_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        stops.forEach { stop ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = stop.id == selectedStopId,
                        enabled = !armed,
                        onClick = { onSelect(stop.id) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = stop.id == selectedStopId,
                    enabled = !armed,
                    onClick = { onSelect(stop.id) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(stop.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(
                            R.string.stop_subtitle,
                            stop.lat,
                            stop.lon,
                            stop.radiusMeters,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onEdit(stop) }, enabled = !armed) {
                    Text(stringResource(R.string.action_edit))
                }
                TextButton(onClick = { onDelete(stop) }, enabled = !armed) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onAdd, enabled = !armed) {
            Text(stringResource(R.string.action_add_stop))
        }
    }
}

// ---- readiness -------------------------------------------------------------

@Composable
fun ReadinessSection(
    items: List<ReadinessItem>,
    onFix: (ReadinessItem) -> Unit,
    onToggleManual: (ReadinessItem, Boolean) -> Unit,
) {
    val blocking = Readiness.blockingCount(items)
    SectionCard(
        title = stringResource(R.string.section_readiness),
        subtitle = if (blocking == 0) {
            stringResource(R.string.readiness_all_good)
        } else {
            stringResource(R.string.readiness_missing, blocking)
        },
        subtitleColor = if (blocking == 0) OkGreen else WarnAmber,
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.manual) {
                    Checkbox(
                        checked = item.ok,
                        onCheckedChange = { onToggleManual(item, it) },
                    )
                } else {
                    Text(
                        text = if (item.ok) "✓" else "!",
                        color = when {
                            item.ok -> OkGreen
                            item.severity == Severity.REQUIRED -> MaterialTheme.colorScheme.error
                            else -> WarnAmber
                        },
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        modifier = Modifier.width(28.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.severity == Severity.RECOMMENDED && !item.ok) {
                        Text(
                            text = stringResource(R.string.readiness_optional),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (item.actionLabel != null && (!item.ok || item.manual)) {
                    TextButton(onClick = { onFix(item) }) { Text(item.actionLabel) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

// ---- settings --------------------------------------------------------------

@Composable
fun SettingsSection(context: Context, onChanged: () -> Unit) {
    var forceVolume by remember { mutableStateOf(Prefs.forceMaxVolume(context)) }
    var vibrate by remember { mutableStateOf(Prefs.vibrate(context)) }
    var missedGuard by remember { mutableStateOf(Prefs.missedStopGuard(context)) }
    var backstop by remember { mutableFloatStateOf(Prefs.backstopMinutes(context).toFloat()) }
    var snooze by remember { mutableFloatStateOf(Prefs.snoozeMinutes(context).toFloat()) }

    SectionCard(title = stringResource(R.string.section_settings)) {
        SwitchRow(
            title = stringResource(R.string.setting_force_volume),
            detail = stringResource(R.string.setting_force_volume_detail),
            checked = forceVolume,
        ) {
            forceVolume = it
            Prefs.setForceMaxVolume(context, it)
            onChanged()
        }
        SwitchRow(
            title = stringResource(R.string.setting_vibrate),
            detail = stringResource(R.string.setting_vibrate_detail),
            checked = vibrate,
        ) {
            vibrate = it
            Prefs.setVibrate(context, it)
            onChanged()
        }
        SwitchRow(
            title = stringResource(R.string.setting_missed_guard),
            detail = stringResource(R.string.setting_missed_guard_detail),
            checked = missedGuard,
        ) {
            missedGuard = it
            Prefs.setMissedStopGuard(context, it)
            onChanged()
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (backstop.roundToInt() == 0) {
                stringResource(R.string.setting_backstop_off)
            } else {
                stringResource(R.string.setting_backstop_on, backstop.roundToInt())
            },
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.setting_backstop_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = backstop,
            onValueChange = { backstop = it },
            onValueChangeFinished = {
                Prefs.setBackstopMinutes(context, backstop.roundToInt())
                onChanged()
            },
            valueRange = 0f..180f,
            steps = 35,
        )

        Text(
            text = stringResource(R.string.setting_snooze, snooze.roundToInt()),
            fontWeight = FontWeight.SemiBold,
        )
        Slider(
            value = snooze,
            onValueChange = { snooze = it },
            onValueChangeFinished = {
                Prefs.setSnoozeMinutes(context, snooze.roundToInt())
                onChanged()
            },
            valueRange = 1f..15f,
            steps = 13,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---- shared bits -----------------------------------------------------------

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleColor == Color.Unspecified) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        subtitleColor
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

internal fun formatDistance(meters: Float): String =
    if (meters >= 1000f) {
        String.format(Locale.getDefault(), "%.2f km", meters / 1000f)
    } else {
        "${meters.roundToInt()} m"
    }

internal fun clockTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
