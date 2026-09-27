/**
 * The manifest changes the Expo config plugin makes, as plain functions over
 * the JSON shape `@expo/config-plugins` hands a manifest mod, so they can be
 * tested without Expo installed.
 *
 * From 2.0 the library's own manifest carries only what sensor-only counting
 * needs. These add what an app opts into:
 *
 *   healthConnect: true | { read, write, backgroundRead, historyRead, activeCalories }
 *   batteryOptimizationPrompt: true
 */

const HEALTH = 'android.permission.health.';

/** The permissions a set of plugin options needs, in manifest order. */
function permissionsFor(options = {}) {
  const out = [];
  const hc = options.healthConnect;
  if (hc) {
    const scope = hc === true ? {} : hc;
    const read = scope.read !== false;
    const write = scope.write !== false;
    const types = ['STEPS', 'DISTANCE', 'TOTAL_CALORIES_BURNED'];
    if (read) types.forEach((t) => out.push(`${HEALTH}READ_${t}`));
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
