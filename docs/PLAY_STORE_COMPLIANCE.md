# Play Store compliance

Read this before your first upload. A step tracker touches three of Play's
sensitive areas at once — foreground services, health data and battery
exemptions — and each has its own declaration form.

## 1. Foreground service type

Play requires a declared type and a justification for every foreground service.
This package uses `health`.

**Play Console → App content → Foreground service permissions.**

- **Permission:** `FOREGROUND_SERVICE_HEALTH`
- **Purpose:** continuous step counting for a fitness feature
- **Why a foreground service is required:** the app must keep reading the step
  sensor while the screen is off and the app is not in the foreground; deferred
  work such as WorkManager cannot sample a sensor continuously
- **Video:** record a screen capture showing the notification appearing when
  tracking starts, and the count updating while walking with the app closed.
  Reviewers reject text-only submissions here.

The notification must be visible and honest. Do not hide it, do not make it
dismissible, and do not use a title that misrepresents what is running.

## 2. Health Connect

**Play Console → Health apps declaration form.** Every permission declared in
the merged manifest has to be justified, so remove the ones you do not use
(see [INSTALLATION.md](INSTALLATION.md#trimming-permissions-you-do-not-use)).

Requirements:

- A privacy policy that specifically names the Health Connect data types you
  read and write, states that data is stored on-device, and explains any
  transmission off-device. Link it from both the Play listing and in-app.
- The `ACTION_SHOW_PERMISSIONS_RATIONALE` intent filter and the
  `VIEW_PERMISSION_USAGE` activity alias. Missing these is the most common
  rejection.
- No advertising, no selling health data, and no sharing it with third parties
  for anything unrelated to the feature the user asked for.
- Data deletion must be possible from inside your app. `clearHistory()` covers
  your local store; Health Connect data is deleted from the Health Connect app.

### On duplicate step data

From Android 14 with SDK Extension 20 or higher, Health Connect records
on-device steps by itself once any app holds `READ_STEPS`. If you also write
steps, users can see the same walk twice in the Health Connect UI. Two sane
options:

- **Read-only** — leave `healthConnectEnabled: true` but never call the write
  path. Use Health Connect as a source, your Room database as the record.
- **Write with attribution** — keep writing. Records carry a stable
  `clientRecordId` so re-syncing does not stack duplicates, but they still sit
  alongside the platform's own entries.

Pick one deliberately and say which in your privacy policy. Also note that as of
the June 2026 Health Connect update, on-device steps are attributed to a
device-specific synthetic package name rather than the generic `android` package,
which changes how you filter by source.

## 3. Battery optimisation exemption

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a restricted permission. Play allows
it only for a short list of use cases, and "our fitness app works better" is not
one of them. An app rejected here usually has to strip the permission and
resubmit.

Recommendation: **remove the permission** and use
`openBatteryOptimizationSettings()`, which opens the system list and lets the
user grant the exemption themselves. That path needs no declaration.

```xml
<uses-permission
    android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    tools:node="remove" />
```

If you keep it, submit the **Permissions declaration form** and expect to argue
the case.

## 4. ACTIVITY_RECOGNITION

A runtime permission from Android 10. Request it in context — after the user has
tapped something that clearly starts tracking — not on first launch. Play's data
safety form must list it under "Health and fitness → Fitness info".

## 5. Data safety form

Declare, at minimum:

| Data type | Collected | Shared | Purpose |
|---|---|---|---|
| Fitness info (steps, distance, calories) | Yes | Only if you set `remoteSyncUrl` | App functionality |

If `remoteSyncUrl` is set, you are transmitting health data off-device: declare
it as shared, state the recipient, confirm encryption in transit (use HTTPS —
the worker will happily post to `http://`, and you should not), and offer a
deletion path.

## 6. Notification content

Android 13+ makes notifications a runtime permission. Denying it hides the
notification but does not stop the service, and `allGranted` in
`checkPermissions()` reflects that deliberately. Do not block your onboarding on
`POST_NOTIFICATIONS`.

## 7. Pre-launch checklist

- [ ] Foreground service declaration submitted, with a video
- [ ] Health apps declaration submitted, only for permissions you actually use
- [ ] Privacy policy names each Health Connect data type
- [ ] `ACTION_SHOW_PERMISSIONS_RATIONALE` filter and `VIEW_PERMISSION_USAGE`
      alias present in the app manifest
- [ ] `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` removed, or declared and justified
- [ ] Unused `android.permission.health.*` entries removed with `tools:node="remove"`
- [ ] Data safety form matches whether `remoteSyncUrl` is configured
- [ ] `ACTIVITY_RECOGNITION` requested in context, with an explanation on screen
- [ ] In-app data deletion available
- [ ] Tested on a Xiaomi or Oppo device, where autostart restrictions kill
      services that survive fine on Pixel
