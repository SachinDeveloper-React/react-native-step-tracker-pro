# Installation

## Requirements

| | |
|---|---|
| React Native | ≥ 0.74 (old and new architecture both supported) |
| `minSdkVersion` | 26 — `java.time` and the Health Connect client both need it |
| `compileSdkVersion` / `targetSdkVersion` | 35 |
| Java | 17 |
| Kotlin | 1.9.24 by default; see below for 2.x |

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

## 3. Kotlin 2.x (only if your app is already on it)

The library ships Room via KSP and defaults to Kotlin 1.9.24. KSP versions are
pinned to an exact Kotlin version, so if your app uses Kotlin 2.x you must say
so, or the build fails with a KSP/Kotlin mismatch.

```gradle
buildscript {
    ext {
        kotlinVersion = "2.0.21"
        kspVersion = "2.0.21-1.0.25"   // must match kotlinVersion exactly
        roomVersion = "2.7.1"
    }
}
```

Other overridable properties: `healthConnectVersion` (default `1.1.0-beta01`),
`workVersion` (default `2.9.1`).

`healthConnectVersion` is held at `1.1.0-beta01` on purpose: `1.1.0` stable
requires `compileSdk 36` and Android Gradle Plugin 8.9.1+, which React Native
did not ship until well after 0.76, so depending on it would fail
`checkDebugAarMetadata` in every app inside the `react-native >= 0.74` range
this package claims to support. `1.1.0-beta01` is the newest release that still
builds against `compileSdk 35` and exposes the same `Metadata` API. If your app
is already on `compileSdk 36` and AGP 8.9.1+, opt up with:

```groovy
ext {
    healthConnectVersion = "1.1.0"
}
```


If Gradle warns that the Kotlin plugin is loaded twice with different versions,
that is this mismatch — set `kotlinVersion` and `kspVersion` as above.

## 4. Manifest entries your app must add

The library manifest already merges in the service, the boot receiver, the
sensor permissions and the Health Connect permissions. Two things cannot be
merged and have to live in your app:

### Health Connect rationale screen

Play rejects apps that request Health Connect permissions without a screen
explaining what the data is used for. Add both the intent filter (Android 13 and
below) and the alias (Android 14+) to whichever activity shows your privacy
policy:

```xml
<activity android:name=".MainActivity" android:exported="true">
    <intent-filter>
        <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
    </intent-filter>
</activity>

<activity-alias
    android:name="ViewPermissionUsageActivity"
    android:exported="true"
    android:permission="android.permission.START_VIEW_PERMISSION_USAGE"
    android:targetActivity=".MainActivity">
    <intent-filter>
        <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
        <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
    </intent-filter>
</activity-alias>
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

## ProGuard / R8

`consumer-rules.pro` ships with the library and is applied automatically. No
app-side rules are needed.
