package com.majnioui.tv.prayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Function;

public class RecitationTest {
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    /** Dhuhr at 13:11 every day. */
    private static final Function<LocalDate, int[]> TIMES = day -> new int[]{391, 460, 791, 981, 1125, 1193};

    @Test
    public void kahfIsTwoHoursBeforeFridayDhuhr() {
        ZonedDateTime sunday = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, CHICAGO);
        assertEquals("2026-10-30T11:11-05:00[America/Chicago]", MorningQuran.nextKahf(TIMES, 120, sunday).toString());
    }

    @Test
    public void kahfOnFridayAfterItsTimeGoesToNextFriday() {
        ZonedDateTime friday = ZonedDateTime.of(2026, 10, 30, 11, 11, 0, 0, CHICAGO);
        assertEquals("2026-11-06T11:11-06:00[America/Chicago]", MorningQuran.nextKahf(TIMES, 120, friday).toString());
        ZonedDateTime early = ZonedDateTime.of(2026, 10, 30, 6, 0, 0, 0, CHICAGO);
        assertEquals("2026-10-30T11:11-05:00[America/Chicago]", MorningQuran.nextKahf(TIMES, 120, early).toString());
    }

    @Test
    public void ramadan1447IsFebruaryToMarch2026() {
        assertTrue(Hijri.ramadan(LocalDate.of(2026, 3, 1), 0));
        assertFalse(Hijri.ramadan(LocalDate.of(2026, 4, 1), 0));
        assertFalse(Hijri.ramadan(LocalDate.of(2026, 10, 25), 0));
    }

    @Test
    public void offsetMovesTheMonthEdge() {
        LocalDate lastOfShaban = LocalDate.of(2026, 3, 1);
        while (Hijri.ramadan(lastOfShaban, 0)) lastOfShaban = lastOfShaban.minusDays(1);
        assertFalse(Hijri.ramadan(lastOfShaban, 0));
        assertTrue(Hijri.ramadan(lastOfShaban, 1));
    }
}
