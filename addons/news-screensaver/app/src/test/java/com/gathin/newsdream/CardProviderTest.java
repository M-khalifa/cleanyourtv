package com.gathin.newsdream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class CardProviderTest {
    @Test
    public void captionKeepsOnlyTheCardCharactersAndFitsThirtyTwo() {
        assertEquals("Talks resume in Cairo after a we", CardProvider.caption("Talks resume in Cairo, after a week: what next?"));
        assertEquals("Q3 results beat forecasts", CardProvider.caption("Q3 results beat forecasts!"));
        assertEquals("", CardProvider.caption("مرحبا"));
        assertEquals("", CardProvider.caption(null));
    }

    @Test
    public void agoInWords() {
        assertEquals("Just now", CardProvider.ago(10_000));
        assertEquals("5 min ago", CardProvider.ago(5 * 60_000L));
        assertEquals("3 h ago", CardProvider.ago(3 * 3_600_000L));
        assertEquals("3 days ago", CardProvider.ago(72 * 3_600_000L));
        assertEquals("Just now", CardProvider.ago(-5_000));
    }

    private static Headline at(String title, long time) {
        return new Headline("BBC", title, "", time, "");
    }

    @Test
    public void headlinesTakeTurnsNewestFirst() {
        List<Headline> h = Arrays.asList(at("old", 1), at("newest", 3), at("middle", 2));
        assertEquals("newest", CardProvider.pick(h, 0).title);
        assertEquals("same half minute, same headline", "newest", CardProvider.pick(h, CardProvider.TURN_MS - 1).title);
        assertEquals("middle", CardProvider.pick(h, CardProvider.TURN_MS).title);
        assertEquals("old", CardProvider.pick(h, 2 * CardProvider.TURN_MS).title);
        assertEquals("newest", CardProvider.pick(h, 3 * CardProvider.TURN_MS).title);
        assertNull(CardProvider.pick(new ArrayList<>(), 0));
    }

    @Test
    public void aFailedRefreshWaitsBeforeTheNext() {
        assertTrue(CardProvider.due(0, 1_000));
        assertTrue(!CardProvider.due(1_000, 1_000 + CardProvider.RETRY_MS - 1));
        assertTrue(CardProvider.due(1_000, 1_000 + CardProvider.RETRY_MS));
        assertTrue("clock set back", CardProvider.due(10_000, 5_000));
    }

    @Test
    public void onlyTheNewestTenTakeTurns() {
        List<Headline> h = new ArrayList<>();
        for (int i = 0; i < 15; i++) h.add(at("h" + i, i));
        for (int t = 0; t < 30; t++) {
            assertTrue(Integer.parseInt(CardProvider.pick(h, t * CardProvider.TURN_MS).title.substring(1)) >= 5);
        }
    }
}
