package dev.emiara.gpswakeup

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService

/**
 * The ringing screen. Shows over the lock screen with the display forced on, and cannot be
 * dismissed with Back — you have to actually press a button, which is the point.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()

        // Swallow Back so a half-asleep hand can't silently cancel the alarm.
        onBackPressedDispatcher.addCallback(this) { }

        setContent {
            WakeupTheme {
                val status by TrackerState.status.collectAsState()
                AlarmScreen(
                    stopName = status.targetName,
                    reason = status.alarmReason,
                    snoozeMinutes = Prefs.snoozeMinutes(this),
                    onDismiss = {
                        TrackingService.sendAction(this, TrackingService.ACTION_DISMISS)
                        finish()
                    },
                    onSnooze = {
                        TrackingService.sendAction(this, TrackingService.ACTION_SNOOZE)
                        finish()
                    },
                )
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            getSystemService<KeyguardManager>()?.requestDismissKeyguard(this, null)
        }
    }
}

@Composable
private fun AlarmScreen(
    stopName: String?,
    reason: String?,
    snoozeMinutes: Int,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AlarmRed)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.alarm_title),
                color = Color.White,
                fontSize = 44.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stopName ?: stringResource(R.string.alarm_unknown_stop),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (!reason.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = reason,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = AlarmRed,
                ),
            ) {
                Text(
                    text = stringResource(R.string.action_im_awake),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text(
                    text = stringResource(R.string.action_snooze_minutes, snoozeMinutes),
                    fontSize = 18.sp,
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.alarm_hint),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
