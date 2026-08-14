package dev.emiara.gpswakeup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID

/**
 * The saved-journeys list. Arming a route arms its first leg; each alarm you dismiss arms
 * the next one, so the final destination is never left un-armed.
 */
@Composable
fun RoutesSection(
    routes: List<Route>,
    stops: List<Stop>,
    selectedRouteId: String?,
    armed: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Route) -> Unit,
    onDelete: (Route) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.section_routes),
        subtitle = stringResource(R.string.routes_subtitle),
    ) {
        if (routes.isEmpty()) {
            Text(
                text = stringResource(R.string.routes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        routes.forEach { route ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = route.id == selectedRouteId,
                        enabled = !armed,
                        onClick = { onSelect(route.id) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = route.id == selectedRouteId,
                    enabled = !armed,
                    onClick = { onSelect(route.id) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(route.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = describe(route, stops),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onEdit(route) }, enabled = !armed) {
                    Text(stringResource(R.string.action_edit))
                }
                TextButton(onClick = { onDelete(route) }, enabled = !armed) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onAdd, enabled = !armed) {
            Text(stringResource(R.string.action_add_route))
        }
    }
}

private fun describe(route: Route, stops: List<Stop>): String {
    if (route.legs.isEmpty()) return "—"
    return route.legs.joinToString(" → ") { leg ->
        stops.firstOrNull { it.id == leg.stopId }?.name ?: "?"
    }
}

/**
 * Build a journey out of saved stops, in the order you'll reach them: transfer first,
 * final destination last.
 */
@Composable
fun RouteEditorDialog(
    existing: Route?,
    stops: List<Stop>,
    onDismiss: () -> Unit,
    onSave: (Route) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    val legs = remember { mutableStateListOf<RouteLeg>().apply { addAll(existing?.legs.orEmpty()) } }
    var showStopPicker by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (existing == null) R.string.dialog_add_route else R.string.dialog_edit_route,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.route_name_label)) },
                        placeholder = { Text(stringResource(R.string.route_name_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = stringResource(R.string.route_legs_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.route_legs_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (legs.isEmpty()) {
                        Text(
                            text = stringResource(R.string.route_no_legs),
                            style = MaterialTheme.typography.bodySmall,
                            color = WarnAmber,
                        )
                    }

                    legs.forEachIndexed { index, leg ->
                        val stop = stops.firstOrNull { it.id == leg.stopId }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}.",
                                modifier = Modifier.width(28.dp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stop?.name
                                        ?: stringResource(R.string.route_missing_stop),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (index == legs.lastIndex) {
                                        stringResource(R.string.route_leg_final)
                                    } else {
                                        stringResource(R.string.route_leg_transfer)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (index > 0) {
                                        val moved = legs.removeAt(index)
                                        legs.add(index - 1, moved)
                                    }
                                },
                                enabled = index > 0,
                            ) { Text("↑") }
                            TextButton(
                                onClick = {
                                    if (index < legs.lastIndex) {
                                        val moved = legs.removeAt(index)
                                        legs.add(index + 1, moved)
                                    }
                                },
                                enabled = index < legs.lastIndex,
                            ) { Text("↓") }
                            TextButton(onClick = { legs.removeAt(index) }) {
                                Text("✕", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }

                    TextButton(onClick = { showStopPicker = true }) {
                        Text(stringResource(R.string.route_add_leg))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            onSave(
                                Route(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    legs = legs.toList(),
                                ),
                            )
                        },
                        enabled = name.isNotBlank() && legs.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showStopPicker) {
        StopPickerDialog(
            stops = stops,
            onDismiss = { showStopPicker = false },
            onPick = { stop ->
                legs.add(RouteLeg(stopId = stop.id))
                showStopPicker = false
            },
        )
    }
}

@Composable
private fun StopPickerDialog(
    stops: List<Stop>,
    onDismiss: () -> Unit,
    onPick: (Stop) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_pick_stop_title)) },
        text = {
            if (stops.isEmpty()) {
                Text(stringResource(R.string.route_pick_stop_empty))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    stops.forEach { stop ->
                        Text(
                            text = stop.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(stop) }
                                .padding(vertical = 12.dp),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
