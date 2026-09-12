package com.steptrackerpro.health

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.steptrackerpro.R
import com.steptrackerpro.core.ConfigStore

/**
 * The privacy policy target Health Connect links to from its permission sheet
 * and from its "app permissions" screen.
 *
 * Declaring it is not optional. Health Connect requires every app requesting
 * health permissions to handle
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (Android 13 and below)
 * and `android.intent.action.VIEW_PERMISSION_USAGE` with the
 * `android.intent.category.HEALTH_PERMISSIONS` category (Android 14 and up),
 * and Play review rejects health apps that do not - so the manifest entry
 * ships here rather than being left as a note in the install docs.
 *
 * All this activity does is open `privacyPolicyUrl` from config and finish. An
 * app that would rather show its own in-app screen can override the whole
 * entry in its manifest:
 *
 * ```xml
 * <activity android:name=".MyPolicyActivity" android:exported="true">
 *   <intent-filter>
 *     <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
 *   </intent-filter>
 * </activity>
 * <activity android:name="com.steptrackerpro.health.HealthPrivacyPolicyActivity"
 *     tools:node="remove" />
 * ```
 */
class HealthPrivacyPolicyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a web URL is opened. The value comes from the app's own config,
        // but this activity is exported and reachable from the Health Connect
        // UI, so it never launches an arbitrary scheme on the app's behalf.
        val url = ConfigStore(this).get().privacyPolicyUrl
            ?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
        if (url.isNullOrEmpty()) {
            // Better than a silent finish: the user tapped a link and deserves
            // to know it goes nowhere, and the developer sees it in QA.
            Toast.makeText(
                this,
                getString(R.string.stp_privacy_policy_missing),
                Toast.LENGTH_LONG
            ).show()
        } else {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        finish()
    }
}
