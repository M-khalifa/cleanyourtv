package com.majnioui.tv.prayer;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logcat plus a file that outlives it: events.log in the app's external files folder, readable with
 * {@code adb shell cat /sdcard/Android/data/com.majnioui.tv.prayer/files/events.log}. The TV's logcat rolls over
 * in about 20 minutes, too soon to explain a morning that went wrong.
 */
final class EventLog {
    static final String NAME = "events.log";
    static final int MAX_BYTES = 64 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private EventLog() {
    }

    static void i(Context c, String tag, String msg) {
        Log.i(tag, msg);
        append(c, tag, msg);
    }

    static void w(Context c, String tag, String msg) {
        Log.w(tag, msg);
        append(c, tag, msg);
    }

    private static synchronized void append(Context c, String tag, String msg) {
        File dir = c.getApplicationContext().getExternalFilesDir(null);
        if (dir == null) return;
        try {
            add(new File(dir, NAME), LocalDateTime.now().format(TIME) + " " + tag + " " + msg + "\n");
        } catch (IOException e) {
            Log.w(tag, "could not write " + NAME, e);
        }
    }

    /** Appends {@code line}; past MAX_BYTES the older half goes, cut at a line break. */
    static void add(File f, String line) throws IOException {
        if (f.length() > MAX_BYTES) {
            byte[] all = Files.readAllBytes(f.toPath());
            int from = all.length - MAX_BYTES / 2;
            while (from < all.length && all[from - 1] != '\n') from++;
            byte[] keep = new byte[all.length - from];
            System.arraycopy(all, from, keep, 0, keep.length);
            Files.write(f.toPath(), keep);
        }
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }
    }
}
