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
