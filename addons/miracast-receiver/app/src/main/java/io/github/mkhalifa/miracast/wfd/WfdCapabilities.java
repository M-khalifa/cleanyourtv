package io.github.mkhalifa.miracast.wfd;

import java.util.Locale;

/** What this sink answers to M3 GET_PARAMETER keys; null means "omit the key". */
public final class WfdCapabilities {
    public static final int RTP_PORT = 19000;

    /** CEA 640x480p60 up to 1920x1080p60, interlaced modes (bits 2, 4, 9, 14) cleared. */
    static final long CEA_BITS = 0x1FFFFL & ~((1L << 2) | (1L << 4) | (1L << 9) | (1L << 14));
    /** Native mode: CEA table (0), index 8 = 1920x1080p60. */
    static final int NATIVE = 8 << 3;
    static final int PROFILE_CBP = 0x01;
    static final int PROFILE_CHP = 0x02;
    static final int LEVEL_4_2 = 0x10;

    private final boolean answerWfdx;

    public WfdCapabilities(boolean answerWfdx) {
        this.answerWfdx = answerWfdx;
    }

    public static String videoFormats() {
        return String.format(Locale.ROOT, "%02x 00 %s, %s", NATIVE, codec(PROFILE_CHP, false), codec(PROFILE_CBP, false));
    }

    public static String videoFormatsX() {
        return String.format(Locale.ROOT, "%04x 00 %s, %s", NATIVE, codec(PROFILE_CHP, true), codec(PROFILE_CBP, true));
    }

    private static String codec(int profile, boolean wide) {
        if (wide) {
            return String.format(Locale.ROOT, "%04x %04x %010x %010x %08x 00 0000 0000 00 none none",
                    profile, LEVEL_4_2, CEA_BITS, 0L, 0L);
        }
        return String.format(Locale.ROOT, "%02x %02x %08x %08x %08x 00 0000 0000 00 none none",
                profile, LEVEL_4_2, CEA_BITS, 0L, 0L);
    }

    /** Value for one requested key, or null to leave it out of the reply. */
    public String answer(String key) {
        switch (key.toLowerCase(Locale.ROOT)) {
            case "wfd_video_formats":
                return videoFormats();
            case "wfdx_video_formats":
                return answerWfdx ? videoFormatsX() : null;
            case "wfd_audio_codecs":
                return "LPCM 00000003 00, AAC 00000001 00";
            case "wfd_client_rtp_ports":
                return "RTP/AVP/UDP;unicast " + RTP_PORT + " 0 mode=play";
            case "wfd_content_protection":
            case "wfd_uibc_capability":
            case "wfd_display_edid":
            case "wfd_coupled_sink":
            case "wfd_standby_resume_capability":
            case "wfd_3d_video_formats":
            case "microsoft_diagnostics_capability":
            case "microsoft_format_change_capability":
            case "microsoft_latency_management_capability":
            case "microsoft_rtcp_capability":
            case "microsoft_color_space_conversion":
            case "microsoft_multiscreen_projection":
            case "microsoft_audio_mute":
            case "microsoft_custom_video_formats":
                return "none";
            case "wfd_connector_type":
                return "05";
            case "wfd_idr_request_capability":
                return "1";
            case "microsoft_max_bitrate":
                return "20000000";
            default:
                return null;
        }
    }
}
