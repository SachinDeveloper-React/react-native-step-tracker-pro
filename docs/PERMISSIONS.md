# Permissions

Everything the package can ask the user for, in the order to ask, with what to
do when they say no. Which of these apply depends on your
[usage mode](USAGE_MODES.md).

| # | Permission | Type | Mode A | Mode B | Mode C | Ask with |
|---|---|---|---|---|---|---|
| 1 | `ACTIVITY_RECOGNITION` | runtime dialog (Android 10+) | required | — | required | `requestPermissions()` |
| 2 | `POST_NOTIFICATIONS` | runtime dialog (Android 13+) | optional | — | optional | `requestPermissions()` |
| 3 | `FOREGROUND_SERVICE_HEALTH` | install-time (Android 14+) | automatic | — | automatic | nothing to do |
| 4 | Health Connect read set | Health Connect sheet | — | required | required for merge | `enableHealthConnect()` |
| 5 | Health Connect write set | Health Connect sheet | — | — | optional | `enableHealthConnect()` |
| 6 | `READ_HEALTH_DATA_IN_BACKGROUND` | same sheet | — | optional | recommended | config flag |
| 7 | `READ_HEALTH_DATA_HISTORY` | same sheet | — | optional | optional | config flag |
| 7a | `READ_ACTIVE_CALORIES_BURNED` | same sheet | — | optional | optional | config flag |
| 8 | Battery optimisation exemption | system dialog / settings | recommended on OEM phones | — | recommended on OEM phones | `requestBackgroundPermissions()` |
| 9 | OEM autostart | OEM settings screen | recommended on OEM phones | — | recommended on OEM phones | `requestBackgroundPermissions()` |

## What the library declares, and what you add

"You" here is the developer integrating the package, in the app's
`AndroidManifest.xml`. The people using your app never see any of this: they
get the same permission dialogs and Health Connect sheet as in 1.x, whichever
side of the split a permission is on.

### Who declares what

| Permission | Declared by | Why | Without it |
|---|---|---|---|
| `ACTIVITY_RECOGNITION` | the library | reading the step sensor | `startTracking()` rejects with `E_PERMISSION_DENIED` |
| `FOREGROUND_SERVICE` | the library | counting with the app closed | the service cannot enter the foreground; `error` event `E_SERVICE_START_FAILED` |
| `FOREGROUND_SERVICE_HEALTH` | the library | the service's `health` type, Android 14+ | `startTracking()` rejects with `E_PERMISSION_DENIED` on Android 14+ |
| `POST_NOTIFICATIONS` | the library | the ongoing notification, Android 13+ | the notification is invisible; counting continues |
| `RECEIVE_BOOT_COMPLETED` | the library | restarting after a reboot | tracking stays off until the app is opened |
| `WAKE_LOCK` | the library | sync jobs and the accelerometer fallback | background work can stop mid-run |
| Health Connect read set: `READ_STEPS`, `READ_DISTANCE`, `READ_TOTAL_CALORIES_BURNED` | **your app**, with `healthConnectReadEnabled` (default on) | reading watches and other apps | `E_HEALTH_CONNECT_NOT_DECLARED` |
| Health Connect write set: `WRITE_STEPS`, `WRITE_DISTANCE`, `WRITE_TOTAL_CALORIES_BURNED` | **your app**, with `healthConnectWriteEnabled` (default on) | mirroring this phone's count | `E_HEALTH_CONNECT_NOT_DECLARED` |
| `READ_HEALTH_DATA_IN_BACKGROUND` | **your app**, with `healthConnectBackgroundRead` | background reads of a watch | `E_HEALTH_CONNECT_NOT_DECLARED` |
| `READ_HEALTH_DATA_HISTORY` | **your app**, with `healthConnectHistoryRead` | reads past 30 days | `E_HEALTH_CONNECT_NOT_DECLARED` |
| `READ_ACTIVE_CALORIES_BURNED` | **your app**, with `healthConnectReadActiveCalories` | `StepSource.activeCalories` | `E_HEALTH_CONNECT_NOT_DECLARED` |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | **your app**, only for the direct dialog | one-tap battery exemption | the settings list opens instead; nothing fails |

### Why the split sits there

The six library permissions are what every app using this package needs:
without any one of them the package does not count, does not run in the
background, or does not come back after a reboot. There is nothing to
decide, so asking every developer to copy them would only add a way to break
the install.

Everything else depends on what the app does, and that is what Play judges:

- **Every declared permission is one Play asks you to justify.** A Health
  Connect permission also brings the Health apps declaration form, and an
  unjustified one is a rejection. Up to 1.x every app inherited all of them -
  an app that never touched Health Connect had to notice and remove them, or
  explain them. Now a sensor-only app declares no health permission at all.
- **A mistake shows up immediately, not at review.** A missing entry fails the
  request with `E_HEALTH_CONNECT_NOT_DECLARED`, naming what to add. An extra
  entry left over in 1.x said nothing until Play review found it.
- **The work is small.** A handful of lines, copied from below; the Expo
  config plugin writes them for you.
- **It is the usual pattern.** Other Health Connect libraries for React Native
  also leave the declarations to the app.

The alternative - keep declaring everything and have apps remove what they do
not use with `tools:node="remove"` - is less setup on day one, but it puts
the cost on every app that does not need a permission, and that cost arrives
as a Play rejection rather than an error in development.

### The entries to add

Add the Health Connect entries your config uses to
`android/app/src/main/AndroidManifest.xml`:

```xml
<!-- healthConnectReadEnabled (default true): reading other apps and watches -->
<uses-permission android:name="android.permission.health.READ_STEPS" />
<uses-permission android:name="android.permission.health.READ_DISTANCE" />
<uses-permission android:name="android.permission.health.READ_TOTAL_CALORIES_BURNED" />

<!-- healthConnectWriteEnabled (default true): mirroring this phone's count -->
<uses-permission android:name="android.permission.health.WRITE_STEPS" />
<uses-permission android:name="android.permission.health.WRITE_DISTANCE" />
<uses-permission android:name="android.permission.health.WRITE_TOTAL_CALORIES_BURNED" />

<!-- Optional, each only with its config flag -->
<!-- healthConnectBackgroundRead -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />
<!-- healthConnectHistoryRead -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_HISTORY" />
<!-- healthConnectReadActiveCalories -->
<uses-permission android:name="android.permission.health.READ_ACTIVE_CALORIES_BURNED" />
```

And, only if you call `requestDisableBatteryOptimization()` and have an
eligible Play use case for the direct dialog:

```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

### When an entry is missing

- `requestHealthConnectPermissions()` and `enableHealthConnect()` reject with
  `E_HEALTH_CONNECT_NOT_DECLARED`, naming the missing entries. Health Connect
  itself would silently leave an undeclared permission off its sheet, which
  looks exactly like the user refusing.
  `getHealthConnectStatus().undeclaredPermissions` lists them up front.
- `requestDisableBatteryOptimization()` opens the settings list instead of the
  direct dialog, and `getBackgroundRestrictionStatus().directPromptAvailable`
  is `false`.

**Expo.** The config plugin writes these for you; see
[INSTALLATION.md](INSTALLATION.md#expo).

### Checking your setup

In the app, before any Health Connect request:

```ts
const status = await StepTracker.getHealthConnectStatus();
if (status.undeclaredPermissions.length > 0) {
  console.warn('Add to AndroidManifest.xml:', status.undeclaredPermissions);
}
const background = await StepTracker.getBackgroundRestrictionStatus();
// background.directPromptAvailable: whether the one-tap battery dialog can show
```

From the build, the merged manifest lists every permission the app ends up
with - the ones you declared plus the library's six:

```sh
cd android && ./gradlew :app:processDebugMainManifest
grep 'uses-permission' app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
```

What Play sees is that list. Anything in it your app does not use is worth
removing before you fill in the forms.

**Upgrading from 1.x?** Your app relied on the library for these entries.
[MIGRATING.md](MIGRATING.md) walks through adding them, per mode.

---

## The order to ask in

Play's guidance, and what actually converts:

1. **Do not ask on first launch.** Show the step screen, explain what tracking
   does, and ask when the user taps "Start".
2. **`requestPermissions()`** on that tap — one call shows the
   `ACTIVITY_RECOGNITION` dialog and, on Android 13+, the notification dialog.
3. **`startTracking()`** as soon as `allGranted` is true. Counting is now on.
4. **Health Connect** later, from a "Connect a watch / other apps" step or a
   settings screen — never as a gate to step 3.
5. **Background restrictions** after tracking has been running, ideally the
   first time `getTrackingHealth()` reports `recoveryCount > 0` or on the
   first foreground after install on an `aggressiveOem` phone.

```ts
// Step 2–3
const perms = await StepTracker.requestPermissions();
if (!perms.allGranted) {
  // Explain, then offer app settings — a second system dialog will not
  // appear once the user has picked "Don't ask again".
  return showRationale({ onSettings: () => StepTracker.openAppSettings() });
}
await StepTracker.startTracking();

// Step 4, from a settings screen
const hc = await StepTracker.enableHealthConnect();

// Step 5, on phones that need it
const restrictions = await StepTracker.getBackgroundRestrictionStatus();
if (restrictions.aggressiveOem || restrictions.batteryOptimizationEnabled) {
  const shown = await StepTracker.requestBackgroundPermissions();
  // 'battery' | 'autostart' | 'none' — explain the screen that just opened
}
```

---

Two install-time permissions in the library manifest need no request and no
form: `WAKE_LOCK` (held by WorkManager during a sync, and by the service while
the accelerometer fallback is sampling a non-wake-up sensor) and
`RECEIVE_BOOT_COMPLETED`.

## 1. `ACTIVITY_RECOGNITION`

Runtime permission from Android 10. Without it the sensor cannot be read and
`startTracking()` rejects with `E_PERMISSION_DENIED`. On Android 14+ it is also
the runtime prerequisite for a `health`-type foreground service: the OS
refuses `startForeground` without it.

Declared by the library manifest (plus the legacy
`com.google.android.gms.permission.ACTIVITY_RECOGNITION` for API ≤ 28).

**Denied:** `checkPermissions().activityRecognition` is false. Show why it is
needed and a button to `openAppSettings()`. Do not loop on `requestPermissions()`.

**Revoked while tracking:** Android kills the process. The next start sees the
missing grant, stops the service, and emits `error` with
`E_PERMISSION_DENIED`. The boot receiver also checks it and stays quiet.

Play data safety: *Health and fitness → Fitness info*, collected, "app
functionality".

The same grant covers Google's Activity Recognition API, which the
integrity checks use when `fraudDetection.activityRecognition` is on and your
app ships `play-services-location`. The integrity checks need no other
permission: charging state and clock changes come from system broadcasts,
and nothing reads location.

## 2. `POST_NOTIFICATIONS`

Android 13+. Requested in the same `requestPermissions()` call. **Denying it
does not stop counting** — the foreground service still runs, the notification
is just hidden. `allGranted` ignores it on purpose. Do not block onboarding on
it.

The channel is created at `IMPORTANCE_LOW` and never makes a sound. If the
user turns the channel off in system settings, it stays off; the only route is
app notification settings.

## 3. `FOREGROUND_SERVICE_HEALTH`

Android 14+. A normal (install-time) permission, declared by the library
manifest, granted automatically. Nothing to request. What it *does* need is a
**Play Console declaration** with a video — see
[PLAY_STORE_COMPLIANCE.md](PLAY_STORE_COMPLIANCE.md#1-foreground-service-type).

## 4–7. Health Connect

Health Connect permissions are not Android runtime permissions. They are
granted through Health Connect's own sheet, launched via an
`ActivityResultContract` from a transparent activity the library provides.

### What is asked for

The set follows config. Every permission on the sheet is one the user can
refuse the whole sheet over, so the package never asks for more than the mode
needs:

| Config | Adds to the sheet |
|---|---|
| `healthConnectReadEnabled: true` (default) | `READ_STEPS`, `READ_DISTANCE`, `READ_TOTAL_CALORIES_BURNED` |
| `healthConnectWriteEnabled: true` (default) | `WRITE_STEPS`, `WRITE_DISTANCE`, `WRITE_TOTAL_CALORIES_BURNED` |
| `healthConnectBackgroundRead: true` | `READ_HEALTH_DATA_IN_BACKGROUND` |
| `healthConnectHistoryRead: true` | `READ_HEALTH_DATA_HISTORY` |
| `healthConnectReadActiveCalories: true` | `READ_ACTIVE_CALORIES_BURNED` |

Each one has to be declared in your manifest as well - see
[What the library declares](#what-the-library-declares-and-what-you-add).

`getHealthConnectStatus().granted` is true when everything *your config* needs
is granted; `canRead` / `canWrite` report the raw capability regardless.

### The flow

```ts
const status = await StepTracker.enableHealthConnect();
```

`enableHealthConnect()` runs the whole ladder and returns where it got to:

| `status` | Meaning | What `enableHealthConnect()` did |
|---|---|---|
| `availability: 'not_supported'` | device cannot run Health Connect | nothing; hide the feature |
| `installable: true` | provider missing or too old (Android ≤ 13) | opened the Play Store listing |
| `shouldOpenSettings: true` | sheet refused twice; it no longer appears | opened Health Connect settings |
| `granted: false` | sheet shown, user declined | nothing more this call |
| `granted: true` | done | — |

Re-run it on the next foreground — `healthConnectStatusChanged` fires when the
status moved while the app was away, which is how installing the provider or
granting from Health Connect's own settings gets noticed.

### The two-refusal rule

Health Connect stops showing its sheet after the user refuses it **twice**. A
third `requestHealthConnectPermissions()` resolves normally with an unchanged
status and nothing on screen — indistinguishable from an instant denial unless
you count. `denialCount` and `shouldOpenSettings` are that count; past it,
only `openHealthConnectSettings()` can help. `revokeHealthConnectPermissions()`
resets the counter.

### Background reads (6)

Android 14+ refuses Health Connect reads from an app with no visible activity
unless it holds `READ_HEALTH_DATA_IN_BACKGROUND`. The foreground service
counts as background here. Without it:

- the service's throttled refresh of today's sources returns empty, so the
  notification and `stepsChanged` only pick up a watch sync the next time the
  app is opened;
- the sync worker cannot check whether a wearable owns a day before writing it.

Set `healthConnectBackgroundRead: true` in Mode C. Play scrutinises this one:
say in the declaration that it keeps a live step count in sync with a paired
watch while the app is closed.

### History (7)

Reads older than 30 days need `READ_HEALTH_DATA_HISTORY`. Only yearly stats
resolved against Health Connect need it; the phone's own history is local and
unaffected.

### The rationale screen

Health Connect requires an activity answering
`androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (Android ≤ 13) and
`android.intent.action.VIEW_PERMISSION_USAGE` with the
`HEALTH_PERMISSIONS` category (Android 14+), and Play rejects health apps
without one. **The library ships both.** They open `privacyPolicyUrl` from
config; set it, or replace the activity with your own — see
[INSTALLATION.md](INSTALLATION.md#health-connect-rationale-screen).

## 8. Battery optimisation exemption

`isBatteryOptimizationEnabled()` true means Doze still applies. On stock
Android a foreground service survives Doze and this is not needed. On OEM
skins it is the single most effective step, and on **Android 12+ it is also
what allows the watchdog to restart a killed service from the background** —
the OS refuses background starts of a foreground service otherwise.

Two ways to ask:

| Method | Needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | UX |
|---|---|---|
| `requestDisableBatteryOptimization()` | yes, declared by your app from 2.0 | one system dialog, "Allow"; the settings list when not declared |
| `openBatteryOptimizationSettings()` | no | settings list, user finds the app |

The permission is allowed by Play when the app's core function is broken by
Doze; a step tracker's is. Ask in context, with the reason on screen. Details
and the exact wording in
[PLAY_STORE_COMPLIANCE.md](PLAY_STORE_COMPLIANCE.md#3-battery-optimisation-exemption).

## 9. OEM autostart / background launch

Xiaomi, Oppo, Vivo, Realme, Huawei, Tecno/Infinix/itel, Samsung and others
have a second, manufacturer-specific switch with no API.
`openManufacturerAutoStartSettings()` deep-links to it and
`getBackgroundRestrictionStatus().autoStartSettingsAvailable` says whether the
link will land on the OEM screen or fall back to app info.

`requestBackgroundPermissions()` does 8 and 9 in order, one screen per call.
Per-manufacturer instructions to show the user are in
[OEM_BATTERY.md](OEM_BATTERY.md).

---

## Denial matrix

| The user refused… | Counting | What still works | What to show |
|---|---|---|---|
| `ACTIVITY_RECOGNITION` | **off** | Health Connect reads (Mode B behaviour) | rationale + `openAppSettings()` |
| `POST_NOTIFICATIONS` | on | everything; notification hidden | nothing, or a one-line note |
| Health Connect sheet | on (phone only) | everything except watch / other-app data | "Connect later" in settings |
| Battery exemption | on | everything, until the OEM kills the service | `getTrackingHealth().recoveryCount` → prompt again |
| OEM autostart | on | as above | per-OEM instructions |

Nothing here is fatal except the first row.
