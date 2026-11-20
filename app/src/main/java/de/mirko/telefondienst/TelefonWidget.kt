package de.mirko.telefondienst

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Homescreen-Widget: heutiger Dienst + Wochenübersicht. */
class TelefonWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    companion object {
        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, TelefonWidget::class.java))
            if (ids.isEmpty()) return

            val today = LocalDate.now()
            val all = Store.all(ctx)
            val state = Store.bundesland(ctx)

            // Heute
            val holidayToday = Holidays.getHolidayName(today, state)
            val blocks = Scheduler.blocks(all[today] ?: emptySet())
            val todayText = if (holidayToday != null) "Feiertag ($holidayToday)"
            else if (blocks.isEmpty()) "Kein Dienst eingetragen"
            else blocks.joinToString(", ") { "%02d:00–%02d:00".format(it.first, it.last + 1) }
            val todayTitle = "Heute, " +
                today.format(DateTimeFormatter.ofPattern("EEE dd.MM.", Locale.GERMAN))

            // Woche
            val monday = today.with(DayOfWeek.MONDAY)
            val weekNo = today[WeekFields.ISO.weekOfWeekBasedYear()]
            val days = (0..4).map { monday.plusDays(it.toLong()) }
            val counts = days.map { (all[it] ?: emptySet()).size }
            val weekTitle = "Woche $weekNo: ${counts.sum()} Std."
            val weekDetail = days.joinToString("  ") { d ->
                val prefix = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).take(2)
                val hName = Holidays.getHolidayName(d, state)
                if (hName != null) "$prefix FT" else "$prefix ${(all[d] ?: emptySet()).size}"
            }

            val open = PendingIntent.getActivity(
                ctx,
                0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val views = RemoteViews(ctx.packageName, R.layout.widget).apply {
                setTextViewText(R.id.tvTodayTitle, todayTitle)
                setTextViewText(R.id.tvToday, todayText)
                setTextViewText(R.id.tvWeekTitle, weekTitle)
                setTextViewText(R.id.tvWeek, weekDetail)
                setOnClickPendingIntent(R.id.widgetRoot, open)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
