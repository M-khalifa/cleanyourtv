package io.github.mkhalifa.miracast.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AudioTimingTest {
    @Test
    public void queuedFramesSurviveThePlaybackHeadWrap() {
        long written = (1L << 32) + 100;
        assertEquals(50, AudioRenderer.queuedFrames(written, 50));
        assertEquals(100, AudioRenderer.queuedFrames(0xFFFFFFF0L + 100, 0xFFFFFFF0));
        assertEquals(480, AudioRenderer.queuedFrames(48_480, 48_000));
    }

    @Test
    public void audioDelayFollowsVideoInBoundedSteps() {
        assertEquals("first delay is applied at once", 60, AudioRenderer.delayStepMs(0, 60));
        assertEquals("small change ignored", 0, AudioRenderer.delayStepMs(60, 60 + AudioRenderer.DELAY_STEP_MS - 1));
        assertEquals("shrink once a step is reached", -AudioRenderer.DELAY_STEP_MS,
                AudioRenderer.delayStepMs(60, 60 - AudioRenderer.DELAY_STEP_MS));
        assertEquals("capped", AudioRenderer.MAX_AV_DELAY_MS, AudioRenderer.delayStepMs(0, 10_000));
        assertEquals("instant mode drops the delay", -60, AudioRenderer.delayStepMs(60, 0));
        assertEquals(0, AudioRenderer.delayStepMs(0, -5));
    }

    @Test
    public void audioDelayCapMatchesTheVideoPacerCap() {
        assertEquals(FramePacer.MAX_DELAY_NS / 1_000_000L, AudioRenderer.MAX_AV_DELAY_MS);
    }
}
