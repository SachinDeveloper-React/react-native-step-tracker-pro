/**
 * Expo config plugin for react-native-step-tracker-pro. In app.json:
 *
 *   "plugins": [
 *     ["react-native-step-tracker-pro", {
 *       "healthConnect": { "read": true, "write": true, "backgroundRead": true },
 *       "batteryOptimizationPrompt": false
 *     }]
 *   ]
 *
 * It declares the opt-in permissions, raises minSdkVersion to 26 when the
 * project is below it, and leaves everything else to the library manifest.
 * Needs a development build (`expo prebuild`); Expo Go has no custom native
 * code.
 */
const { permissionsFor, applyPermissions, minSdkFor } = require('./manifest');

function configPlugins() {
  try {
    return require('@expo/config-plugins');
  } catch (error) {
    throw new Error(
      'react-native-step-tracker-pro: the Expo config plugin needs @expo/config-plugins, ' +
        'which the expo package provides. Run it through `npx expo prebuild`.'
    );
  }
}

function withStepTrackerPro(config, options = {}) {
  const { withAndroidManifest, withGradleProperties } = configPlugins();
  config = withAndroidManifest(config, (mod) => {
    mod.modResults = applyPermissions(mod.modResults, options);
    return mod;
  });
  config = withGradleProperties(config, (mod) => {
    const entry = mod.modResults.find(
      (item) => item.type === 'property' && item.key === 'android.minSdkVersion'
    );
    const next = minSdkFor(entry ? entry.value : undefined);
    if (next !== null) {
      if (entry) entry.value = next;
      else mod.modResults.push({ type: 'property', key: 'android.minSdkVersion', value: next });
    }
    return mod;
  });
  return config;
}

const pkg = require('../package.json');

let plugin = withStepTrackerPro;
try {
  plugin = configPlugins().createRunOncePlugin(withStepTrackerPro, pkg.name, pkg.version);
} catch (_) {
  // Loaded outside Expo - by a test, say. The bare function still works
  // once @expo/config-plugins is there.
}

module.exports = plugin;
module.exports.permissionsFor = permissionsFor;
