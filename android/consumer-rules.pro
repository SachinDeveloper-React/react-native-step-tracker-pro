# Room generated implementations are reflected on at runtime.
-keep class com.steptrackerpro.db.** { *; }
-keep class androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Health Connect record classes are resolved by name.
-keep class androidx.health.connect.client.records.** { *; }
-dontwarn androidx.health.connect.client.**

# Service, receiver and activity entry points referenced from the manifest.
-keep class com.steptrackerpro.service.** { *; }
-keep class com.steptrackerpro.health.HealthPermissionActivity { *; }
-keep class com.steptrackerpro.health.HealthPrivacyPolicyActivity { *; }

# The optional 1.1 permission constants are looked up by name, so that the
# package still builds against a 1.0 client pinned via ext.healthConnectVersion.
-keepclassmembers class androidx.health.connect.client.permission.HealthPermission {
    java.lang.String PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND;
    java.lang.String PERMISSION_READ_HEALTH_DATA_HISTORY;
}
-keep class com.steptrackerpro.sync.** { *; }

# Integrity checks. The manifest receiver and the Keystore wrapper are entry
# points; the rest is plain code.
-keep class com.steptrackerpro.integrity.ActivityTransitionReceiver { *; }

# Activity Recognition is compile-only (see build.gradle): an app that does
# not add play-services-location must still pass R8's missing-class check on
# the references this package makes to it. They are only ever reached after a
# runtime probe has found the classes.
-dontwarn com.google.android.gms.location.**
-dontwarn com.google.android.gms.tasks.**
-dontwarn com.google.android.gms.common.api.**
