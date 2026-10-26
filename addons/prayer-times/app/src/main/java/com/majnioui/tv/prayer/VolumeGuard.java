package com.majnioui.tv.prayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.Log;

/**
 * Keeps Handover's "volume to give back" in SharedPreferences while an Azan or recitation has changed the volume,
 * so a process death (a force-stop, a low-memory kill) cannot leave the TV at 2 or 5 for good. On the next start
 * with nothing playing, the user's volume comes back, but only if the volume is still the level the event set:
 * a volume someone changed since is theirs.
 */
final class VolumeGuard implements Handover.Store {
    private static final String TAG = "PrayerAlerts";
    private static final String PREFS = "volume_guard";
    private static final String RESTORE = "restore", SET = "set";

    private final SharedPreferences prefs;

    private VolumeGuard(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Before an Azan or recitation reports to Handover. */
    static void attach(Context c) {
        if (Handover.STATE.store == null) Handover.STATE.store = new VolumeGuard(c);
    }

    /** At a start of the process with no event playing: gives back a volume a dead process left changed. */
    static void reconcile(Context c) {
        attach(c);
        if (Handover.STATE.azanPlaying() || Handover.STATE.recitationActive()) return;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int restore = p.getInt(RESTORE, -1), set = p.getInt(SET, -1);
        if (restore < 0) return;
        p.edit().clear().apply();
        AudioManager audio = c.getSystemService(AudioManager.class);
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != set) return;
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0);
            Log.i(TAG, "volume given back after an interrupted Azan or recitation: " + restore);
        } catch (SecurityException e) {
            Log.w(TAG, "could not give the volume back", e);
        }
    }

    @Override
    public void save(int restore, int set) {
        prefs.edit().putInt(RESTORE, restore).putInt(SET, set).apply();
    }

    @Override
    public void clear() {
        prefs.edit().clear().apply();
    }
}
