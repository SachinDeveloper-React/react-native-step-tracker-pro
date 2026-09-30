# Installation

## Requirements

| | |
|---|---|
| React Native | ≥ 0.77 (old and new architecture both supported; the new one only from 0.82, where it is the only one) |
| `minSdkVersion` | 26 — `java.time` and the Health Connect client both need it. React Native's template starts at 24: raise it |
| `compileSdkVersion` / `targetSdkVersion` | 35 or higher |
| Android Gradle Plugin | 8.x, or 9.x with built-in Kotlin on or off |
| Java | 17 |
| Kotlin | follows the host app's `kotlinVersion`; 2.0.21 when none is set; see below |
| Health Connect client | `1.1.0` on compileSdk 36+, `1.1.0-beta01` below; `1.1.0-alpha10` is the minimum |

The combinations CI builds on every push are in the
[compatibility table](../README.md#compatibility).

Before wiring anything up, pick a [usage mode](USAGE_MODES.md) — native
sensor only, Health Connect only, or both. It decides which manifest entries
below you keep.

## 1. Install

```sh
npm install react-native-step-tracker-pro
# or
yarn add react-native-step-tracker-pro
```

Autolinking picks the package up. No manual `MainApplication` edit is needed.

## 2. Set the SDK levels

`android/build.gradle` in your app:

```gradle
buildscript {
    ext {
        minSdkVersion = 26
        compileSdkVersion = 35
        targetSdkVersion = 35
    }
}
```

## 3. Kotlin and Room versions

The library follows the Kotlin your app already uses — React Native sets
`rootProject.ext.kotlinVersion` and the library reads it — and defaults to
2.0.21 with Room 2.7.2 when nothing is set. Override either in the app's root
`build.gradle` only if you need to:

```gradle
buildscript {
    ext {
        kotlinVersion = "2.0.21"
        roomVersion = "2.7.2"
    }
}
```

Other overridable properties: `healthConnectVersion` and `workVersion`
(default `2.9.1`).

The Health Connect client follows your `compileSdkVersion`: `1.1.0` stable when
it is 36 or higher, `1.1.0-beta01` below. `1.1.0` requires `compileSdk 36` and
AGP 8.9.1+, which React Native did not ship until 0.81, so depending on it
unconditionally would fail `checkDebugAarMetadata` for every app on 0.77-0.80.
`1.1.0-beta01` is the newest release that builds against `compileSdk 35` with
the same `Metadata` API. Pin either yourself with:

```groovy
ext {
    healthConnectVersion = "1.1.0"
}
```


Room's annotation processing runs through **kapt**, not KSP. A KSP plugin
version has to match the Kotlin plugin version exactly, and a library cannot
know which Kotlin its host app will bring — pinning one breaks every app on a
different Kotlin, and the KSP suffix cannot be derived from the Kotlin version.
kapt ships inside the Kotlin Gradle plugin, so it always matches. There is no
`kspVersion` to set.

The React Native floor is ≥ 0.77 because Room 2.7 is the first release whose
bundled `kotlinx-metadata` can read Kotlin 2.x metadata, and Room 2.7 itself
needs Kotlin 2.0+. On react-native 0.74–0.76 (Kotlin 1.9.24) the package still
works if you pin the older pair:

```groovy
ext {
    kotlinVersion = "1.9.24"
    roomVersion = "2.6.1"
}
```

### Android Gradle Plugin 9

Nothing to configure. The library reads which AGP and Kotlin mode your app
runs: with `android.builtInKotlin=false` - React Native 0.87's template
default - it applies the Kotlin Android plugin and its kapt as always; with
built-in Kotlin on (AGP 9's own default) it applies AGP's
`com.android.legacy-kapt` instead, and adds the `gradle-kotlin` artifact that
provides it at your AGP's version. It also detects React Native 0.82+ as
new-architecture-only, whatever `newArchEnabled` says.

## 4. Manifest entries

The library manifest merges in the service, the boot receiver, the rationale
activity, the `<queries>` for companion apps and OEM battery managers, and
the permissions sensor-only counting needs. **From 2.0 it declares no Health
Connect permission and not `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**: add the
ones your config uses. The snippets are in
[PERMISSIONS.md](PERMISSIONS.md#what-the-library-declares-and-what-you-add);
per mode in [USAGE_MODES.md](USAGE_MODES.md). A config that asks for one you
did not declare rejects with `E_HEALTH_CONNECT_NOT_DECLARED`. Upgrading an app
that relied on 1.x declaring them: [MIGRATING.md](MIGRATING.md).

The rest of this section covers the entries you may want to customise:

### Health Connect rationale screen

Play rejects apps that request Health Connect permissions without a screen
explaining what the data is used for, and Health Connect links to it from its
own permission sheet.

**The library ships that activity and both of its intent filters** — the
`androidx.health` action for Android 13 and below, and the
`VIEW_PERMISSION_USAGE` alias for Android 14+. All you have to do is give it a
URL to open:

```ts
await StepTracker.initialize({
  privacyPolicyUrl: 'https://example.com/privacy',
});
```

Without a URL the screen still opens and tells the user (and you, in QA) that
none is configured, so a missing policy fails loudly rather than silently.

To show your own in-app screen instead, declare it with both filters and remove
the library's, so exactly one activity answers each:

```xml
<activity android:name=".PrivacyPolicyActivity" android:exported="true">
    <intent-filter>
        <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
    </intent-filter>
</activity>

<activity-alias
    android:name="ViewPermissionUsageActivity"
    android:exported="true"
    android:permission="android.permission.START_VIEW_PERMISSION_USAGE"
    android:targetActivity=".PrivacyPolicyActivity">
    <intent-filter>
        <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
        <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
    </intent-filter>
</activity-alias>

<activity
    android:name="com.steptrackerpro.health.HealthPrivacyPolicyActivity"
    tools:node="remove" />
<activity-alias
    android:name="com.steptrackerpro.health.ViewPermissionUsageActivity"
    tools:node="remove" />
```

### Optional Health Connect permissions

These are requested only when you opt in, because Health Connect shows one
sheet for the whole set and asking for something you do not need risks the
grants you do. Declare each one you turn on:

| Permission | Config flag | Needed for |
|---|---|---|
| `READ_HEALTH_DATA_IN_BACKGROUND` | `healthConnectBackgroundRead` | reading a watch's steps with the app closed and tracking off — while tracking is on, the tracking service is a foreground service, which Health Connect lets read. Without it those reads are not made, and report Health Connect as not consulted |
| `READ_HEALTH_DATA_HISTORY` | `healthConnectHistoryRead` | reading further back than 30 days, which yearly stats need |
| `READ_ACTIVE_CALORIES_BURNED` | `healthConnectReadActiveCalories` | `StepSource.activeCalories` |

### Expo

Expo apps need a development build (`npx expo prebuild`); Expo Go has no
custom native code. The package ships a config plugin that declares the
opt-in permissions and raises `android.minSdkVersion` to 26:

```json
{
  "expo": {
    "plugins": [
      ["react-native-step-tracker-pro", {
        "healthConnect": { "read": true, "write": true, "backgroundRead": true },
        "batteryOptimizationPrompt": false
      }]
    ]
  }
}
```

`healthConnect` takes `true` for the default read and write sets, or an object
with `read`, `write` (both default `true`), `readTypes`, `backgroundRead`,
`historyRead` and `activeCalories`. `readTypes` matches the
`healthConnectReadTypes` config option - `["steps"]` declares `READ_STEPS`
alone - so pass the same list to both. Leave `healthConnect` out for
sensor-only counting.
`batteryOptimizationPrompt: true` adds `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
`healthConnect: false` says the app never uses Health Connect and removes the
privacy-policy activity and alias the library merges in for it (2.3). They
are left alone otherwise: Android 14 refuses Health Connect permission
requests from an app without them. `notificationIcon: "ic_stat_steps"` writes
the keep rule [the custom icon needs](#5-custom-notification-icon-recommended).

## 5. Custom notification icon (recommended)

The bundled icon is a generic footprint. Drop a white-on-transparent
`ic_stat_steps.xml` into `android/app/src/main/res/drawable/` and pass the name:

```ts
await StepTracker.initialize({ notificationIcon: 'ic_stat_steps' });
```

The library finds the icon by name at runtime, which resource shrinking cannot
see. With `shrinkResources true` in a release build, keep it, or it is removed
and the default icon shows. Add `android/app/src/main/res/raw/keep.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@drawable/ic_stat_steps" />
```

A name that is not found logs a warning under the `StepTrackerPro` tag once,
and the default icon is used. With Expo, pass the name to the config plugin
as `notificationIcon` and it writes the keep rule for you.

## 6. Build

```sh
cd android && ./gradlew clean && cd ..
npx react-native run-android
```

The step counter does not exist on most emulators. Use a physical device, or
inject values on an emulator with:

```sh
adb shell run-as <applicationId> \
  sed -i 's/steps_today" value="[0-9]*"/steps_today" value="5000"/' \
  shared_prefs/StepTrackerProState.xml
```

## 7. Minimum working setup

```ts
import { useEffect } from 'react';
import StepTracker from 'react-native-step-tracker-pro';

useEffect(() => {
  (async () => {
    await StepTracker.initialize({ height: 175, weight: 75, dailyGoal: 10000 });
    const perms = await StepTracker.requestPermissions();
    if (perms.allGranted) await StepTracker.startTracking();
  })();

  const sub = StepTracker.addListener('stepsChanged', ({ steps }) => {
    console.log(steps);
  });
  return () => sub.remove();
}, []);
```

## 8. Low-end and OEM phones

Xiaomi, Oppo, Vivo, Realme, Tecno and friends kill foreground services. The
package restarts itself (on next app open, from a 15-minute watchdog, and on
boot) and recovers every step the hardware counted in between, but only the
user can stop the killing. Add one onboarding step:

```ts
const status = await StepTracker.getBackgroundRestrictionStatus();
if (status.aggressiveOem || status.batteryOptimizationEnabled) {
  await StepTracker.requestBackgroundPermissions();
}
```

and show `getTrackingHealth().recoveryCount` some attention. Everything
manufacturer-specific is in [OEM_BATTERY.md](OEM_BATTERY.md).

## 9. Supporting a watch

If your users wear a watch, the phone's sensor is not the whole story — a phone
left on a desk counts nothing while a worn watch counts everything. Health
Connect is how the watch's number reaches you.

```ts
await StepTracker.initialize({
  privacyPolicyUrl: 'https://example.com/privacy',
  stepSource: 'auto',                 // the default: take the higher of the two
  // With tracking on, the tracking service lets Health Connect be read with
  // the app closed. healthConnectBackgroundRead: true covers the rest -
  // tracking off, or the service killed - and is one more grant Play reviews.
});

// One button: installs the provider, or asks, or opens settings — whichever
// step the user is actually on.
const status = await StepTracker.enableHealthConnect();

if (status.canRead) {
  const { sources, hasWearable } = await StepTracker.getStepSources(
    '2026-09-01',
    '2026-09-09'
  );
  // Let the user pick, or leave it to the policy.
  if (hasWearable) console.log(sources.filter((s) => s.isWearable));
}
```

The numbers coming back from `getTodaySteps()` and the stats calls are already
resolved, and carry a `stepSource` saying which app they came from. Nothing here
sums the phone and the watch — see
[Step sources](./API.md#step-sources-watches-and-other-apps).

The `useHealthConnect()` hook wraps this whole flow if you would rather not
drive it by hand.

## ProGuard / R8

`consumer-rules.pro` ships with the library and is applied automatically. No
app-side rules are needed.
