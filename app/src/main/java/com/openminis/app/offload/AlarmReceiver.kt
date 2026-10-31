package com.openminis.app.offload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openminis.app.logging.AppLogger

/**
 * BroadcastReceiver that fires when an alarm or timer triggers.
 * Shows a high-priority notification with the alarm label.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val NOTIFICATION_GROUP = "minis_alarm_group"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // [T-android-alarm-reschedule-on-restart] Both actions mean the same
        // thing for us: AlarmManager has dropped our pending alarms and they
        // need re-registering. BOOT_COMPLETED covers a reboot;
        // MY_PACKAGE_REPLACED covers an app update, which is far more frequent
        // and previously left every scheduled task dead until the next reboot.
        // Same handling for both — rescheduleAll() is idempotent.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AppLogger.info(TAG, "${intent.action} — re-registering scheduled task alarms")
            // [T-android-scheduled-tasks-design] Re-register every enabled
            // ScheduledTask with AlarmManager. The OS drops all pending
            // alarms on reboot, so persisted tasks would silently stop
            // firing without this. The legacy AlarmOffloadManager entries
            // are repeating alarms and DO survive reboot via the OS, so
            // we don't have to re-register them here.
            try {
                com.openminis.app.scheduled.ScheduledTaskManager(context).rescheduleAll()
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "scheduled-task rescheduleAll failed: ${t.message}")
            }
            return
        }

        val action = intent.action
        if (action != null && action != AlarmOffloadManager.ACTION_TRIGGER_ALARM) {
            AppLogger.warning(TAG, "Ignoring unexpected broadcast action: $action")
            return
        }

        val alarmId = intent.getStringExtra(AlarmOffloadManager.EXTRA_ALARM_ID)?.takeIf { it.isNotBlank() }
        if (alarmId == null) {
            AppLogger.warning(TAG, "Ignoring alarm broadcast without valid EXTRA_ALARM_ID")
            return
        }

        // Verify the alarmId against persisted alarms and use the persisted label
        // so third-party apps cannot spoof notifications via the exported receiver.
        val verifiedLabel = try {
            AlarmOffloadManager(context).onAlarmFired(alarmId)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "onAlarmFired($alarmId) failed: ${e.message}")
            null
        }
        if (verifiedLabel == null) {
            AppLogger.warning(TAG, "Ignoring unverified alarm broadcast for id=$alarmId")
            return
        }
        val label = verifiedLabel

        AppLogger.debug(TAG, "Alarm triggered: id=$alarmId label=$label")

        val notificationId = alarmId.hashCode() and 0x7FFFFFFF

        // Launch intent – open the app when the notification is tapped
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi = if (launchIntent != null) {
            PendingIntent.getActivity(
                context,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }

        val notification = NotificationCompat.Builder(context, AlarmOffloadManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Minis Alarm")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setGroup(NOTIFICATION_GROUP)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .apply { if (contentPi != null) setContentIntent(contentPi) }
            .build()

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot post notification – missing POST_NOTIFICATIONS permission: ${e.message}")
        }
    }
}
