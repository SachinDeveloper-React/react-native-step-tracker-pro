# Migrating

## From 2.4 to 2.5

Nothing to change in code for most apps.

- **`'rate_limited'` joins `HealthConnectRead`** - `RangeStats.healthConnect`,
  `StepSourceList.healthConnect`, a snapshot's `sourcesStatus` - and a
  snapshot's `healthConnectRecords.status`: Health Connect refused the read
  for quota. A `switch` that handles every value needs one more case, and a
  server that validates `sourcesStatus` against a fixed list needs one more
  value. Treat it like `'timed_out'`: not "no other apps", and worth asking
  again later. See [Rate limits](API.md#rate-limits).
- **The Health Connect sync runs every 15 minutes by default**, from 30, and
  waits while the battery is low. Set `healthConnectSyncIntervalMinutes: 30`
  to keep the old pace.
- **Records the package writes carry the phone's maker and model** with the
  phone device type. Nothing reads them differently; an app showing where
  steps came from can now say which phone.
- **The database moves to schema version 6** on its own, adding one empty
  table for per-minute records; every row stays as it is.
- **Per-minute records are opt-in**: `healthConnectWriteGranularity:
  'minute'` writes a record for every minute with steps, as Health Connect's
  guidance for steps asks. Nothing changes until you set it; the next sync
  after switching, either way, replaces each day's records of the other
  kind. It is planned to become the default in 3.0. See
  [Per-minute records](API.md#writehealthconnectstepsdate-string-promiseboolean).

## From 2.2 to 2.3

Nothing to change in code for most apps; two defaults move.

- **The notification is private on the lock screen.** On a phone set to hide
  sensitive content when locked, it shows "Counting steps" instead of the
  count. To keep showing the count, pass
  `notificationLockScreen: 'public'`.
- **`hasAttestationKey()` ignores a key a snapshot made.** If you sign
  snapshots before attesting, that key was reported as attested and uploads
  were signed with it. After the upgrade such an install reports `false`
  and your usual "attest when there is no key" path runs once: expect one
  new attestation per such install, and register the key it sends.

## From 1.x to 2.0

For most apps the whole upgrade is: **add the Health Connect permissions you
use to your manifest, and rebuild.** 1.x declared them for every app; 2.0
declares only what counting with the phone's sensor needs, so each app
declares - and justifies to Play - only what it actually uses. The reasoning
is in [PERMISSIONS.md](PERMISSIONS.md#why-the-split-sits-there).

Nothing changes for the people using your app: the same permission dialogs,
the same Health Connect sheet, the same numbers.

### 1. What moved

| Permission | 1.x | 2.0 |
|---|---|---|
| `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` | library | library - nothing to do |
| `health.READ_STEPS`, `READ_DISTANCE`, `READ_TOTAL_CALORIES_BURNED` | library | **your app** |
| `health.WRITE_STEPS`, `WRITE_DISTANCE`, `WRITE_TOTAL_CALORIES_BURNED` | library | **your app** |
| `health.READ_HEALTH_DATA_IN_BACKGROUND`, `READ_HEALTH_DATA_HISTORY` | library | **your app** |
| `health.READ_ACTIVE_CALORIES_BURNED` | - | **your app** (new in 2.0, opt-in) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | library | **your app** |

### 2. Add what your mode uses

Find your [usage mode](USAGE_MODES.md) and add its block to
`android/app/src/main/AndroidManifest.xml`, inside `<manifest>`.

**Mode C - sensor and Health Connect (the default config).** Before, in
1.x, there was nothing in your manifest. After:

```xml
<uses-permission android:name="android.permission.health.READ_STEPS" />
<uses-permission android:name="android.permission.health.READ_DISTANCE" />
<uses-permission android:name="android.permission.health.READ_TOTAL_CALORIES_BURNED" />
<uses-permission android:name="android.permission.health.WRITE_STEPS" />
<uses-permission android:name="android.permission.health.WRITE_DISTANCE" />
<uses-permission android:name="android.permission.health.WRITE_TOTAL_CALORIES_BURNED" />

<!-- only with healthConnectBackgroundRead: true -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />
<!-- only with healthConnectHistoryRead: true -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_HISTORY" />
<!-- only if you call requestDisableBatteryOptimization() for the direct dialog -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

**Mode B - Health Connect only.** The read set, plus the optional reads you
turned on. Keep the `tools:node="remove"` lines for the sensor, service and
boot receiver that 1.x told you to add - the library still declares those.
Delete the ones for the health write set and the battery permission: they
remove nothing now. Set `healthConnectWriteEnabled: false` if you have not
already, so the write set is never requested.

**Mode A - sensor only.** Nothing to add. Delete the
`tools:node="remove"` lines for health permissions and the battery
permission; they are harmless but remove nothing now. Add the battery
permission only if you want the direct dialog.

A config that asks for something you did not declare no longer fails
silently. `requestHealthConnectPermissions()` and `enableHealthConnect()`
reject with `E_HEALTH_CONNECT_NOT_DECLARED`, naming the missing entries.

### 3. Expo

Instead of editing the manifest, list the package under `plugins` with what
you use, and prebuild:

```json
["react-native-step-tracker-pro", {
  "healthConnect": { "read": true, "write": true, "backgroundRead": true },
  "batteryOptimizationPrompt": false
}]
```

The plugin also raises `android.minSdkVersion` to 26. Details in
[INSTALLATION.md](INSTALLATION.md#expo).

### 4. Check it

```ts
const { undeclaredPermissions } = await StepTracker.getHealthConnectStatus();
// [] when the manifest has everything your config asks for
```

and compare the merged manifest with what you expect to declare to Play:

```sh
cd android && ./gradlew :app:processDebugMainManifest
grep 'uses-permission' app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
```

### 5. The other breaking changes

Most apps are not affected by these; check each one.

**Events on the new architecture** arrive through codegen-typed emitters. If
you subscribed with your own `NativeEventEmitter`, switch to the package's
`addListener`:

```ts
// 1.x - stops receiving events on the new architecture in 2.0
new NativeEventEmitter(NativeModules.StepTrackerPro)
  .addListener('StepTrackerPro:stepsChanged', onSteps);

// 2.0 - either architecture
const sub = StepTracker.addListener('stepsChanged', onSteps);
// later
sub.remove();
```

**`removeListener()` with no argument** is deprecated and warns once. It
removes every listener in the app, the hooks' included. Keep the
subscription `addListener` returns and call `remove()` on it, or call
`removeAllListeners()` when you really mean all of them.

**Refused uploads are not retried.** If you set `remoteSyncHeaders`, handle
the new event, so an expired token is replaced instead of silently stopping
uploads:

```ts
StepTracker.addListener('syncAuthFailed', async () => {
  const token = await refreshToken();
  await StepTracker.updateConfig({ remoteSyncHeaders: { Authorization: `Bearer ${token}` } });
  await StepTracker.syncNow();
});
```

**`useStepTracker` applies config changes.** 1.x read config once on mount
and dropped every later change. 2.0 pushes a change through `updateConfig()`.
Pass config from one component - the one that owns it - and expect a change
to take effect.

**minSdk 26.** The library now declares it itself. If your app is still on
React Native's template default of 24, raise `minSdkVersion` to 26; the
manifest merge names this package until you do.

**Rebuild the native app.** The codegen spec changed:

```sh
cd android && ./gradlew clean && cd ..
npx react-native run-android
```

### 6. What happens on its own

- **Remote sync headers** stored in the clear by 1.x are encrypted with a
  Keystore key the first time 2.0 reads config. Nothing to do.
- **The database** migrates on its own, from any 1.x version; 2.0 adds no
  schema change of its own.
- **AGP 9 and Kotlin 2.2** are detected; there is nothing to configure
  whether your app uses AGP's built-in Kotlin or not.
- **The Health Connect client** moves to 1.1.0 by itself on compileSdk 36+.
  `ext.healthConnectVersion` still pins a version if you need one.
- **Config keys** are unchanged; every 1.x key means what it did.

### 7. Play Console

Compare the merged manifest with your current declarations:

- **Mode C** with the same set added back: nothing changes.
- **A sensor-only app that inherited health permissions from 1.x** no longer
  declares any. Update the data safety form, and the Health apps declaration
  is no longer needed.
- **Dropped the battery permission**: nothing to declare any more for it.

[PLAY_STORE_COMPLIANCE.md](PLAY_STORE_COMPLIANCE.md) has the forms in detail.
