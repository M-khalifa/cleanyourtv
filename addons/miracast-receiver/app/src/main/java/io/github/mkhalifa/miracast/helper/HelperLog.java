package io.github.mkhalifa.miracast.helper;

import android.util.Log;

/** Helper logging: logcat tag MiracastHelper, plus stdout when run from an adb shell. */
public final class HelperLog {
    public static final String TAG = "MiracastHelper";
    static volatile boolean stdout;

    private HelperLog() {
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        if (stdout) {
            System.out.println(System.currentTimeMillis() % 1_000_000 + " " + msg);
            System.out.flush();
        }
    }

    public static void w(String msg, Throwable t) {
        Log.w(TAG, msg, t);
        if (stdout) {
            System.out.println(System.currentTimeMillis() % 1_000_000 + " WARN " + msg + (t == null ? "" : ": " + t));
            System.out.flush();
        }
    }
}
