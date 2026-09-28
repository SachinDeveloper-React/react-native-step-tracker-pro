/**
 * Expo config plugin for react-native-step-tracker-pro. In app.json:
 *
 *   "plugins": [
 *     ["react-native-step-tracker-pro", {
 *       "healthConnect": { "read": true, "write": true, "backgroundRead": true },
 *       // or, for an app that reads steps alone:
 *       // "healthConnect": { "readTypes": ["steps"], "write": false },
 *       "batteryOptimizationPrompt": false,
 *       "notificationIcon": "ic_stat_steps"
 *     }]
 *   ]
 *
 * It declares the opt-in permissions, raises minSdkVersion to 26 when the
 * project is below it, and leaves everything else to the library manifest.
 * Needs a development build (`expo prebuild`); Expo Go has no custom native
 * code.
 */
const path = require('path');
const fs = require('fs');
const {
  permissionsFor,
  applyPermissions,
  applyHealthActivities,
  keepXml,
  minSdkFor,
} = require('./manifest');

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
  const { withAndroidManifest, withGradleProperties, withDangerousMod } = configPlugins();
  config = withAndroidManifest(config, (mod) => {
    mod.modResults = applyHealthActivities(applyPermissions(mod.modResults, options), options);
    return mod;
  });
  const keep = keepXml(options.notificationIcon);
  if (keep) {
    config = withDangerousMod(config, [
      'android',
      async (mod) => {
        const dir = path.join(mod.modRequest.platformProjectRoot, 'app/src/main/res/raw');
        fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(path.join(dir, 'step_tracker_pro_keep.xml'), keep);
        return mod;
      },
    ]);
  }
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
