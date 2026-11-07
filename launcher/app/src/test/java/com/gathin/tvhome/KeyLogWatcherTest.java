package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KeyLogWatcherTest {

    @Test
    public void readsTheFirstPress() {
        assertEquals(190, KeyLogWatcher.pressedKey(
                "I/TVodSceneManagerService( 1042): key event: keyCode=190, action=0, repeat=0, policyFlags=570425345"));
        assertEquals(4020, KeyLogWatcher.pressedKey("key event: keyCode=4020, action=0, repeat=0, policyFlags=1"));
    }

    @Test
    public void ignoresReleasesRepeatsAndOtherLines() {
        assertEquals(-1, KeyLogWatcher.pressedKey("key event: keyCode=190, action=1, repeat=0, policyFlags=1"));
        assertEquals(-1, KeyLogWatcher.pressedKey("key event: keyCode=24, action=0, repeat=3, policyFlags=1"));
        assertEquals(-1, KeyLogWatcher.pressedKey("I/TclShortCutKeyManager( 1042): sDownPressMap dont have behave:190"));
        assertEquals(-1, KeyLogWatcher.pressedKey(null));
    }
}
