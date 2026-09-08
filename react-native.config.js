module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: 'android',
        packageImportPath: 'import com.steptrackerpro.StepTrackerProPackage;',
        packageInstance: 'new StepTrackerProPackage()',
      },
      // iOS is intentionally unsupported: this package wraps Android-only
      // hardware sensors and Health Connect.
      ios: null,
    },
  },
};
