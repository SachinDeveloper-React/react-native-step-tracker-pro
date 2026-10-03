/**
 * The manifest changes the Expo config plugin makes, as plain functions over
 * the JSON shape `@expo/config-plugins` hands a manifest mod, so they can be
 * tested without Expo installed.
 *
 * From 2.0 the library's own manifest carries only what sensor-only counting
 * needs. These add what an app opts into:
 *
 *   healthConnect: true | false | { read, write, readTypes, backgroundRead, historyRead, activeCalories, vitals }
 *   notificationIcon: 'ic_stat_steps'
 *
 * `healthConnect: false` says the app never uses Health Connect: the
 * privacy-policy activity and alias the library merges in for it are
 * removed. Left out, they stay - Android 14 refuses Health Connect permission
 * requests from an app without them, so they are only removed when asked.
 *
 * `notificationIcon` names the custom notification drawable, which the
 * library finds by name at runtime - invisible to resource shrinking - so
 * the plugin writes a keep rule for it.
 *
 * `readTypes` matches the `healthConnectReadTypes` config option: the read
 * permissions declared are the ones for those types, steps always.
 * `vitals` matches `healthConnectReadVitals`: one read permission per vital
 * listed, none by default.
 *   batteryOptimizationPrompt: true
 */

const HEALTH = 'android.permission.health.';

/** Health Connect's permission suffix for each `healthConnectReadTypes` name. */
const READ_TYPE_PERMISSIONS = {
  steps: 'STEPS',
  distance: 'DISTANCE',
  totalCalories: 'TOTAL_CALORIES_BURNED',
};

/** Health Connect's permission suffix for each `healthConnectReadVitals` name. */
const VITAL_PERMISSIONS = {
  heartRate: 'HEART_RATE',
  restingHeartRate: 'RESTING_HEART_RATE',
  oxygenSaturation: 'OXYGEN_SATURATION',
  respiratoryRate: 'RESPIRATORY_RATE',
  bloodPressure: 'BLOOD_PRESSURE',
  bodyTemperature: 'BODY_TEMPERATURE',
  bloodGlucose: 'BLOOD_GLUCOSE',
};

/** The permissions a set of plugin options needs, in manifest order. */
function permissionsFor(options = {}) {
  const out = [];
  const hc = options.healthConnect;
  if (hc) {
    const scope = hc === true ? {} : hc;
    const read = scope.read !== false;
    const write = scope.write !== false;
    const types = ['STEPS', 'DISTANCE', 'TOTAL_CALORIES_BURNED'];
    if (read) readTypesFor(scope.readTypes).forEach((t) => out.push(`${HEALTH}READ_${t}`));
    if (write) types.forEach((t) => out.push(`${HEALTH}WRITE_${t}`));
    if (read && scope.backgroundRead) out.push(`${HEALTH}READ_HEALTH_DATA_IN_BACKGROUND`);
    if (read && scope.historyRead) out.push(`${HEALTH}READ_HEALTH_DATA_HISTORY`);
    if (read && scope.activeCalories) out.push(`${HEALTH}READ_ACTIVE_CALORIES_BURNED`);
    if (read) vitalsFor(scope.vitals).forEach((t) => out.push(`${HEALTH}READ_${t}`));
  }
  if (options.batteryOptimizationPrompt) {
    out.push('android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS');
  }
  return out;
}

/**
 * The permission suffixes for a `readTypes` option: all three when it is
 * absent, as before 2.1; steps always, as the library requires them.
 */
function readTypesFor(readTypes) {
  if (readTypes == null) return Object.values(READ_TYPE_PERMISSIONS);
  if (!Array.isArray(readTypes)) {
    throw new Error('react-native-step-tracker-pro: healthConnect.readTypes must be an array');
  }
  const unknown = readTypes.filter((t) => !(t in READ_TYPE_PERMISSIONS));
  if (unknown.length > 0) {
    throw new Error(
      `react-native-step-tracker-pro: unknown healthConnect.readTypes ${unknown.join(', ')}; ` +
        `use ${Object.keys(READ_TYPE_PERMISSIONS).join(', ')}`
    );
  }
  return Object.keys(READ_TYPE_PERMISSIONS)
    .filter((t) => t === 'steps' || readTypes.includes(t))
    .map((t) => READ_TYPE_PERMISSIONS[t]);
}

/** The permission suffixes for a `vitals` option, in a fixed order; none when it is absent. */
function vitalsFor(vitals) {
  if (vitals == null) return [];
  if (!Array.isArray(vitals)) {
    throw new Error('react-native-step-tracker-pro: healthConnect.vitals must be an array');
  }
  const unknown = vitals.filter((v) => !(v in VITAL_PERMISSIONS));
  if (unknown.length > 0) {
    throw new Error(
      `react-native-step-tracker-pro: unknown healthConnect.vitals ${unknown.join(', ')}; ` +
        `use ${Object.keys(VITAL_PERMISSIONS).join(', ')}`
    );
  }
  return Object.keys(VITAL_PERMISSIONS)
    .filter((v) => vitals.includes(v))
    .map((v) => VITAL_PERMISSIONS[v]);
}

const TOOLS = 'http://schemas.android.com/tools';
const HEALTH_ACTIVITY = 'com.steptrackerpro.health.HealthPrivacyPolicyActivity';
const HEALTH_ALIAS = 'com.steptrackerpro.health.ViewPermissionUsageActivity';

/**
 * With `healthConnect: false`, marks the library's Health Connect
 * privacy-policy activity and its alias for removal from the merged
 * manifest. Anything else leaves the manifest as it is.
 */
function applyHealthActivities(manifest, options = {}) {
  if (options.healthConnect !== false) return manifest;
  const root = manifest.manifest;
  root.$ = { ...(root.$ || {}), 'xmlns:tools': TOOLS };
  const app = (root.application && root.application[0]) || {};
  if (!root.application) root.application = [app];
  const remove = (tag, name) => {
    const list = app[tag] || [];
    if (!list.some((entry) => entry.$ && entry.$['android:name'] === name)) {
      list.push({ $: { 'android:name': name, 'tools:node': 'remove' } });
    }
    app[tag] = list;
  };
  remove('activity', HEALTH_ACTIVITY);
  remove('activity-alias', HEALTH_ALIAS);
  return manifest;
}

/**
 * The resource file that keeps a drawable found only by name, or null when
 * there is none. `shrinkResources` cannot see a name that arrives from
 * JavaScript at runtime and would otherwise remove it.
 */
function keepXml(icon) {
  if (icon == null) return null;
  if (typeof icon !== 'string' || !/^[a-z0-9_]+$/.test(icon)) {
    throw new Error(
      'react-native-step-tracker-pro: notificationIcon must be a drawable name (a-z, 0-9, _)'
    );
  }
  return (
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    `<resources xmlns:tools="${TOOLS}" tools:keep="@drawable/${icon}" />\n`
  );
}

/** Adds each permission the options need that the manifest does not declare yet. */
function applyPermissions(manifest, options = {}) {
  const root = manifest.manifest;
  const existing = root['uses-permission'] || [];
  const declared = new Set(existing.map((entry) => entry.$ && entry.$['android:name']));
  const added = permissionsFor(options)
    .filter((name) => !declared.has(name))
    .map((name) => ({ $: { 'android:name': name } }));
  root['uses-permission'] = existing.concat(added);
  return manifest;
}

/**
 * The minSdkVersion to write into gradle.properties, or null to leave it:
 * this package and Health Connect need 26, and a project already above that
 * keeps its own.
 */
function minSdkFor(current) {
  const value = parseInt(current, 10);
  return Number.isFinite(value) && value >= 26 ? null : '26';
}

module.exports = { permissionsFor, applyPermissions, applyHealthActivities, keepXml, minSdkFor };
