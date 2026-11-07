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
    public void onlyAnUntouchedPauseIsResumed() {
        assertTrue(MediaPause.stillOurPause(PlaybackState.STATE_PAUSED, 10_200, 10_000));
        assertFalse("paused again by the user later", MediaPause.stillOurPause(PlaybackState.STATE_PAUSED, 40_000, 10_000));
        assertFalse("stopped meanwhile", MediaPause.stillOurPause(PlaybackState.STATE_STOPPED, 10_200, 10_000));
        assertFalse("already playing", MediaPause.stillOurPause(PlaybackState.STATE_PLAYING, 10_200, 10_000));
    }

    @Test
    public void stoppedOrPausedSessionsAreLeftAlone() {
        assertFalse(MediaPause.playing(PlaybackState.STATE_PAUSED));
        assertFalse(MediaPause.playing(PlaybackState.STATE_STOPPED));
        assertFalse(MediaPause.playing(PlaybackState.STATE_NONE));
        assertFalse(MediaPause.playing(PlaybackState.STATE_ERROR));
    }
}
