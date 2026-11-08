package io.github.mkhalifa.miracast.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VideoDecoderTest {
    @Test
    public void newDecoderOnlyWhenTheSpsSizeChanges() {
        H264.SpsInfo info = new H264.SpsInfo();
        info.width = 1920;
        info.height = 1080;
        assertEquals(false, VideoDecoder.needsNewDecoder(info, 1920, 1080));
        assertEquals(true, VideoDecoder.needsNewDecoder(info, 0, 0));
        assertEquals(true, VideoDecoder.needsNewDecoder(info, 1920, 1088));
        info.width = 3840;
        info.height = 2160;
        assertEquals(true, VideoDecoder.needsNewDecoder(info, 1920, 1080));
        assertEquals("unparsed SPS keeps the decoder", false, VideoDecoder.needsNewDecoder(null, 1920, 1080));
    }
}
