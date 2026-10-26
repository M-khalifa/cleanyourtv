package com.majnioui.tv.prayer;

import java.time.LocalDate;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;

/**
 * The Hijri date from Java's Umm al-Qura calendar, moved by config.xml hijri_offset_days where the local mosque
 * starts a month a day earlier or later. Used for Ramadan wording.
 */
final class Hijri {
    static final int RAMADAN = 9;

    private Hijri() {
    }

    /** Hijri month (1 to 12) of {@code day}. */
    static int month(LocalDate day, int offsetDays) {
        return HijrahDate.from(day.plusDays(offsetDays)).get(ChronoField.MONTH_OF_YEAR);
    }

    static boolean ramadan(LocalDate day, int offsetDays) {
        return month(day, offsetDays) == RAMADAN;
    }

    static final String[] MONTHS = {"Muharram", "Safar", "Rabi al-Awwal", "Rabi al-Thani", "Jumada al-Ula",
            "Jumada al-Akhirah", "Rajab", "Shaban", "Ramadan", "Shawwal", "Dhu al-Qadah", "Dhu al-Hijjah"};

    /** "14 Jumada al-Ula 1448". */
    static String text(LocalDate day, int offsetDays) {
        HijrahDate h = HijrahDate.from(day.plusDays(offsetDays));
        return h.get(ChronoField.DAY_OF_MONTH) + " " + MONTHS[h.get(ChronoField.MONTH_OF_YEAR) - 1] + " "
                + h.get(ChronoField.YEAR_OF_ERA);
    }
}
