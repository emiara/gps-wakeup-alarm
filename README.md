# Bus Stop Alarm (gps-wakeup-alarm)

Android app that lets you set wake-up alarms based on GPS positions so that you don't miss
your stop on your way home while sleepy on the bus.

Built for one job: **you fall asleep on the night bus, and the alarm still goes off.**

## How it works

Pick the stop you get off at, hit **Arm alarm**, and sleep. A foreground service watches
your position and rings a full-screen, lock-screen-piercing alarm on the alarm audio stream
once you're inside the stop's radius.

Three independent things can wake you, so a single failure doesn't cost you your stop:

| Trigger | Fires when |
| --- | --- |
| **Geofence** | You're within the stop's radius (default 500 m) and the fix is trustworthy. |
| **Missed-stop guard** | You got close and are now clearly moving away — you slept through it. |
| **Time backstop** | Optional plain clock alarm N minutes after arming, works with no GPS at all. |

## Why it stays alive

Night-bus reliability is the whole point, so the app fights the usual Android app-killers:

- **Foreground service** (`location` type) with a partial wake lock — tracking keeps running
  with the screen off and after the app is swiped away.
- **Watchdog exact alarm** every 4 minutes. If the OS killed the service, it comes straight
  back. Exact alarms also grant the app an exemption from Android 12+ background
  foreground-service-start restrictions.
- **Boot receiver** — if the phone reboots mid-journey, tracking resumes.
- **`START_STICKY` + `onTaskRemoved`** handling so swiping the app away re-arms the watchdog.
- **Battery-optimisation exemption** requested up front, plus deep links into the
  manufacturer-specific autostart screens (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Samsung,
  Asus, Realme, Honor).
- **Ringing survives a process kill** — the alarm state is persisted, so if the app is killed
  mid-alarm the service picks it back up.

## Why it's audible

- Plays on `STREAM_ALARM` with `USAGE_ALARM` audio attributes, which bypasses the ringer's
  silent/vibrate mode and (with DND access granted) Do Not Disturb.
- Optionally **forces the alarm volume to maximum** when it rings, then restores it.
- **Full-screen intent** notification on a MAX-importance channel — takes over a locked
  screen rather than showing a banner you'll sleep through.
- Wakes the display, shows over the lock screen, and **disables the Back button** so a
  half-asleep hand can't silently cancel it.
- Vibration waveform in parallel.
- **Bundled fallback tone** (`res/raw/alarm_tone.wav`) in case the device has no usable
  default alarm ringtone.

## Permissions and why each one is needed

| Permission | Why |
| --- | --- |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Knowing where the bus is. |
| `ACCESS_BACKGROUND_LOCATION` | Tracking with the screen off / app in the background. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | The long-running tracking service. |
| `POST_NOTIFICATIONS` | Tracking notification and the alarm notification. |
| `USE_FULL_SCREEN_INTENT` | Taking over the lock screen when it rings. |
| `WAKE_LOCK` | Keeping the CPU alive between fixes; waking the screen for the alarm. |
| `DISABLE_KEYGUARD` | Showing the alarm screen over the lock screen. |
| `VIBRATE` | Vibration alongside the sound. |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Watchdog, snooze and time backstop. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Not being frozen by Doze mid-ride. |
| `RECEIVE_BOOT_COMPLETED` | Resuming after a reboot. |
| `ACCESS_NOTIFICATION_POLICY` | Ringing through Do Not Disturb. |
| `MODIFY_AUDIO_SETTINGS` | Raising the alarm volume when it fires. |

The **"Will the alarm go off?"** checklist on the main screen verifies every one of these at
runtime — plus the device location switch, the alarm channel's importance, the alarm stream
volume and (Android 14+) the full-screen-intent grant — with a one-tap fix for each.

**There is a "Test the alarm now" button. Use it once at home before you rely on this.**

## No Google Play Services

Location comes from the platform `LocationManager` (GPS + network + fused providers), not
from Play Services. No API key, no Google dependency, works on de-Googled phones.

Update frequency adapts to distance: roughly every 2 minutes when you're 20 km out, every
second once you're within a few hundred metres, so it isn't pointlessly draining the battery
for the first half of the ride.

## Building

Requires JDK 17 and the Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Or grab the APK from the **Build APK** GitHub Actions run for the latest commit — the
workflow builds it on every push and uploads it as an artifact.

Install with `adb install -r app-debug.apk`, or copy the APK to the phone and open it.

## Setting up a stop

Easiest: stand at the stop, open the app, **Add a stop → Use my current location**.
Otherwise, get the coordinates from any map app and type them in.

Radius guidance: at 50 km/h a bus covers 500 m in about 36 seconds — enough time to wake up,
gather your things and press the bell. Set it larger for fast routes, smaller for dense city
stops where 500 m might cover two stops.
