package com.majnioui.tv.prayer;

import android.app.Activity;
import android.os.Bundle;

/**
 * Test alarms from adb only: the activity needs the DUMP permission, which adb's shell holds and an app cannot.
 * <pre>
 * am start -n com.majnioui.tv.prayer/.TestHooksActivity --ei test_alert_seconds 60 [--ei test_prayer 0]
 * am start -n com.majnioui.tv.prayer/.TestHooksActivity --ei test_quran_seconds 180 [--ei test_quran_surah 108]
 * </pre>
 * The alert test arms the countdown, the banner and the Azan 0, 40 and 80 s after the given delay.
 */
public class TestHooksActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        long now = System.currentTimeMillis();
        int quranIn = getIntent().getIntExtra("test_quran_seconds", 0);
        int surah = getIntent().getIntExtra("test_quran_surah", 0);
        if (quranIn > 0) {
            MorningQuran.arm(this, MorningQuran.TEST_SLOT, now + quranIn * 1000L,
                    surah >= 1 && surah <= 114
                            ? String.format(java.util.Locale.ROOT, "https://server8.mp3quran.net/afs/%03d.mp3", surah) : null,
                    null, -1, MorningQuran.KIND_TEST);
        }
        int testIn = getIntent().getIntExtra("test_alert_seconds", 0);
        int prayer = getIntent().getIntExtra("test_prayer", PrayerTimes.MAGHRIB);
        if (testIn > 0 && prayer >= 0 && prayer < PrayerAlerts.NAMES.length) {
            PrayerAlerts.arm(this, PrayerAlerts.TEST_SLOT, new long[]{now + testIn * 1000L, prayer, PrayerAlerts.SOON});
            PrayerAlerts.arm(this, PrayerAlerts.TEST_SLOT + 1,
                    new long[]{now + (testIn + 40) * 1000L, prayer, PrayerAlerts.BEFORE});
            PrayerAlerts.arm(this, PrayerAlerts.TEST_SLOT + 2,
                    new long[]{now + (testIn + 80) * 1000L, prayer, PrayerAlerts.AT});
        }
        finish();
    }
}
