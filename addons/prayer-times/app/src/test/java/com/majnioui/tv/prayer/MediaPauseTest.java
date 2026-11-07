package com.majnioui.tv.prayer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.session.PlaybackState;

import org.junit.Test;

public class MediaPauseTest {

    @Test
    public void playingStatesArePaused() {
        assertTrue(MediaPause.playing(PlaybackState.STATE_PLAYING));
        assertTrue(MediaPause.playing(PlaybackState.STATE_BUFFERING));
        assertTrue(MediaPause.playing(PlaybackState.STATE_FAST_FORWARDING));
        assertTrue(MediaPause.playing(PlaybackState.STATE_REWINDING));
    }

    @Test
    public void stoppedOrPausedSessionsAreLeftAlone() {
        assertFalse(MediaPause.playing(PlaybackState.STATE_PAUSED));
        assertFalse(MediaPause.playing(PlaybackState.STATE_STOPPED));
        assertFalse(MediaPause.playing(PlaybackState.STATE_NONE));
        assertFalse(MediaPause.playing(PlaybackState.STATE_ERROR));
    }
}
