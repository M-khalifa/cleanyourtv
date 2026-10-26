package com.majnioui.tv.prayer;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The Azan recording the user chose. The add-on ships none: it is downloaded from an address typed in Settings,
 * or copied over adb to Android/data/com.majnioui.tv.prayer/files/azan.mp3. Without it there is no Azan.
 */
final class AzanSound {
    private static final String TAG = "PrayerTimes";
    static final String NAME = "azan.mp3";
    /** Enough for a few minutes of high-bitrate audio; anything bigger is not an Azan. */
    static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final long MIN_BYTES = 1024;

    private AzanSound() {
    }

    static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        return new File(dir != null ? dir : c.getFilesDir(), NAME);
    }

    static boolean ready(File f) {
        return f.isFile() && f.length() >= MIN_BYTES;
    }

    static boolean ready(Context c) {
        return ready(file(c));
    }

    /** Downloads {@code address} over the current sound, keeping the old one unless the new file plays. */
    static void download(Context c, String address) throws Exception {
        File target = file(c);
        File part = new File(target.getParentFile(), NAME + ".part");
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
        http.setConnectTimeout(15_000);
        http.setReadTimeout(30_000);
        http.setInstanceFollowRedirects(true);
        // Wikimedia and some CDNs refuse requests that do not name the client.
        http.setRequestProperty("User-Agent", "PrayerTimes-TV/1.0 (cleanyourtv add-on)");
        try {
            int code = http.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + code);
            long total = 0;
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_BYTES) throw new Exception("larger than " + MAX_BYTES / (1024 * 1024) + " MB");
                    out.write(buf, 0, n);
                }
            }
            if (!ready(part)) throw new Exception("empty file");
            int seconds = playableSeconds(part);
            if (!part.renameTo(target)) {
                if (!target.delete() || !part.renameTo(target)) throw new Exception("could not replace " + NAME);
            }
            Log.i(TAG, "Azan sound saved, " + total / 1024 + " KB, " + seconds + " s");
        } finally {
            http.disconnect();
            if (part.exists() && !part.delete()) Log.w(TAG, "could not delete " + part);
        }
    }

    /** Length of {@code f} in seconds; throws when the TV cannot play it. */
    private static int playableSeconds(File f) throws Exception {
        MediaPlayer p = new MediaPlayer();
        try {
            p.setDataSource(f.getPath());
            p.prepare();
            return p.getDuration() / 1000;
        } catch (Exception e) {
            throw new Exception("not a sound this TV can play");
        } finally {
            p.release();
        }
    }
}
