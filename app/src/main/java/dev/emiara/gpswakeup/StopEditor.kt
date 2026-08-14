package dev.emiara.gpswakeup

import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.getSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private const val TAB_SEARCH = 0
private const val TAB_MANUAL = 1

/**
 * Add or edit a stop, two ways: search Norwegian public transport, or type coordinates.
 * Either way, the same confirmation block at the bottom shows exactly what will be saved.
 */
@Composable
fun StopEditorDialog(
    existing: Stop?,
    onDismiss: () -> Unit,
    onSave: (Stop) -> Unit,
) {
    val context = LocalContext.current

    var tab by remember { mutableIntStateOf(TAB_SEARCH) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var lat by remember { mutableStateOf(existing?.lat?.let { format(it) } ?: "") }
    var lon by remember { mutableStateOf(existing?.lon?.let { format(it) } ?: "") }
    var radius by remember {
        mutableFloatStateOf((existing?.radiusMeters ?: Prefs.DEFAULT_RADIUS).toFloat())
    }

    fun apply(pickedName: String, pickedLat: Double, pickedLon: Double) {
        name = pickedName
        lat = format(pickedLat)
        lon = format(pickedLon)
    }

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
                            if (existing == null) R.string.dialog_add_stop else R.string.dialog_edit_stop,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }

                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == TAB_SEARCH,
                        onClick = { tab = TAB_SEARCH },
                        text = { Text(stringResource(R.string.tab_search)) },
                    )
                    Tab(
                        selected = tab == TAB_MANUAL,
                        onClick = { tab = TAB_MANUAL },
                        text = { Text(stringResource(R.string.tab_manual)) },
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        TAB_SEARCH -> SearchTab(onPick = { apply(it.name, it.lat, it.lon) })

                        else -> ManualTab(
                            lat = lat,
                            lon = lon,
                            onLatChange = { lat = it },
                            onLonChange = { lon = it },
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                // ---- confirmation block, shared by both tabs ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.field_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    val coordsValid = isValidLatitude(lat) && isValidLongitude(lon)
                    Text(
                        text = if (coordsValid) {
                            stringResource(R.string.editor_selected, lat.trim(), lon.trim())
                        } else {
                            stringResource(R.string.editor_nothing_selected)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (coordsValid) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            WarnAmber
                        },
                    )

                    Text(
                        text = stringResource(R.string.field_radius, radius.roundToInt()),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Slider(
                        value = radius,
                        onValueChange = { radius = it },
                        valueRange = Prefs.MIN_RADIUS.toFloat()..Prefs.MAX_RADIUS.toFloat(),
                        steps = 28,
                    )
                    Text(
                        text = stringResource(R.string.field_radius_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = {
                            val latValue = lat.trim().toDoubleOrNull()
                            val lonValue = lon.trim().toDoubleOrNull()
                            if (name.isBlank() ||
                                latValue == null || lonValue == null ||
                                latValue !in -90.0..90.0 || lonValue !in -180.0..180.0
                            ) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_invalid_stop),
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@Button
                            }
                            onSave(
                                Stop(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    lat = latValue,
                                    lon = lonValue,
                                    radiusMeters = radius.roundToInt(),
                                ),
                            )
                        },
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
}

private fun isValidLatitude(raw: String): Boolean =
    raw.trim().toDoubleOrNull()?.let { it in -90.0..90.0 } == true

private fun isValidLongitude(raw: String): Boolean =
    raw.trim().toDoubleOrNull()?.let { it in -180.0..180.0 } == true

// ---- search ----------------------------------------------------------------

@Composable
private fun SearchTab(onPick: (StopSuggestion) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StopSuggestion>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    // Bias results towards where the phone already thinks it is.
    val focus = remember { Locate.lastKnown(context) }

    LaunchedEffect(query) {
        val text = query.trim()
        if (text.length < 2) {
            results = emptyList()
            loading = false
            error = null
            return@LaunchedEffect
        }
        delay(350) // debounce — a new keystroke cancels and restarts this effect
        loading = true
        error = null
        val outcome = Entur.autocomplete(text, focus?.latitude, focus?.longitude)
        loading = false
        searched = true
        outcome
            .onSuccess { results = it }
            .onFailure {
                results = emptyList()
                error = context.getString(R.string.search_failed)
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_label)) },
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            val here = Locate.lastKnown(context)
                            if (here == null) {
                                loading = false
                                error = context.getString(R.string.search_need_location)
                                return@launch
                            }
                            val outcome = Entur.reverse(here.latitude, here.longitude)
                            loading = false
                            searched = true
                            outcome
                                .onSuccess { results = it }
                                .onFailure { error = context.getString(R.string.search_failed) }
                        }
                    },
                ) { Text(stringResource(R.string.search_nearby)) }

                if (loading) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = WarnAmber)
            }
            if (!loading && error == null && searched && results.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!searched && query.isBlank()) {
                Text(
                    text = stringResource(R.string.search_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(results) { suggestion ->
                SuggestionRow(suggestion) { onPick(suggestion) }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: StopSuggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(suggestion.name, fontWeight = FontWeight.SemiBold)
            val subtitle = listOf(suggestion.kind, suggestion.where)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = stringResource(R.string.action_pick),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// ---- manual ----------------------------------------------------------------

@Composable
private fun ManualTab(
    lat: String,
    lon: String,
    onLatChange: (String) -> Unit,
    onLonChange: (String) -> Unit,
) {
    val context = LocalContext.current
    var locating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.manual_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = lat,
                onValueChange = onLatChange,
                label = { Text(stringResource(R.string.field_lat)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = lon,
                onValueChange = onLonChange,
                label = { Text(stringResource(R.string.field_lon)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(
            onClick = {
                if (!Locate.hasPermission(context)) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_need_location),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@OutlinedButton
                }
                locating = true
                Locate.once(context) { location ->
                    locating = false
                    if (location == null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_no_fix),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        onLatChange(format(location.latitude))
                        onLonChange(format(location.longitude))
                    }
                }
            },
            enabled = !locating,
        ) {
            Text(
                stringResource(
                    if (locating) R.string.action_locating else R.string.action_use_current,
                ),
            )
        }
    }
}

private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)
