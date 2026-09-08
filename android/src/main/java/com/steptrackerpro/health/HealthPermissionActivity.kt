package com.steptrackerpro.health

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Health Connect permissions can only be requested through an
 * ActivityResultContract, which needs a real Activity. The React module starts
 * this transparent activity, which requests the grants and hands the result
 * back through [Callbacks].
 *
 * The manifest entry must not carry `android:noHistory`: that finishes the
 * activity the moment the Health Connect screen covers it, so the contract can
 * never deliver and every request resolves with the pre-grant status.
 */
class HealthPermissionActivity : ComponentActivity() {

    private lateinit var launcher: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = registerForActivityResult(HealthConnectManager.permissionContract()) { granted ->
            Callbacks.deliver(granted)
            setResult(Activity.RESULT_OK)
            finish()
            overridePendingTransition(0, 0)
        }
        // Only launch on a fresh start. After a configuration change or a
        // process restart the contract is already in flight and relaunching
        // would stack a second permission screen.
        if (savedInstanceState == null) {
            runCatching { launcher.launch(HealthConnectManager.PERMISSIONS) }
                .onFailure {
                    Callbacks.deliver(emptySet())
                    finish()
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Guard against the activity being torn down before the contract
        // returns (e.g. the provider app was killed). isFinishing is false when
        // the system is only recreating us, where the result is still coming.
        if (isFinishing) Callbacks.deliverIfPending()
    }

    object Callbacks {
        fun interface Listener {
            fun onResult(granted: Set<String>)
        }

        private val listeners = CopyOnWriteArrayList<Listener>()

        /**
         * A result that arrived before anyone was waiting for it. The caller
         * registers its listener only after `startActivity` succeeds, so that a
         * failed launch cannot leave a listener armed to settle a promise that
         * was already rejected; this closes the window that opens up.
         */
        private var latched: Set<String>? = null

        /** Call before launching, so a stale result cannot be picked up. */
        @Synchronized
        fun expect() {
            latched = null
        }

        @Synchronized
        fun await(listener: Listener) {
            val ready = latched
            if (ready != null) {
                latched = null
                runCatching { listener.onResult(ready) }
                return
            }
            listeners.add(listener)
        }

        @Synchronized
        fun deliver(granted: Set<String>) {
            if (listeners.isEmpty()) {
                latched = granted
                return
            }
            val snapshot = listeners.toList()
            listeners.clear()
            snapshot.forEach { runCatching { it.onResult(granted) } }
        }

        @Synchronized
        fun deliverIfPending() {
            if (listeners.isNotEmpty()) deliver(emptySet())
        }
    }
}
