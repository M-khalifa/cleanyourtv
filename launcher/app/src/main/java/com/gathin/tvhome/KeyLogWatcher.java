package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * app_keys_from_log: opens app_keys targets with key_filter off, so the firmware keeps auto-repeating a held volume
 * key. Any key filter stops that repeat on TCL Android 12, since the volume step is taken before the filter.
 * TCL's system server logs each key press ("TVodSceneManagerService: key event: keyCode=190, action=0, ..."), and
 * this reads that one tag through logcat (READ_LOGS, granted over adb; Android asks once per log session) and opens
 * the mapped app on the press. Home opens vendor keys (1000 and up) itself while it is in front, so those are
 * skipped then; lower keycodes (YouTube's 190) never reach Home's window without a filter.
 */
final class KeyLogWatcher {
    private static final String TAG = "TvHomeKeyLog";
    private static final Pattern KEY = Pattern.compile("key event: keyCode=(\\d+), action=(\\d+), repeat=(\\d+)");
    private static final long RETRY_MS = 5_000L;

    private final Context ctx;
    private final Map<Integer, String> apps;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private volatile Process process;
    private Thread thread;

    KeyLogWatcher(Context ctx, Map<Integer, String> apps) {
        this.ctx = ctx.getApplicationContext();
        this.apps = apps;
    }

    /** The key code of a first press (action 0, repeat 0) in a logcat line, or -1 for any other line. */
    static int pressedKey(String line) {
        if (line == null) return -1;
        Matcher m = KEY.matcher(line);
        if (!m.find() || !"0".equals(m.group(2)) || !"0".equals(m.group(3))) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    void start() {
        if (apps.isEmpty() || thread != null) return;
        Log.i(TAG, "watching for " + apps.keySet());
        thread = new Thread(this::run, "key-log-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stopped = true;
        Process p = process;
        if (p != null) p.destroy();
        if (thread != null) thread.interrupt();
    }

    private void run() {
        while (!stopped) {
            try {
                // -T 1: only lines from now on, so a restart never replays an old press.
                process = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "TVodSceneManagerService:I", "*:S")
                        .redirectErrorStream(true).start();
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!stopped && (line = in.readLine()) != null) {
                        int key = pressedKey(line);
                        String target = apps.get(key);
                        if (target == null || key >= 1000 && MainActivity.inFront) continue;
                        Log.i(TAG, "key " + key + " opens " + target);
                        main.post(() -> RemoteKeys.launch(ctx, target));
                    }
                }
            } catch (Exception e) {
                if (!stopped) Log.w(TAG, "logcat read failed: " + e);
            } finally {
                Process p = process;
                if (p != null) p.destroy();
            }
            if (stopped) return;
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
