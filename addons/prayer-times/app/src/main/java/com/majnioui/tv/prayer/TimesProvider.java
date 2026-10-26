package com.majnioui.tv.prayer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Read-only daily prayer times for the home screen, the same ones the alerts use: one row per day for today and
 * tomorrow, minutes after midnight (stored Aladhan times, else the on-TV calculation). No rows until a location is
 * set. Also, on each row: ramadan (1 when Ramadan wording is on and it is Ramadan) and hijri (the date's text when
 * "Hijri date on Home" is on, else null) and alerts (1 when this add-on's countdown pill and banners are on). The
 * location itself is never exposed: any app on the TV can read this.
 */
public class TimesProvider extends ContentProvider {
    private static final String[] COLUMNS = {"date", "fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha",
            "ramadan", "hijri", "alerts"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(COLUMNS, 2);
        Options o = Options.load(getContext());
        if (!o.hasLocation()) return c;
        boolean pill = o.alerts && getContext().getResources().getInteger(R.integer.alert_soon_minutes) > 0
                && android.provider.Settings.canDrawOverlays(getContext());
        java.util.function.Function<LocalDate, int[]> times = PrayerAlerts.times(getContext(), java.time.ZoneId.systemDefault());
        for (int d = 0; d < 2; d++) {
            LocalDate day = LocalDate.now().plusDays(d);
            int[] m = times.apply(day);
            c.addRow(new Object[]{day.format(DateTimeFormatter.ISO_LOCAL_DATE), m[0], m[1], m[2], m[3], m[4], m[5],
                    o.ramadan && Hijri.ramadan(day, o.hijriOffset) ? 1 : 0,
                    o.cardHijri ? Hijri.text(day, o.hijriOffset) : null, pill ? 1 : 0});
        }
        return c;
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
