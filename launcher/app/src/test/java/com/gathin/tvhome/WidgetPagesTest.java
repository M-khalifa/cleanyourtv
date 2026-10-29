package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class WidgetPagesTest {
    private static final String[] DEFAULTS = {"prayer", "weather"};
    private static final String NEWS = "app:com.gathin.newsdream";

    @Test
    public void nothingSavedTakesTheDefaults() {
        assertEquals(Arrays.asList("prayer", "weather"), WidgetPages.enabled(null, DEFAULTS));
    }

    @Test
    public void savedListKeepsItsOrderAndDropsUnknownAndRepeats() {
        assertEquals(Arrays.asList(NEWS, "adb", "prayer"),
                WidgetPages.enabled(" " + NEWS + ",ADB,bogus,app:not a package," + NEWS + ",prayer", DEFAULTS));
    }

    @Test
    public void emptySavedListMeansAllOff() {
        assertTrue(WidgetPages.enabled("", DEFAULTS).isEmpty());
    }

    @Test
    public void toggleAddsAtTheEndAndRemoves() {
        List<String> on = WidgetPages.toggle(Arrays.asList("prayer", "weather"), NEWS);
        assertEquals(Arrays.asList("prayer", "weather", NEWS), on);
        assertEquals(Arrays.asList("prayer", NEWS), WidgetPages.toggle(on, "weather"));
        assertEquals(on, WidgetPages.toggle(on, "bogus"));
    }

    @Test
    public void moveFirstOnlyMovesAnEnabledSource() {
        assertEquals(Arrays.asList(NEWS, "prayer"), WidgetPages.moveFirst(Arrays.asList("prayer", NEWS), NEWS));
        assertEquals(Arrays.asList("prayer"), WidgetPages.moveFirst(Arrays.asList("prayer"), NEWS));
    }

    @Test
    public void listedShowsEnabledFirstThenBuiltInsThenApps() {
        assertEquals(Arrays.asList("adb", "prayer", "weather", "memory", NEWS),
                WidgetPages.listed(Arrays.asList("adb", "prayer"), Collections.singletonList("com.gathin.newsdream")));
    }

    @Test
    public void saveRoundTrips() {
        List<String> s = Arrays.asList("memory", NEWS, "prayer");
        assertEquals(s, WidgetPages.enabled(WidgetPages.save(s), DEFAULTS));
    }

    @Test
    public void rotationWrapsAndHandlesNoPages() {
        assertEquals(-1, WidgetPages.next(0, 0));
        assertEquals(0, WidgetPages.next(-1, 3));
        assertEquals(1, WidgetPages.next(0, 3));
        assertEquals(0, WidgetPages.next(2, 3));
        assertEquals("a page list that shrank starts over", 0, WidgetPages.next(5, 2));
    }

    @Test
    public void secondsCycleThroughTheChoices() {
        assertEquals(10, WidgetPages.nextSeconds(6));
        assertEquals(6, WidgetPages.nextSeconds(30));
        assertEquals(6, WidgetPages.nextSeconds(7));
        assertEquals(6, WidgetPages.seconds(0));
        assertEquals(15, WidgetPages.seconds(15));
    }

    @Test
    public void wifiBarsByStrength() {
        assertEquals(3, WidgetPages.wifiBars(-55));
        assertEquals(3, WidgetPages.wifiBars(-60));
        assertEquals(2, WidgetPages.wifiBars(-65));
        assertEquals(1, WidgetPages.wifiBars(-75));
        assertEquals(1, WidgetPages.wifiBars(-127));
    }

    @Test
    public void networkIsNoLongerASource() {
        assertEquals(Arrays.asList("prayer"), WidgetPages.enabled("network,prayer", DEFAULTS));
    }

    @Test
    public void adbShowsOnlyWhileOn() {
        assertNull(WidgetPages.adb(false, false));
        assertTrue(WidgetPages.adb(false, true).alert);
        assertTrue(WidgetPages.adb(true, false).meta.contains("Developer options"));
        assertTrue(WidgetPages.adb(true, true).meta.contains("network"));
    }

    @Test
    public void memoryShowsInUseOfTotalAndTurnsRedWhenLow() {
        long gb = 1L << 30;
        assertFalse(WidgetPages.memory(400L << 20, 2 * gb, 5 * gb, 16 * gb).alert);
        assertTrue(WidgetPages.memory(100L << 20, 2 * gb, 5 * gb, 16 * gb).alert);
        assertTrue(WidgetPages.memory(400L << 20, 2 * gb, 500L << 20, 16 * gb).alert);
        WidgetPages.Item m = WidgetPages.memory(717L << 20, 2 * gb, 5 * gb, 16 * gb);
        assertEquals("RAM 1.3 / 2.0 GB used", m.line);
        assertEquals("Storage 11.0 / 16.0 GB used", m.meta);
    }

    @Test
    public void usedStaysInsideTheTotal() {
        assertEquals("300 / 512 MB", WidgetPages.used(300L << 20, 512L << 20));
        assertEquals("0 / 512 MB", WidgetPages.used(-5, 512L << 20));
        assertEquals("2.0 / 2.0 GB", WidgetPages.used(3L << 30, 2L << 30));
    }

    @Test
    public void addOnRowTakesLineThenCaptionAndFallsBackToTheLabel() {
        WidgetPages.Item i = WidgetPages.fromRow("com.gathin.newsdream", "Latest headline", "Talks resume in Cairo",
                "NEWS · BBC", "5 min ago", false, false, "News", "News");
        assertEquals(NEWS, i.source);
        assertEquals("Talks resume in Cairo", i.line);
        assertEquals("news", i.kind);
        WidgetPages.Item c = WidgetPages.fromRow("a.b", "Caption only", null, null, null, true, true, null, "Clock");
        assertEquals("Caption only", c.line);
        String longCaption = "A caption much longer than thirty two characters";
        assertEquals("a caption is cut at the card's 32", 32,
                WidgetPages.fromRow("a.b", longCaption, null, null, null, false, false, null, "X").line.length());
        assertEquals("CLOCK", c.kicker);
        assertEquals("", c.meta);
        assertTrue(c.alert && c.urgent);
        assertNull(WidgetPages.fromRow("a.b", " ", "\n", "K", "M", false, false, "", "X"));
    }

    @Test
    public void cleanStripsControlsJoinsSpacesAndCuts() {
        assertEquals("a b c", WidgetPages.clean("  a\n\tb​ ‮c  ", 80));
        assertEquals("abcd", WidgetPages.clean("abcdef", 4));
        assertEquals("Arabic مرحبا stays", WidgetPages.clean("Arabic مرحبا stays", 80));
        String emoji = "ab😀";
        assertEquals("a surrogate pair is never cut in half", "ab", WidgetPages.clean(emoji, 3));
        assertEquals(emoji, WidgetPages.clean(emoji, 4));
        assertEquals("", WidgetPages.clean(null, 10));
    }
}
