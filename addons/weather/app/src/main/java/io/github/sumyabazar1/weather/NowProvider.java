package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only weather now for a home-screen widget: one row from the last fetch. A read finding it older than
 * 30 minutes starts a refresh in the background, so the next read is fresh. Nothing here is private: the
 * chosen city's name and its public weather.
 */
public class NowProvider extends ContentProvider {
    static final String PREFS = "now";
    private static final String TAG = "WeatherNow";
    private static final long STALE_MS = 30 * 60_000L;
    private static final String[] COLUMNS = {"place", "temp_c", "feels_c", "sky", "code", "day", "aqi", "updated_ms"};
    private final AtomicBoolean refreshing = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        SharedPreferences now = getContext().getSharedPreferences(PREFS, 0);
        long updated = now.getLong("updated_ms", 0);
        if (System.currentTimeMillis() - updated > STALE_MS) refresh();
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        if (updated > 0) {
            c.addRow(new Object[]{now.getString("place", ""), now.getInt("temp_c", 0), now.getInt("feels_c", 0),
                    now.getString("sky", ""), now.getInt("code", 0), now.getBoolean("day", true) ? 1 : 0,
                    now.getInt("aqi", -1), updated});
        }
        return c;
    }

    private void refresh() {
        SharedPreferences place = getContext().getSharedPreferences(WeatherActivity.PREFS, 0);
        if (!place.contains("lat") || !refreshing.compareAndSet(false, true)) return;
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        String name = place.getString("name", "");
        new Thread(() -> {
            try {
                save(getContext(), name, Forecast.fetch(lat, lon, Forecast.forecastDays(3)));
            } catch (Exception e) {
                Log.w(TAG, "refresh failed", e);
            } finally {
                refreshing.set(false);
            }
        }, "weather-now").start();
    }

    /** Keeps the latest report for the widget; the app calls this too after each fetch it shows. */
    static void save(android.content.Context ctx, String place, Forecast.Report r) {
        ctx.getSharedPreferences(PREFS, 0).edit()
                .putString("place", place)
                .putInt("temp_c", (int) Math.round(r.tempC))
                .putInt("feels_c", (int) Math.round(r.feelsC))
                .putString("sky", ctx.getString(Forecast.sky(r.code, r.day)))
                .putInt("code", r.code)
                .putBoolean("day", r.day)
                .putInt("aqi", r.aqi)
                .putLong("updated_ms", System.currentTimeMillis())
                .apply();
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
