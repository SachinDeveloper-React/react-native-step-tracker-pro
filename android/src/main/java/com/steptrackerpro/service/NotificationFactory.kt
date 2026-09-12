package com.steptrackerpro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.steptrackerpro.R
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
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
        val km = String.format(Locale.US, "%.2f", snapshot.distance / 1000.0)
        val kcal = formatCount(snapshot.calories.toInt())

        val title = config.notificationTitle?.applyTokens(steps, km, kcal, percent, config)
            ?: "$steps steps"
        val text = when {
            paused -> context.getString(R.string.stp_paused_text)
            config.notificationText != null ->
                config.notificationText.applyTokens(steps, km, kcal, percent, config)
            else -> context.getString(R.string.stp_notification_text, km, kcal, percent)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(resolveIcon(config))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("${formatCount(config.dailyGoal)} goal")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())

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
        val id = context.resources.getIdentifier(custom, "drawable", context.packageName)
        return if (id != 0) id else R.drawable.stp_ic_steps
    }

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
        km: String,
        kcal: String,
        percent: Int,
        config: StepTrackerConfig
    ): String = this
        .replace("{steps}", steps)
        .replace("{distance}", km)
        .replace("{calories}", kcal)
        .replace("{percent}", percent.toString())
        .replace("{goal}", formatCount(config.dailyGoal))

    companion object {
        const val CHANNEL_ID = "step_tracker_pro"
        const val NOTIFICATION_ID = 8_143
        const val EXTRA_FROM_NOTIFICATION = "stp_from_notification"

        private const val REQUEST_OPEN = 3101
        private const val REQUEST_PAUSE = 3102
        private const val REQUEST_RESUME = 3103

        /**
         * NumberFormat is not thread safe, and notifications are now built from
         * both the sensor thread and the main thread. One instance per thread
         * is cheaper than locking on a path that runs once a second.
         */
        private val numberFormat: ThreadLocal<NumberFormat> =
            ThreadLocal.withInitial { NumberFormat.getIntegerInstance(Locale.getDefault()) }

        private fun formatCount(value: Int): String =
            numberFormat.get()?.format(value) ?: value.toString()
    }
}
