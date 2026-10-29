package com.majnioui.tv.prayer;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Today's prayer times for one city from Aladhan (free, no account or key; only the coordinates, the date, the
 * method and the Asr school are sent), with the city and method chosen in Settings (OptionsActivity). The next
 * prayer is highlighted with a countdown.
 */
public class MainActivity extends Activity {
    private static final String TAG = "PrayerTimes";
    private static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final int[] NAME_RES = {
            R.string.prayer_fajr,
            R.string.prayer_sunrise,
            R.string.prayer_dhuhr,
            R.string.prayer_asr,
            R.string.prayer_maghrib,
            R.string.prayer_isha
    };
    private static final String[] ARABIC = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final int[] ICONS = {R.drawable.ic_fajr, R.drawable.ic_sunrise, R.drawable.ic_dhuhr,
            R.drawable.ic_asr, R.drawable.ic_maghrib, R.drawable.ic_isha};
    private static final long MINUTE_MS = 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Typeface font;
    private int accent;
    private TextView dates, next, status, place;
    private LinearLayout rows;
    /** Today's times as minutes after midnight, in NAMES order; null until loaded. */
    private int[] minutes;
    private String[] times;
    /** The calculation method's name as Aladhan gives it, for the status line. */
    private String methodName = "";
    /** The day the times are for (yyyy-MM-dd), to reload after midnight. */
    private String loadedFor;
    /** The location and method the shown times are for; another one clears them first. */
    private String shownFingerprint;
    private boolean loading;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // A failed fetch leaves loadedFor unset, so the next minute tries again.
            if (!today().equals(loadedFor)) load();
            else render();
            main.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Off the main thread; adb test alarms are in TestHooksActivity.
        PrayerAlerts.rearm(this);
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {(accent & 0x00FFFFFF) | 0x55000000, 0xFF050607, 0xFF050607}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(80), dp(48), dp(80), dp(40));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        place = text(18, 400, 0xB3FFFFFF);
        place.setLetterSpacing(0.25f);
        col.addView(place);

        next = text(52, 300, 0xFFFFFFFF);
        next.setPadding(0, dp(10), 0, dp(4));
        col.addView(next);
        dates = text(20, 400, 0xB3FFFFFF);
        col.addView(dates);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        rlp.setMargins(0, dp(36), 0, 0);
        col.addView(rows, rlp);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(18), 0, 0);
        status = text(16, 400, 0x80FFFFFF);
        status.setText(getString(R.string.loading));
        foot.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView settings = text(18, 500, 0xFFFFFFFF);
        settings.setText("Settings");
        settings.setPadding(dp(26), dp(10), dp(26), dp(10));
        settings.setFocusable(true);
        settings.setClickable(true);
        android.graphics.drawable.StateListDrawable sbg = new android.graphics.drawable.StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(22));
        on.setColor(accent);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(dp(22));
        off.setColor(0x26FFFFFF);
        sbg.addState(new int[]{android.R.attr.state_focused}, on);
        sbg.addState(new int[]{}, off);
        settings.setBackground(sbg);
        settings.setOnClickListener(v -> startActivity(new android.content.Intent(this, OptionsActivity.class)));
        foot.addView(settings);
        col.addView(foot);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        main.postDelayed(tick, MINUTE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    private void load() {
        if (loading) return;
        Options o = Options.load(this);
        if (!o.hasLocation()) {
            place.setText("NO CITY YET");
            next.setText("Choose your city");
            dates.setText("");
            rows.removeAllViews();
            minutes = null;
            status.setText("Open Settings to choose the city and the calculation method.");
            return;
        }
        place.setText(o.place.toUpperCase(Locale.ROOT));
        final String fp = o.fingerprint();
        if (!fp.equals(shownFingerprint)) {
            // Another city or method: the TV's own calculation at once, Aladhan's when it answers.
            showCalculated(o);
            shownFingerprint = fp;
            loadedFor = null;
        }
        loading = true;
        final String day = today();
        final java.time.LocalDate date = java.time.LocalDate.now();
        String url = DailyTimes.url(o, date);
        new Thread(() -> {
            JSONObject json = null;
            try {
                json = new JSONObject(fetch(url));
            } catch (Exception e) {
                Log.w(TAG, "fetch: " + e);
            }
            final JSONObject result = json;
            main.post(() -> {
                loading = false;
                if (result == null) {
                    status.setText(getString(R.string.offline));
                    return;
                }
                if (!fp.equals(Options.load(this).fingerprint())) {
                    // The city or method changed while this was on its way: fetch for the new one.
                    load();
                    return;
                }
                try {
                    parse(result.getJSONObject("data"), fp, date);
                    loadedFor = day;
                    if (getResources().getBoolean(R.bool.reminders)) {
                        PrayerScheduler.saveTimingsAndSchedule(MainActivity.this, day, NAMES, times, minutes, getString(R.string.place));
                    } else {
                        PrayerScheduler.cancel(MainActivity.this);
                    }
                    render();
                } catch (Exception e) {
                    Log.w(TAG, "parse: " + e);
                    status.setText(getString(R.string.unexpected_error));
                }
            });
        }).start();
    }

    private void parse(JSONObject data, String fingerprint, java.time.LocalDate date) throws Exception {
        JSONObject t = data.getJSONObject("timings");
        int[] m = new int[NAMES.length];
        String[] s = new String[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            String v = t.getString(NAMES[i]).trim();
            int sp = v.indexOf(' ');
            if (sp > 0) v = v.substring(0, sp);   // "05:12 (+01)" style suffixes
            s[i] = v;
            m[i] = Integer.parseInt(v.substring(0, 2)) * 60 + Integer.parseInt(v.substring(3, 5));
        }
        minutes = m;
        times = s;
        DailyTimes.save(this, date, fingerprint, m);
        JSONObject meta = data.optJSONObject("meta");
        JSONObject method = meta != null ? meta.optJSONObject("method") : null;
        methodName = method != null ? method.optString("name", "") : "";
        showDates();
    }

    /** Today's date and the Hijri date with the adjustment from Settings, the same as the Home card's. */
    private void showDates() {
        dates.setText(new SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH).format(new Date()) + "  ·  "
                + Hijri.text(java.time.LocalDate.now(), Options.load(this).hijriOffset) + " AH");
    }

    private void showCalculated(Options o) {
        int[] m = PrayerAlerts.times(this, java.time.ZoneId.systemDefault()).apply(java.time.LocalDate.now());
        String[] s = new String[m.length];
        for (int i = 0; i < m.length; i++) s[i] = String.format(Locale.ROOT, "%02d:%02d", m[i] / 60, m[i] % 60);
        minutes = m;
        times = s;
        methodName = Options.methodName(o.method) + " (calculated on the TV)";
        showDates();
        render();
    }

    private void render() {
        if (minutes == null) return;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int nextIdx = -1;
        for (int i = 0; i < NAMES.length; i++) {
            if (i == 1) continue;   // sunrise is shown but is not a prayer
            if (minutes[i] > now) { nextIdx = i; break; }
        }
        if (nextIdx >= 0) {
            int left = minutes[nextIdx] - now;
            String timeStr = (left >= 60 ? left / 60 + " h " : "") + left % 60 + " min";
            next.setText(getString(R.string.prayer_in, getString(NAME_RES[nextIdx]), timeStr));
        } else {
            next.setText(getString(R.string.prayer_tomorrow, getString(NAME_RES[0])));
        }

        rows.removeAllViews();
        for (int i = 0; i < NAMES.length; i++) {
            boolean hl = i == nextIdx;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(6), dp(20), dp(6), dp(20));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(hl ? accent : 0x14FFFFFF);
            card.setBackground(bg);
            int dim = i == 1 ? 0x80FFFFFF : 0xFFFFFFFF;
            int fg = hl ? 0xFFFFFFFF : dim;
            ImageView icon = new ImageView(this);
            icon.setImageResource(ICONS[i]);
            icon.setImageTintList(ColorStateList.valueOf(hl ? 0xFFFFFFFF : (i == 1 ? 0x80FFFFFF : accent)));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
            ilp.bottomMargin = dp(22);
            card.addView(icon, ilp);
            TextView ar = centered(text(26, 400, fg));
            ar.setText(ARABIC[i]);
            card.addView(ar);
            TextView name = centered(text(20, 500, hl ? 0xE6FFFFFF : (i == 1 ? 0x80FFFFFF : 0xB3FFFFFF)));
            name.setText(getString(NAME_RES[i]));
            name.setPadding(0, dp(8), 0, dp(20));
            card.addView(name);
            TextView time = centered(text(42, hl ? 500 : 300, fg));
            time.setText(times[i]);
            // Shrink rather than cut "05:10" when the card is narrow.
            time.setMaxLines(1);
            time.setAutoSizeTextTypeUniformWithConfiguration(20, 42, 1, TypedValue.COMPLEX_UNIT_SP);
            card.addView(time, new LinearLayout.LayoutParams(-1, dp(52)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(i == 0 ? 0 : dp(8), 0, i == NAMES.length - 1 ? 0 : dp(8), 0);
            rows.addView(card, lp);
        }
        boolean reminders = getResources().getBoolean(R.bool.reminders);
        String updated = getString(R.string.updated_at, new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date()));
        String statusStr = "Aladhan" + (methodName.isEmpty() ? "" : " · " + methodName) + " · " + updated;
        if (reminders) {
            statusStr += " · " + getString(R.string.reminders_active);
        }
        status.setText(statusStr);
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Centred on its own line, whatever the script direction. */
    private static TextView centered(TextView t) {
        t.setGravity(Gravity.CENTER);
        t.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        return t;
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : font);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
