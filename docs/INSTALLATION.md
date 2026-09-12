# Installation

## Requirements

| | |
|---|---|
| React Native | ≥ 0.77 (old and new architecture both supported) |
| `minSdkVersion` | 26 — `java.time` and the Health Connect client both need it |
| `compileSdkVersion` / `targetSdkVersion` | 35 |
| Java | 17 |
| Kotlin | 2.0.21 by default (follows the host app's `kotlinVersion`); see below |
| Health Connect client | `1.1.0-beta01` by default; `1.1.0-alpha10` is the minimum |

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

Other overridable properties: `healthConnectVersion` (default `1.1.0-beta01`),
`workVersion` (default `2.9.1`).

`healthConnectVersion` is held at `1.1.0-beta01` on purpose: `1.1.0` stable
requires `compileSdk 36` and Android Gradle Plugin 8.9.1+, which React Native
did not ship until well after 0.76, so depending on it would fail
`checkDebugAarMetadata` in every app inside the `react-native >= 0.77` range
this package claims to support. `1.1.0-beta01` is the newest release that still
builds against `compileSdk 35` and exposes the same `Metadata` API. If your app
is already on `compileSdk 36` and AGP 8.9.1+, opt up with:

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

## 4. Manifest entries

The library manifest already merges in the service, the boot receiver, the
sensor permissions, the Health Connect permissions, the rationale activity and
the `<queries>` for companion apps and OEM battery managers. Nothing is
required in your app for the default (both sensor and Health Connect) setup.

What you *remove* depends on the mode — the exact blocks for native-only and
Health-Connect-only are in [USAGE_MODES.md](USAGE_MODES.md). The rest of this
section covers the entries you may want to customise:

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

Two permissions are declared but never requested unless you opt in, because
Health Connect shows one sheet for the whole set and asking for something you do
not need risks the grants you do:

| Permission | Config flag | Needed for |
|---|---|---|
| `READ_HEALTH_DATA_IN_BACKGROUND` | `healthConnectBackgroundRead` | the background sync worker seeing a watch's steps while the app is closed — without it every background read returns empty |
| `READ_HEALTH_DATA_HISTORY` | `healthConnectHistoryRead` | reading further back than 30 days, which yearly stats need |

Remove the ones you will not use, the same way as any other:

```xml
<uses-permission
    android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    tools:node="remove" />
```

### Trimming permissions you do not use

The library declares read and write for steps, distance and calories. Remove
any pair you will not use — Play asks you to justify every declared health
permission:

```xml
<uses-permission
    android:name="android.permission.health.WRITE_TOTAL_CALORIES_BURNED"
    tools:node="remove" />
```

Same for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` if you only ever call
`openBatteryOptimizationSettings()`:

```xml
<uses-permission
    android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    tools:node="remove" />
```

## 5. Custom notification icon (recommended)

The bundled icon is a generic footprint. Drop a white-on-transparent
`ic_stat_steps.xml` into `android/app/src/main/res/drawable/` and pass the name:

```ts
await StepTracker.initialize({ notificationIcon: 'ic_stat_steps' });
```

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
  healthConnectBackgroundRead: true,  // so background syncs see the watch too
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
