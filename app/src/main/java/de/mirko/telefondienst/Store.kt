package de.mirko.telefondienst

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/** Speichert Dienststunden als "yyyy-MM-dd#Stunde" in SharedPreferences. */
object Store {
    const val FIRST_HOUR = 8
    const val LAST_HOUR = 16 // Ende; letzter Slot ist 15–16 Uhr

    private const val PREFS = "telefondienst"
    private const val KEY_SLOTS = "slots"
    private const val KEY_LEAD = "lead_minutes"
    private const val KEY_BUNDESLAND = "bundesland"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rawSlots(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_SLOTS, emptySet()) ?: emptySet()

    fun hours(ctx: Context, date: LocalDate): Set<Int> =
        rawSlots(ctx).asSequence().mapNotNull {
            val (d, h) = it.split("#").let { p -> p[0] to p[1] }
            if (d == date.toString()) h.toInt() else null
        }.toSet()

    fun setHours(ctx: Context, date: LocalDate, hours: Set<Int>) {
        val rest = rawSlots(ctx).filterNot { it.startsWith("$date#") }
        val updated = rest.toMutableSet()
        hours.forEach { updated.add("$date#$it") }
        prefs(ctx).edit { putStringSet(KEY_SLOTS, updated) }
        BackupManager.triggerAutoBackup(ctx.applicationContext)
    }

    fun all(ctx: Context): Map<LocalDate, Set<Int>> =
        rawSlots(ctx).asSequence().map {
            val (dateStr, hourStr) = it.split("#")
            LocalDate.parse(dateStr) to hourStr.toInt()
        }.groupBy({ (date, _) -> date }) { (_, hour) -> hour }
            .mapValues { (_, hours) -> hours.toSet() }

    /** Summe der eingetragenen Stunden von [from] bis [to] (jeweils einschließlich). */
    fun hoursBetween(ctx: Context, from: LocalDate, to: LocalDate): Int =
        all(ctx).filterKeys { !it.isBefore(from) && !it.isAfter(to) }.values.sumOf { it.size }

    fun leadMinutes(ctx: Context): Int = prefs(ctx).getInt(KEY_LEAD, 15)

    fun setLeadMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit { putInt(KEY_LEAD, minutes) }
        BackupManager.triggerAutoBackup(ctx.applicationContext)
    }

    /** Ersetzt slots und lead_minutes in einem Schritt, ohne automatische Sicherung auszulösen. */
    fun restoreData(ctx: Context, slots: Set<String>, leadMinutes: Int) {
        prefs(ctx).edit {
            putStringSet(KEY_SLOTS, slots)
            putInt(KEY_LEAD, leadMinutes)
        }
    }

    fun bundesland(ctx: Context): GermanState {
        val code = prefs(ctx).getString(KEY_BUNDESLAND, GermanState.NW.code) ?: GermanState.NW.code
        return GermanState.fromCode(code)
    }

    fun setBundesland(ctx: Context, state: GermanState) {
        prefs(ctx).edit { putString(KEY_BUNDESLAND, state.code) }
    }
}
