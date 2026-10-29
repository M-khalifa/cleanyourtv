package com.majnioui.tv.prayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.Test;

public class CardProviderTest {
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    private static long at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    @Test
    public void whenSaysTodayTomorrowOrTheDay() {
        long now = at(28, 15, 0);
        assertEquals("Today 9:05 PM", CardProvider.when(at(28, 21, 5), now, ZONE, false));
        assertEquals("Tomorrow 6:00 AM", CardProvider.when(at(29, 6, 0), now, ZONE, false));
        assertEquals("Fri 11:11 AM", CardProvider.when(at(30, 11, 11), now, ZONE, false));
        assertEquals("Fri 11:11", CardProvider.when(at(30, 11, 11), now, ZONE, true));
    }

    @Test
    public void labels() {
        assertEquals("Morning Quran", CardProvider.label(MorningQuran.KIND_MORNING));
        assertEquals("Al-Kahf", CardProvider.label(MorningQuran.KIND_KAHF));
        assertNull(CardProvider.label(-1));
        assertNull("a test recitation is not shown", CardProvider.label(MorningQuran.KIND_TEST));
    }
}
