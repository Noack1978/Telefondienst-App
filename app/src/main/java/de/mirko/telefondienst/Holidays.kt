package de.mirko.telefondienst

import java.time.DayOfWeek
import java.time.LocalDate

enum class GermanState(val code: String, val displayName: String) {
    BW("BW", "Baden-Württemberg"),
    BY("BY", "Bayern"),
    BE("BE", "Berlin"),
    BB("BB", "Brandenburg"),
    HB("HB", "Bremen"),
    HH("HH", "Hamburg"),
    HE("HE", "Hessen"),
    MV("MV", "Mecklenburg-Vorpommern"),
    NI("NI", "Niedersachsen"),
    NW("NW", "Nordrhein-Westfalen"),
    RP("RP", "Rheinland-Pfalz"),
    SL("SL", "Saarland"),
    SN("SN", "Sachsen"),
    ST("ST", "Sachsen-Anhalt"),
    SH("SH", "Schleswig-Holstein"),
    TH("TH", "Thüringen");

    companion object {
        fun fromCode(code: String): GermanState =
            entries.find { it.code == code } ?: NW
    }
}

object Holidays {
    fun getEasterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    fun getHolidayName(date: LocalDate, state: GermanState): String? {
        val year = date.year
        val month = date.monthValue
        val day = date.dayOfMonth

        // Feste Feiertage
        if (month == 1 && day == 1) return "Neujahr"
        if (month == 1 && day == 6 && state in setOf(GermanState.BW, GermanState.BY, GermanState.ST)) return "Heilige Drei Könige"
        if (month == 3 && day == 8 && state in setOf(GermanState.BE, GermanState.MV)) return "Internationaler Frauentag"
        if (month == 5 && day == 1) return "Tag der Arbeit"
        if (month == 8 && day == 15 && state in setOf(GermanState.SL, GermanState.BY)) return "Mariä Himmelfahrt"
        if (month == 9 && day == 20 && state == GermanState.TH) return "Weltkindertag"
        if (month == 10 && day == 3) return "Tag der Deutschen Einheit"
        if (month == 10 && day == 31 && state in setOf(GermanState.BB, GermanState.HB, GermanState.HH, GermanState.MV, GermanState.NI, GermanState.SN, GermanState.ST, GermanState.SH, GermanState.TH)) return "Reformationstag"
        if (month == 11 && day == 1 && state in setOf(GermanState.BW, GermanState.BY, GermanState.NW, GermanState.RP, GermanState.SL)) return "Allerheiligen"
        if (month == 12 && day == 25) return "1. Weihnachtstag"
        if (month == 12 && day == 26) return "2. Weihnachtstag"

        // Bewegliche Feiertage (Ostern)
        val easter = getEasterSunday(year)
        if (date == easter.minusDays(2)) return "Karfreitag"
        if (date == easter.plusDays(1)) return "Ostermontag"
        if (date == easter.plusDays(39)) return "Christi Himmelfahrt"
        if (date == easter.plusDays(50)) return "Pfingstmontag"
        if (date == easter.plusDays(60) && state in setOf(GermanState.BW, GermanState.BY, GermanState.HE, GermanState.NW, GermanState.RP, GermanState.SL)) return "Fronleichnam"

        // Buß- und Bettag (Sachsen)
        if (state == GermanState.SN) {
            var bussDay = LocalDate.of(year, 11, 22)
            while (bussDay.dayOfWeek != DayOfWeek.WEDNESDAY) {
                bussDay = bussDay.minusDays(1)
            }
            if (date == bussDay) return "Buß- und Bettag"
        }

        return null
    }

    fun isHoliday(date: LocalDate, state: GermanState): Boolean =
        getHolidayName(date, state) != null
}
