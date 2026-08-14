package dev.emiara.gpswakeup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : ComponentActivity() {

    /** Text handed to us by the share sheet (a Google Maps link, usually). */
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannels(this)
        sharedText.value = extractShared(intent)
        setContent {
            WakeupTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        sharedLink = sharedText.value,
                        onSharedLinkConsumed = { sharedText.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractShared(intent)?.let { sharedText.value = it }
    }

    /** Pull a usable link out of a SEND or VIEW intent. */
    private fun extractShared(intent: Intent?): String? {
        if (intent == null) return null
        val candidate = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        return candidate.takeIf { MapsLink.looksLikeLink(it) }
    }
}

/** Try each candidate settings screen until one opens. */
private fun launchFirstWorking(ctx: Context, intents: List<Intent>): Boolean {
    for (intent in intents) {
        val started = runCatching {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (started) return true
    }
    return false
}

@Composable
private fun HomeScreen(
    sharedLink: String? = null,
    onSharedLinkConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val status by TrackerState.status.collectAsState()

    var refreshKey by remember { mutableIntStateOf(0) }
    var stops by remember { mutableStateOf(Prefs.stops(context)) }
    var selectedStopId by remember { mutableStateOf(Prefs.armedStopId(context) ?: Prefs.stops(context).firstOrNull()?.id) }
    var editingStop by remember { mutableStateOf<Stop?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var showArmWarning by remember { mutableStateOf(false) }
    var settingsVersion by remember { mutableIntStateOf(0) }

    val readiness = remember(refreshKey, settingsVersion) { Readiness.evaluate(context) }
    val blocking = Readiness.blockingCount(readiness)
    val armed = remember(refreshKey, stops, status.serviceRunning) { Prefs.isArmed(context) }

    fun reload() {
        stops = Prefs.stops(context)
        refreshKey++
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload() }

    // A link arrived from the share sheet — open the editor straight onto that tab.
    LaunchedEffect(sharedLink) {
        if (!sharedLink.isNullOrBlank()) {
            editingStop = null
            pendingLink = sharedLink
            showAddDialog = true
            onSharedLinkConsumed()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { reload() }

    fun fix(item: ReadinessItem) {
        when (item.id) {
            Readiness.ID_NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    launchFirstWorking(context, Readiness.fixIntents(context, item.id))
                }
            }

            Readiness.ID_LOCATION_FINE -> permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )

            Readiness.ID_LOCATION_BACKGROUND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
            }

            Readiness.ID_VOLUME -> {
                Readiness.setAlarmVolumeToMax(context)
                reload()
            }

            Readiness.ID_OEM -> {
                launchFirstWorking(context, Readiness.fixIntents(context, item.id))
                Prefs.setManualStepDone(context, Readiness.ID_OEM, true)
            }

            else -> launchFirstWorking(context, Readiness.fixIntents(context, item.id))
        }
    }

    fun armNow() {
        val id = selectedStopId ?: return
        TrackingService.arm(context, id)
        reload()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            StatusCard(status = status, armed = armed)

            if (armed) {
                Button(
                    onClick = {
                        TrackingService.disarm(context)
                        reload()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) {
                    Text(stringResource(R.string.action_cancel_alarm), fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        if (selectedStopId == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_pick_a_stop),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else if (blocking > 0) {
                            showArmWarning = true
                        } else {
                            armNow()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                ) {
                    Text(stringResource(R.string.action_arm), fontWeight = FontWeight.Bold)
                }
            }

            StopsSection(
                stops = stops,
                selectedStopId = selectedStopId,
                armed = armed,
                onSelect = { selectedStopId = it },
                onAdd = {
                    editingStop = null
                    pendingLink = null
                    showAddDialog = true
                },
                onEdit = {
                    editingStop = it
                    pendingLink = null
                    showAddDialog = true
                },
                onDelete = {
                    Prefs.deleteStop(context, it.id)
                    if (selectedStopId == it.id) selectedStopId = null
                    reload()
                },
            )

            ReadinessSection(
                items = readiness,
                onFix = { fix(it) },
                onToggleManual = { item, done ->
                    Prefs.setManualStepDone(context, item.id, done)
                    reload()
                },
            )

            SettingsSection(
                context = context,
                onChanged = {
                    settingsVersion++
                    reload()
                },
            )

            OutlinedButton(
                onClick = {
                    TrackingService.sendAction(context, TrackingService.ACTION_TEST_ALARM)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.action_test_alarm))
            }

            Text(
                text = stringResource(R.string.footer_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAddDialog) {
        StopEditorDialog(
            existing = editingStop,
            initialLink = pendingLink,
            onDismiss = {
                showAddDialog = false
                pendingLink = null
            },
            onSave = { stop ->
                Prefs.upsertStop(context, stop)
                selectedStopId = stop.id
                showAddDialog = false
                pendingLink = null
                reload()
            },
        )
    }

    if (showArmWarning) {
        AlertDialog(
            onDismissRequest = { showArmWarning = false },
            title = { Text(stringResource(R.string.warn_title)) },
            text = { Text(stringResource(R.string.warn_body, blocking)) },
            confirmButton = {
                TextButton(onClick = {
                    showArmWarning = false
                    armNow()
                }) { Text(stringResource(R.string.warn_arm_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { showArmWarning = false }) {
                    Text(stringResource(R.string.warn_fix_first))
                }
            },
        )
    }
}

@Composable
internal fun LabelledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
