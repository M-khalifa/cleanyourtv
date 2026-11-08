package io.github.mkhalifa.miracast;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** App log: logcat tag Miracast plus the last lines in memory for the Settings screen. */
public final class SessionLog {
    public static final String TAG = "Miracast";
    private static final int MAX = 200;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();

    private SessionLog() {
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        add(msg);
    }

    public static void w(String msg, Throwable t) {
        Log.w(TAG, msg, t);
        add(msg + (t == null ? "" : ": " + t));
    }

    private static synchronized void add(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        lines.addLast(ts + " " + msg);
        while (lines.size() > MAX) lines.removeFirst();
    }

    /** Newest last. */
    public static synchronized String tail(int n) {
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, lines.size() - n);
        int i = 0;
        for (String l : lines) {
            if (i++ < skip) continue;
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
