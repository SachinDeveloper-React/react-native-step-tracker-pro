# Security

## Reporting

Email the address in `package.json` (`author`) with the details, or open a
GitHub security advisory. Please do not file public issues for
vulnerabilities. Expect an acknowledgement within a week.

## What this package handles

Step, distance and calorie totals per day, on the device. Health data under
Google Play's policy, though low-sensitivity in practice. No account data, no
location, no network access unless `remoteSyncUrl` is configured.

## Threat model and what is done about it

### Data at rest

- Counter state and config live in the app's private `SharedPreferences`;
  history in a private Room database. Both are sandboxed per app by Android
  and are **not additionally encrypted** — a rooted device or a backup
  extract can read them. If that matters for your product, exclude them from
  backups (`android:allowBackup="false"` or backup rules) and treat the
  numbers as the user's own data, which they are.
- `remoteSyncHeaders` are stored in the same place, in the clear. Put
  short-lived tokens in them, never long-lived secrets, and rotate them from
  your app. They are never returned by `getConfig()`.

### Data in transit

- `remoteSyncUrl` must be `https://`. The JS layer rejects anything else at
  `initialize()` and the worker refuses to upload to it, unless
  `remoteSyncAllowHttp` is set for a development server.
- Health Connect traffic is on-device IPC.

### Exported components

| Component | Exported | Why | Guard |
|---|---|---|---|
| `BootReceiver` | yes | `BOOT_COMPLETED` is only delivered to exported receivers | acts only when the user had tracking on and permissions are held; an unprotected `QUICKBOOT_POWERON` from another app can at most restart tracking the user already asked for |
| `HealthPrivacyPolicyActivity` (+ alias) | yes | Health Connect and Play require it | opens only `http(s)` URLs from the app's own config; never an arbitrary scheme |
| `StepTrackerService` | no | | |
| `HealthPermissionActivity` | no | | |
| `NotificationActionReceiver` | no | | PendingIntents are `FLAG_IMMUTABLE` |

### Input from the bridge

Dates are validated as `yyyy-MM-dd` and ordered before reaching native.
Numeric config is clamped natively (`StepTrackerConfig.sanitised()`) as well
as validated in JS, because config is also rebuilt from persisted JSON.

### Step data integrity (rewards, leaderboards)

This package counts what the sensors report. It does not, and cannot,
prove the steps were walked:

- A rooted device can feed a mock sensor; a phone can be shaken.
- `resetToday()` and `clearHistory()` are part of the public API.
- Steps recovered after a dead period across midnight are **apportioned by
  time** (`gapRecovery: 'split'`), which is an estimate. Use `'drop'` where an
  over-credit costs money.
- Under `'auto'`, a source the user pins is trusted outright.

If steps have monetary value, verify server-side: rate-limit implausible
daily totals, compare against `stepSource` and `getTrackingHealth()`
(`recoveryCount`, `bestSensor`), and treat `'accelerometer'` and merged
counts as lower-confidence.

### Permissions

Only what the configured mode needs is requested; see
[docs/PERMISSIONS.md](docs/PERMISSIONS.md). `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
and the Health Connect permissions are declared by the library manifest and
are meant to be removed with `tools:node="remove"` by apps that do not use them.

### Dependencies

Runtime: AndroidX core, lifecycle, activity, Room, WorkManager, the Health
Connect client, and kotlinx-coroutines — all Google or JetBrains. No
third-party network or analytics code. `npm audit` and Dependabot run on the
JS toolchain, which is development-only; nothing from `node_modules` ships in
the package.
