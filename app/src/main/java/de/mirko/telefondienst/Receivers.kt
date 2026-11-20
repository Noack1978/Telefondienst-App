package de.mirko.telefondienst

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.time.Instant
import java.time.ZoneId

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Telefondienst:ReminderWakeLock",
        )
        wakeLock.acquire(5000L)

        try {
            val start = intent.getLongExtra(Scheduler.EXTRA_START, 0L)
            val endHour = intent.getIntExtra(Scheduler.EXTRA_END_HOUR, 0)
            if (start != 0L) {
                Scheduler.ensureChannel(context)
                val startHour = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).hour
                val text = "Telefondienst von %02d:00 bis %02d:00 Uhr".format(startHour, endHour)

                val open = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val notification = Notification.Builder(context, Scheduler.CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Telefondienst startet bald")
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .build()
                context.getSystemService(NotificationManager::class.java)
                    ?.notify((start / 60_000L).toInt(), notification)
            }
            Scheduler.scheduleNext(context)
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Scheduler.scheduleNext(context)
        }
    }
}
