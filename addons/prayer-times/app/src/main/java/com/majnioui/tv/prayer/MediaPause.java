package com.majnioui.tv.prayer;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Pauses what is playing for the Azan and plays it again after. The audio focus alone pauses only apps that honour
 * a transient loss (YouTube); Shorts, Disney+ and most ExoPlayer apps duck or ignore it. So every playing media
 * session is paused through RevivalService, the notification listener approved over adb that may list them, and a
 * media pause key goes to the app that takes media keys, for players with no session. Only sessions are played again:
 * a key pause cannot tell which player it reached, and a play key could start an app that was idle.
 */
final class MediaPause {
    private static final String TAG = "PrayerAlerts";

    /** A session changed later than this after the pause was changed by someone else: it is not played again. */
    private static final long SETTLE_MS = 5_000L;

    private final List<MediaController> paused = new ArrayList<>();
    private long pausedAt;

    /** A session in this state is producing sound, or about to. */
    static boolean playing(int state) {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING;
    }

    /** Pauses every playing session and, if any sound is still playing after that, sends a media pause key. */
    static MediaPause pauseAll(Context ctx) {
        MediaPause p = new MediaPause();
        p.pausedAt = SystemClock.elapsedRealtime();
        MediaSessionManager sessions = ctx.getSystemService(MediaSessionManager.class);
        if (sessions != null) {
            try {
                for (MediaController c : sessions.getActiveSessions(new ComponentName(ctx, RevivalService.class))) {
                    PlaybackState s = c.getPlaybackState();
                    if (s == null || !playing(s.getState())) continue;
                    c.getTransportControls().pause();
                    p.paused.add(c);
                }
            } catch (SecurityException e) {
                Log.w(TAG, "media sessions not readable (notification listener off)", e);
            }
        }
        AudioManager audio = ctx.getSystemService(AudioManager.class);
        boolean key = audio != null && audio.isMusicActive();
        if (key) sendKey(audio, KeyEvent.KEYCODE_MEDIA_PAUSE);
        EventLog.i(ctx, TAG, "paused for the Azan: " + p.paused.size() + " session(s)" + (key ? " and a media key" : ""));
        return p;
    }

    /** Whether a session paused at {@code pausedAt} still shows only that pause: paused, last changed around then. */
    static boolean stillOurPause(int state, long lastUpdate, long pausedAt) {
        return state == PlaybackState.STATE_PAUSED && lastUpdate <= pausedAt + SETTLE_MS;
    }

    /** Plays again the sessions pauseAll paused that nobody paused, stopped or played since. */
    void resume() {
        for (MediaController c : paused) {
            try {
                PlaybackState s = c.getPlaybackState();
                if (s != null && stillOurPause(s.getState(), s.getLastPositionUpdateTime(), pausedAt)) {
                    c.getTransportControls().play();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "could not resume " + c.getPackageName(), e);
            }
        }
        paused.clear();
    }

    private static void sendKey(AudioManager audio, int code) {
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }
}
