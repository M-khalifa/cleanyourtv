package com.majnioui.tv.prayer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * The live row of this app's module card (cleanyourtv docs/specs/widget-source.md): the next recitation that is
 * switched on and when, or no row. Only the event and its time leave the app, never the location.
 */
public class CardProvider extends ContentProvider {
    private static final String[] COLUMNS = {"caption", "line", "kicker", "meta", "kind"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        long[] next = MorningQuran.nextEvent(getContext());
        String label = label((int) next[1]);
        if (label == null || next[0] <= 0) return c;
        String when = when(next[0], System.currentTimeMillis(), ZoneId.systemDefault(),
                android.text.format.DateFormat.is24HourFormat(getContext()));
        c.addRow(new Object[]{"Next recitation", label, "NEXT ON THIS TV", when, "event"});
        return c;
    }

    static String label(int kind) {
        return kind == MorningQuran.KIND_KAHF ? "Al-Kahf" : kind == MorningQuran.KIND_MORNING ? "Morning Quran" : null;
    }

    /** "Today 6:00 AM", "Tomorrow 6:00 AM" or "Fri 11:11 AM". */
    static String when(long at, long now, ZoneId zone, boolean clock24) {
        ZonedDateTime t = Instant.ofEpochMilli(at).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        String time = t.format(DateTimeFormatter.ofPattern(clock24 ? "H:mm" : "h:mm a", Locale.US));
        long days = ChronoUnit.DAYS.between(today, t.toLocalDate());
        if (days == 0) return "Today " + time;
        if (days == 1) return "Tomorrow " + time;
        return t.format(DateTimeFormatter.ofPattern("EEE", Locale.US)) + " " + time;
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
