/**
 * The manifest changes the Expo config plugin makes, as plain functions over
 * the JSON shape `@expo/config-plugins` hands a manifest mod, so they can be
 * tested without Expo installed.
 *
 * From 2.0 the library's own manifest carries only what sensor-only counting
 * needs. These add what an app opts into:
 *
 *   healthConnect: true | { read, write, readTypes, backgroundRead, historyRead, activeCalories }
 *
 * `readTypes` matches the `healthConnectReadTypes` config option: the read
 * permissions declared are the ones for those types, steps always.
 *   batteryOptimizationPrompt: true
 */

const HEALTH = 'android.permission.health.';

/** Health Connect's permission suffix for each `healthConnectReadTypes` name. */
const READ_TYPE_PERMISSIONS = {
  steps: 'STEPS',
  distance: 'DISTANCE',
  totalCalories: 'TOTAL_CALORIES_BURNED',
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

module.exports = { permissionsFor, applyPermissions, minSdkFor };
