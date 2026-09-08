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
-keep class com.steptrackerpro.sync.** { *; }
