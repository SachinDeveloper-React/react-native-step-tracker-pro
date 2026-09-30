package com.steptrackerpro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.steptrackerpro.R
import com.steptrackerpro.core.DistanceUnit
import com.steptrackerpro.core.MetricsCalculator
import com.steptrackerpro.core.StepSnapshot
import com.steptrackerpro.core.StepTrackerConfig
import com.steptrackerpro.core.TrackingState
import java.text.NumberFormat
import java.util.Locale

/**
 * Builds the ongoing notification. The channel is created at IMPORTANCE_LOW so
 * the per-step updates never buzz, and the notification is `ongoing` +
 * `onlyAlertOnce` so Android reuses the same row instead of animating.
 */
class NotificationFactory(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel(config: StepTrackerConfig) {
        val name = config.notificationChannelName
            ?: context.getString(R.string.stp_channel_name)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = context.getString(R.string.stp_channel_description)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                // No lock-screen visibility of its own: each notification says
                // how it shows when locked - see notificationLockScreen - and
                // a channel left unset does not override that. A channel
                // created by an earlier release keeps PUBLIC, which does not
                // override a PRIVATE notification either; one set to PRIVATE
                // here would override a PUBLIC one.
            }
        manager.createNotificationChannel(channel)
    }

    fun build(
        snapshot: StepSnapshot,
        config: StepTrackerConfig,
        metrics: MetricsCalculator
    ): Notification {
        val paused = snapshot.state == TrackingState.PAUSED
        val percent = metrics.goalPercent(snapshot.steps, config.dailyGoal)
        val steps = formatCount(snapshot.steps)
        // In the unit config picks, and the phone's own number format like
        // the counts: 2,50 km in Germany, 1.55 mi in the US under `auto`.
        val miles = DistanceUnit.miles(config.notificationDistanceUnit, Locale.getDefault().country)
        val distance = String.format(Locale.getDefault(), "%.2f", DistanceUnit.convert(snapshot.distance, miles))
        val unit = context.getString(if (miles) R.string.stp_unit_mi else R.string.stp_unit_km)
        val kcal = formatCount(snapshot.calories.toInt())

        // Every word shown comes from a resource an app can translate or
        // reword by defining it in its own res/values*/strings.xml.
        val title = config.notificationTitle?.applyTokens(steps, distance, unit, kcal, percent, config)
            ?: context.resources.getQuantityString(R.plurals.stp_notification_title, snapshot.steps, steps)
        val text = when {
            paused -> context.getString(R.string.stp_paused_text)
            config.notificationText != null ->
                config.notificationText.applyTokens(steps, distance, unit, kcal, percent, config)
            else -> context.getString(
                if (miles) R.string.stp_notification_text_miles else R.string.stp_notification_text,
                distance, kcal, percent
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(resolveIcon(config))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(context.getString(R.string.stp_notification_goal, formatCount(config.dailyGoal)))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(
                if (config.notificationLockScreen == StepTrackerConfig.LOCK_SCREEN_PUBLIC) {
                    NotificationCompat.VISIBILITY_PUBLIC
                } else {
                    NotificationCompat.VISIBILITY_PRIVATE
                }
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())

        if (config.notificationLockScreen != StepTrackerConfig.LOCK_SCREEN_PUBLIC) {
            // Steps are health data. On a locked screen whose owner hides
            // sensitive content, this shows instead: that tracking is on,
            // and nothing about how much anyone has walked.
            builder.setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(resolveIcon(config))
                    .setContentTitle(
                        config.notificationChannelName ?: context.getString(R.string.stp_channel_name)
                    )
                    .setContentText(
                        context.getString(if (paused) R.string.stp_paused_text else R.string.stp_locked_text)
                    )
                    .setOngoing(true)
                    .setSilent(true)
                    .setShowWhen(false)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build()
            )
        }

        if (config.notificationActions) {
            if (paused) {
                builder.addAction(
                    0,
                    context.getString(R.string.stp_action_resume),
                    actionIntent(ServiceCommands.ACTION_RESUME, REQUEST_RESUME)
                )
            } else {
                builder.addAction(
                    0,
                    context.getString(R.string.stp_action_pause),
                    actionIntent(ServiceCommands.ACTION_PAUSE, REQUEST_PAUSE)
                )
            }
            openAppIntent()?.let { open ->
                builder.addAction(0, context.getString(R.string.stp_action_open), open)
            }
        }

        return builder.build()
    }

    fun update(notification: Notification) {
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun resolveIcon(config: StepTrackerConfig): Int {
        val custom = config.notificationIcon ?: return R.drawable.stp_ic_steps
        // Looked up by name, which resource shrinking cannot see: a release
        // build with shrinkResources removes the drawable unless the app
        // keeps it (docs/INSTALLATION.md). Said once, loudly, rather than
        // falling back without a word.
        val id = context.resources.getIdentifier(custom, "drawable", context.packageName)
        if (id == 0 && warnedIcon != custom) {
            warnedIcon = custom
            android.util.Log.w(
                TAG,
                "notificationIcon '$custom' is not a drawable in this app - using the default. " +
                    "With shrinkResources on, keep it: tools:keep=\"@drawable/$custom\" (see docs/INSTALLATION.md)"
            )
        }
        return if (id != 0) id else R.drawable.stp_ic_steps
    }

    /** The icon name last warned about, so a missing one is logged once, not per redraw. */
    @Volatile
    private var warnedIcon: String? = null

    /**
     * Launches the host app's main activity, reusing the existing task. Null when
     * the app has no launcher activity: wrapping an empty Intent would produce a
     * PendingIntent that silently does nothing when tapped, which is worse than
     * a notification that is plainly not tappable.
     */
    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_FROM_NOTIFICATION, true)
            }
            ?: return null
        return PendingIntent.getActivity(context, REQUEST_OPEN, launch, flags())
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, requestCode, intent, flags())
    }

    private fun flags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun String.applyTokens(
        steps: String,
        distance: String,
        unit: String,
        kcal: String,
        percent: Int,
        config: StepTrackerConfig
    ): String = this
        .replace("{steps}", steps)
        .replace("{distance}", distance)
        .replace("{unit}", unit)
        .replace("{calories}", kcal)
        .replace("{percent}", percent.toString())
        .replace("{goal}", formatCount(config.dailyGoal))

    companion object {
        private const val TAG = "StepTrackerPro"
        const val CHANNEL_ID = "step_tracker_pro"
        const val NOTIFICATION_ID = 8_143
        const val EXTRA_FROM_NOTIFICATION = "stp_from_notification"

        private const val REQUEST_OPEN = 3101
        private const val REQUEST_PAUSE = 3102
        private const val REQUEST_RESUME = 3103

        /**
         * NumberFormat is not thread safe, and notifications are now built from
         * both the sensor thread and the main thread. One instance per thread
         * is cheaper than locking on a path that runs once a second. Kept with
         * the locale it formats for: the service's thread outlives a change
         * of the phone's language, and the counts would otherwise keep the
         * old format beside a distance in the new one.
         */
        private val numberFormat = ThreadLocal<Pair<Locale, NumberFormat>>()

        private fun formatCount(value: Int): String {
            val locale = Locale.getDefault()
            val format = numberFormat.get()?.takeIf { it.first == locale }?.second
                ?: NumberFormat.getIntegerInstance(locale).also { numberFormat.set(locale to it) }
            return format.format(value)
        }
    }
}
