# Keeping tracking alive on OEM phones

Stock Android keeps a `health` foreground service alive with the screen off,
the app swiped away and the device in Doze, and restarts it after a kill
(`START_STICKY`). Most phones sold in India, South-East Asia, Africa and Latin
America do not run stock Android. MIUI/HyperOS, ColorOS, realme UI,
FuntouchOS/OriginOS, EMUI/MagicOS, HiOS/XOS/itel OS and One UI all ship a
task killer that ignores the foreground service, ignores `START_STICKY`, and
on some builds kills the process the moment the user swipes the app away.

This page is what the package does about it, and what you have to ask the
user to do — because for the last part there is no API.

## What survives a kill

The step count. `TYPE_STEP_COUNTER` runs in the SoC's sensor hub, not in your
process, and keeps counting whether or not anything is listening. When the
service comes back — seconds or hours later — the first sample carries every
step taken in between, and the engine reconciles them:

- inside one day: all of them land on today;
- across midnight: split by time between the days (`gapRecovery: 'split'`,
  the default — see [ARCHITECTURE.md](ARCHITECTURE.md#gap-recovery)).

What is lost while dead: the live notification, `stepsChanged` events, the
half-hourly Health Connect mirror, and the midnight rollover (deferred until
the restart). None of it is data.

On a phone with only `TYPE_STEP_DETECTOR`, or on one with no step sensor at
all where the accelerometer fallback is counting, steps taken while the
process is dead **are** lost — there is no hardware counter to reconcile
from. `getDeviceCapabilities().bestSensor` says which you have. Those are the
phones where the watchdog and the autostart step below matter most.

## What the package does on its own

Four recovery paths, in the order they are likely to fire:

| Path | When | Needs |
|---|---|---|
| `START_STICKY` restart | the OS honours it | nothing (stock Android; OEMs often skip it) |
| **Foreground restart** | the user opens the app (`onHostResume`), or calls `initialize()` / `getTrackingHealth()` | nothing — a foregrounded app may always start its service |
| **Watchdog** | every 15 minutes, when the user has tracking on and no service instance exists | Android 12+: the battery-optimisation exemption, or the start is refused |
| Boot receiver | reboot, app update | `autoStartOnBoot` (default true) |

The foreground restart is the one that always works. It means an OEM kill
costs at most "the notification was gone until the user next opened the app".
The watchdog is what closes even that gap, and the battery exemption is what
lets it.

`getTrackingHealth()` reports all of this:

```ts
const health = await StepTracker.getTrackingHealth();
// {
//   serviceAlive: false,        // no instance right now
//   shouldBeRunning: true,      // the user never stopped it
//   looksDead: true,            // → it was killed; calling this from the
//                               //   foreground has already restarted it
//   heartbeatAgeMs: 5400000,    // 90 minutes since the last beat
//   recoveryCount: 4,           // brought back four times since the user
//                               //   pressed start — an OEM is killing it
//   lastRecoveryReason: 'watchdog',
//   aggressiveOem: true,
//   batteryOptimizationEnabled: true,
//   manufacturer: 'Xiaomi',
// }
```

`recoveryCount` is the number to build UX on: once it climbs, the phone is
killing the service, and only the user can stop that.

## What you have to ask the user for

```ts
const status = await StepTracker.getBackgroundRestrictionStatus();
// { aggressiveOem, batteryOptimizationEnabled, autoStartSettingsAvailable,
//   backgroundStartNeedsExemption, manufacturer, brand }

if (status.aggressiveOem || status.batteryOptimizationEnabled) {
  const shown = await StepTracker.requestBackgroundPermissions();
  // 'battery'   → the Doze exemption dialog (or list) opened; say "tap Allow"
  // 'autostart' → the OEM's screen opened; show the per-OEM instruction below
  // 'none'      → nothing needed
}
```

One screen per call. Call again on the next foreground until
`batteryOptimizationEnabled` is false and `recoveryCount` stops climbing.
Pass `{ directPrompt: false }` to use the settings list instead of the direct
dialog if you removed `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` from the manifest.

Ask **after** tracking is on and has been killed at least once, or on the
first foreground on an `aggressiveOem` phone. Asking on first launch, before
the user has seen a single step, is where these prompts get refused.

## Per-manufacturer instructions

Copy these into your UI, matched on `getBackgroundRestrictionStatus().manufacturer`.
`openManufacturerAutoStartSettings()` opens the first screen in each list
that exists on the device; the rest the user reaches from Settings.

### Xiaomi, Redmi, POCO (MIUI / HyperOS)

Three separate switches, all needed:

1. **Autostart** — Security app → Manage apps → *your app* → Autostart **on**.
   (`openManufacturerAutoStartSettings()` lands here.)
2. **Battery saver** — Settings → Apps → *your app* → Battery saver →
   **No restrictions**.
3. **Lock in recents** — open Recents, long-press the app card → tap the lock
   icon. Without this MIUI kills the process on swipe-away.

MIUI also has "Battery → App battery saver → *your app* → No restrictions" on
older versions, same switch as 2.

### OPPO, realme, OnePlus (ColorOS / realme UI / OxygenOS 12+)

1. **Auto-launch** — Settings → Apps → *your app* → Auto-launch **on**.
   (Deep link target.)
2. **Battery** — Settings → Battery → *your app* → **Allow background
   activity** and **Allow auto-launch**; set power mode to *Unrestricted*
   where the option exists.
3. On older ColorOS: Settings → Battery → Power saving → *your app* → turn
   off **Freeze when in background** and **Abnormal apps optimisation**.
4. Recents → three dots on the app card → **Lock**.

### vivo, iQOO (FuntouchOS / OriginOS)

1. **Background power** — Settings → Battery → Background power consumption
   management → *your app* → **Allow high background power consumption**.
2. **Autostart** — i Manager → App manager → Autostart manager → *your app*
   **on**. (Deep link target.)
3. Recents → pull the app card down to lock it.

### Samsung (One UI)

1. Settings → Apps → *your app* → Battery → **Unrestricted**.
   (Deep link opens the battery screen on most builds.)
2. Settings → Battery → Background usage limits → **Never sleeping apps** →
   add *your app*. Also make sure it is not in *Sleeping* or *Deep sleeping*.
3. Settings → Battery → turn off **Adaptive battery** if the service keeps
   dying after 1–2.

Samsung is less aggressive than the others; steps 1–2 are usually enough.

### Huawei, Honor (EMUI / MagicOS)

1. Settings → Apps → *your app* → Battery → **App launch** → turn *Manage
   automatically* **off**, then enable **Auto-launch**, **Secondary launch**
   and **Run in background**. (Deep link target.)
2. Settings → Battery → App launch — same screen, list form.
3. Recents → lock the app card.

Huawei phones without Google services cannot install Health Connect; Mode A
applies there.

### Tecno, Infinix, itel (HiOS / XOS / itel OS)

1. Phone Master (or *Phone Manager*) → **Auto-start management** → *your app*
   **on**. (Deep link target on builds that expose it.)
2. Settings → Battery → **Power saving** → App power management → *your app*
   → allow background.
3. Phone Master → **Background app management** → un-tick *your app* from
   the list of apps to kill.

Class names on these builds change with every firmware. The package tries
the known ones first and then scans Phone Master's exported activities for
one named like an autostart or battery screen, so an uncatalogued build still
lands somewhere useful; `getBackgroundRestrictionStatus().autoStartTarget`
says where. When it is null and the deep link falls back to app info, the
paths above are what to show — and the value is worth a bug report.

### Nokia / HMD (older Android One builds)

Some 2018–2020 models ship "Evenwell" power saving. Settings → Battery →
Power saver → *your app* → exclude. The deep link targets it.

### ASUS (ZenUI)

Mobile Manager → **Auto-start manager** → *your app* **on**; Mobile Manager →
Boost → **PowerMaster** → Auto-start manager, and turn off *Clean up in
suspend*.

### Motorola, Nokia (recent), Google Pixel, Android One

Stock or near-stock. The battery-optimisation exemption is sufficient, and
usually unnecessary. Nothing else to show.

## Notification hygiene

An OEM killer is more likely to leave an app alone when its foreground
service notification is visible and ongoing. The package's notification is
ongoing, silent and `IMPORTANCE_LOW`; do not set `notificationActions: false`
*and* hide the channel — a foreground service with a hidden notification is
exactly what several skins treat as abuse.

## Testing it

There is no emulator for MIUI. On the device:

```sh
# kill it the way the OEM does
adb shell am force-stop <applicationId>          # does NOT trigger START_STICKY
adb shell am kill <applicationId>                # does, on stock

# watch the recovery
adb logcat -s StepTrackerService StepTrackerWatchdog StepTrackerBoot
adb shell run-as <applicationId> cat shared_prefs/StepTrackerProState.xml \
  | grep -E 'heartbeat|recovery|steps_today'
```

Then walk with the app dead, open it, and confirm the steps appear and
`recoveryCount` moved. The full matrix is in [TESTING.md](TESTING.md).
