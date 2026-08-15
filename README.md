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

## Finding your stop

**Search** — every bus stop, Bybanen platform, train station, ferry quay and address in
Norway, via [Entur's geocoder](https://developer.entur.org/pages-geocoder-intro), the
national public transport data service. No API key. Results are biased towards your current
position, so searching "Sentrum" in Bergen gives you the Bergen one. There's a **Stops near
me** button that reverse-geocodes your position into real stop names.

**Manual** — coordinates typed in, or captured from where you're standing.

Search is the only thing that touches the network. **Tracking and the alarm work entirely
offline** — once a stop is saved, you can be in a tunnel with no signal and it still rings.

## Saved routes

A route is a named, ordered list of your stops: the transfer first, the stop you actually
get off at last. Arm the route and dismissing one leg's alarm arms the next automatically —
no re-arming your final destination half asleep at an interchange. The time backstop
restarts per leg, and only the last leg's dismissal ends the journey.

**Routes run both ways.** Select a route and tap *Ride this route the other way* to arm it
in reverse — the same saved journey gets you to work and back home again. Direction is a
property of this trip, not an edit to the route, so the saved order is never touched.

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

## Headphones

If Bluetooth or wired headphones are connected when the alarm fires, the sound is sent to
them explicitly with `setPreferredDevice` — many phones otherwise keep alarms on the
speaker even with Bluetooth connected.

On headphones the behaviour deliberately changes:

- **The volume is capped** rather than forced to maximum (60% of the stream max by default,
  adjustable). A maxed-out alarm stream straight into your ears is a hearing risk.
- **Vibration is forced on**, regardless of the vibrate setting, to make up for the quieter
  sound.
- If the headset connects or drops **mid-alarm**, the sound is re-routed rather than left
  playing into a device that is no longer there.

Note on the cap: Android does not expose the OS-level "safe media volume" figure to apps,
so this is the app's own conservative cap approximating it, not a reading of the system
value. The app does not request `BLUETOOTH_CONNECT`, so it can't always read your
headphones' name — the status text says "your headphones" when it can't.

## Plain-language status

The top of the screen, and the expanded tracking notification, always read as full
sentences rather than numbers:

> The alarm is on and you are 3.2 km from Nesttun terminal. It will ring in your headphones
> and vibrate when you get within 500 metres. Because you are wearing headphones the volume
> is held at 60 percent, so it buzzes as well. After you get off there it will wake you
> again at Home.

That status message is set in **OpenDyslexic** with generous line spacing, since it is the
one thing you read while barely awake. Only that text — setting the whole interface in it
makes everything wider and harder to scan. Switchable in Settings.

## Why it's audible

- Plays on `STREAM_ALARM` with `USAGE_ALARM` audio attributes, which bypasses the ringer's
  silent/vibrate mode and (with DND access granted) Do Not Disturb.
- Optionally **forces the alarm volume to maximum** when it rings, then restores it.
- **Full-screen intent** notification on a MAX-importance channel — takes over a locked
  screen rather than showing a banner you'll sleep through.
- Wakes the display, shows over the lock screen, and **disables the Back button** so a
  half-asleep hand can't silently cancel it.
- Vibration waveform in parallel, always on when headphones are connected.
- **Synthesised fallback tone** if the device has no usable default alarm ringtone — a
  two-tone alarm generated through `AudioTrack`, so there is no audio asset that can be
  missing or unplayable.

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
| `MODIFY_AUDIO_SETTINGS` | Setting the alarm volume when it fires. |
| `INTERNET` | Stop search only. Not used while tracking. |

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

Or just download the latest build — CI publishes it on every push:

**https://github.com/emiara/gps-wakeup-alarm/releases/tag/latest-debug**

Install with `adb install -r app-debug.apk`, or copy the APK to the phone and open it.

## Radius guidance

At 50 km/h a bus covers 500 m in about 36 seconds — enough time to wake up,
gather your things and press the bell. Set it larger for fast routes, smaller for dense city
stops where 500 m might cover two stops.

## Third-party assets

[OpenDyslexic](https://opendyslexic.org/) by Abbie Gonzalez, SIL Open Font License 1.1 —
see `LICENSE-OpenDyslexic.txt`.
