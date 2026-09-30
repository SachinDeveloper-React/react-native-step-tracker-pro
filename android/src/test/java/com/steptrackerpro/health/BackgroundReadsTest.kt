package com.steptrackerpro.health

import android.app.ActivityManager.RunningAppProcessInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Health Connect refuses reads from an app in the background unless it holds
 * `READ_HEALTH_DATA_IN_BACKGROUND`, and takes "background" from AppOps, whose
 * foreground reaches as far as a foreground service. The tracking service is
 * one, so while tracking is on nothing is refused.
 */
class BackgroundReadsTest {

    @Test
    fun `an activity on screen or a foreground service running is the foreground`() {
        assertTrue(HealthConnectManager.inForegroundForReads(RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(HealthConnectManager.inForegroundForReads(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
    }

    @Test
    fun `visible, perceptible, asleep on top, a plain service or cached is the background`() {
        for (importance in listOf(
            RunningAppProcessInfo.IMPORTANCE_VISIBLE,
            RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE,
            RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING,
            RunningAppProcessInfo.IMPORTANCE_SERVICE,
            RunningAppProcessInfo.IMPORTANCE_CACHED
        )) {
            assertFalse("importance $importance", HealthConnectManager.inForegroundForReads(importance))
        }
    }
}
