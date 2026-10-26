package com.majnioui.tv.prayer;

import android.app.Activity;
import android.os.Build;
import android.view.WindowManager;

/** Turning the screen on from an activity, on Android 8.0 too (the activity methods came in 8.1). */
final class Screen {
    private Screen() {
    }

    /** The accessibility lock-screen action that puts the TV back came in Android 9: without it, never wake it. */
    static boolean canPutBack() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    @SuppressWarnings("deprecation")
    static void turnOn(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            a.setShowWhenLocked(true);
            a.setTurnScreenOn(true);
        } else {
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }
}
