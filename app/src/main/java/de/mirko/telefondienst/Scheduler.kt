package de.mirko.telefondienst

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZoneId

object Scheduler {
    const val CHANNEL = "dienst_erinnerung"
    const val EXTRA_START = "start"
    const val EXTRA_END_HOUR = "end_hour"

    /** Fasst zusammenhängende Stunden zu Blöcken zusammen, z. B. {9,10,11,14} -> [9..11, 14..14]. */
    fun blocks(hours: Set<Int>): List<IntRange> {
        val sorted = hours.sorted()
        val result = mutableListOf<IntRange>()
        var start = -1
        var prev = -1
        for (h in sorted) {
            if (start == -1) {
                start = h
            } else if (h != (prev + 1)) {
                result.add(start..prev)
                start = h
            }
            prev = h
        }
        if (start != -1) result.add(start..prev)
        return result
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL,
            "Dienst-Erinnerung",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Erinnerungen vor Dienstbeginn"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            enableLights(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun canScheduleExact(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 31) ||
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pendingIntent(ctx: Context, start: Long?, endHour: Int?): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            start?.let { putExtra(EXTRA_START, it) }
            endHour?.let { putExtra(EXTRA_END_HOUR, it) }
        }
        return PendingIntent.getBroadcast(
            ctx,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Plant genau einen Alarm: die nächste anstehende Erinnerung. Wird nach jedem Alarm neu geplant. */
    fun scheduleNext(ctx: Context) {
        TelefonWidget.updateAll(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val lead = Store.leadMinutes(ctx) * 60_000L
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()

        var bestTrigger = Long.MAX_VALUE
        var bestStart = 0L
        var bestEnd = 0
        val state = Store.bundesland(ctx)

        for ((date, hours) in Store.all(ctx)) {
            if (Holidays.isHoliday(date, state)) continue
            for (block in blocks(hours)) {
                val start = date.atTime(block.first, 0).atZone(zone).toInstant().toEpochMilli()
                val trigger = start - lead
                if (trigger in ((now + 1) until bestTrigger)) {
                    bestTrigger = trigger
                    bestStart = start
                    bestEnd = block.last + 1
                }
            }
        }

        if (bestTrigger == Long.MAX_VALUE) {
            am.cancel(pendingIntent(ctx, null, null))
            return
        }

        val pi = pendingIntent(ctx, bestStart, bestEnd)
        if (canScheduleExact(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bestTrigger, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bestTrigger, pi)
        }
    }
}
