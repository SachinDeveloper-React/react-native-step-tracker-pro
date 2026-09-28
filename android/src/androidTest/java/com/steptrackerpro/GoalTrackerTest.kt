package com.steptrackerpro

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.steptrackerpro.core.GoalTracker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Each goal fires once per period - and again for a higher one, never for a lower. */
@RunWith(AndroidJUnit4::class)
class GoalTrackerTest {

    private lateinit var context: Context
    private val day = "2026-09-28"

    @Before
    fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(GoalTracker.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun raisingTheGoalFiresAgainAndLoweringItDoesNot() {
        val goals = GoalTracker(context)
        assertNotNull(goals.checkDaily(day, 5_000, 5_000))
        assertNull(goals.checkDaily(day, 6_000, 5_000))
        // Raised to 10k: nothing until 10k, then it fires for 10k.
        assertNull(goals.checkDaily(day, 9_000, 10_000))
        assertNotNull(goals.checkDaily(day, 10_000, 10_000))
        // Lowered below what was celebrated: no second celebration.
        assertNull(goals.checkDaily(day, 10_500, 8_000))
        // A new day starts over.
        assertNotNull(goals.checkDaily("2026-09-29", 8_000, 8_000))
    }

    @Test
    fun aMarkerFromBeforeTheUpgradeIsNotFiredAgain() {
        // 2.3.0 and earlier stored the period alone.
        context.getSharedPreferences(GoalTracker.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("fired_daily", day).commit()
        assertNull(GoalTracker(context).checkDaily(day, 20_000, 10_000))
    }

    @Test
    fun resetRearmsTodayWhateverWasCelebrated() {
        val goals = GoalTracker(context)
        assertNotNull(goals.checkDaily(day, 10_000, 10_000))
        goals.reset()
        assertNotNull(goals.checkDaily(day, 5_000, 5_000))
    }
}
