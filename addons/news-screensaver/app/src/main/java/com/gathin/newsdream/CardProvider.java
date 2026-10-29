package com.gathin.newsdream;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The live row of this app's module card (cleanyourtv docs/specs/widget-source.md): one of the newest cached
 * headlines, a different one every half minute, one row or none. A stale or missing cache is refreshed in the background, so a read never waits for the
 * network. Only public headlines leave the app, never the feed list; any app on the TV can read this.
 */
public class CardProvider extends ContentProvider {
    private static final String[] COLUMNS = {"caption", "line", "kicker", "meta", "kind"};
    private static final AtomicBoolean refreshing = new AtomicBoolean();
    /** After a refresh starts, the next one waits this long, so failing feeds are not fetched on every read. */
    static final long RETRY_MS = 5 * 60_000L;
    private static volatile long lastTry;
    /** How many of the newest headlines take turns, and how long each one stays. */
    static final int TURNS = 10;
    static final long TURN_MS = 30_000L;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        File dir = getContext().getFilesDir();
        List<Headline> cached = News.load(dir);
        long now = System.currentTimeMillis();
        long age = now - News.cacheTime(dir);
        if ((cached.isEmpty() || age >= News.REFRESH_MS || age < 0) && due(lastTry, now)) {
            lastTry = now;
            refresh(dir);
        }
        Headline h = pick(cached, now);
        if (h == null) return c;
        String source = h.source == null ? "" : h.source.trim();
        c.addRow(new Object[]{caption(h.title), h.title,
                source.isEmpty() ? "NEWS" : "NEWS · " + source.toUpperCase(Locale.ROOT),
                h.time > 0 ? ago(now - h.time) : "", "news"});
        return c;
    }

    /** A refresh may start: none started yet, or the last one RETRY_MS ago (or the clock went back). */
    static boolean due(long lastTry, long now) {
        return lastTry == 0 || now - lastTry >= RETRY_MS || now < lastTry;
    }

    /** The newest TURNS headlines in turn, by the clock: each read in the same half minute gets the same one. */
    static Headline pick(List<Headline> cached, long now) {
        List<Headline> newest = new ArrayList<>(cached);
        newest.sort((a, b) -> Long.compare(b.time, a.time));
        int n = Math.min(TURNS, newest.size());
        return n == 0 ? null : newest.get((int) (Math.max(0, now) / TURN_MS % n));
    }

    /** The card caption's own rule: letters, digits, space and . &amp; + ' - only, at most 32 characters. */
    static String caption(String title) {
        StringBuilder b = new StringBuilder();
        for (char ch : (title == null ? "" : title).toCharArray()) {
            boolean ok = ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || " .&+'-".indexOf(ch) >= 0;
            if (ok && !(ch == ' ' && (b.length() == 0 || b.charAt(b.length() - 1) == ' '))) b.append(ch);
            if (b.length() == 32) break;
        }
        return b.toString().trim();
    }

    static String ago(long millis) {
        long minutes = Math.max(0, millis / 60_000L);
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        return hours < 48 ? hours + " h ago" : hours / 24 + " days ago";
    }

    private static void refresh(File dir) {
        List<String> feeds = Config.feeds(dir);
        if (feeds.isEmpty() || !refreshing.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                List<Headline> fresh = News.fetch(feeds, System.currentTimeMillis());
                if (!fresh.isEmpty()) News.save(dir, fresh);
            } finally {
                refreshing.set(false);
            }
        }, "card-refresh").start();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
