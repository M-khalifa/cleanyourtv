package com.majnioui.tv.prayer;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings on the TV, with the remote: OK switches a row, Left/Right step its value. The city comes from a search
 * (Open-Meteo's geocoding: only the typed name is sent). Everything beyond the times page starts off unless
 * config.xml says otherwise. Each change re-arms the alarms (debounced, off the main thread).
 */
public class OptionsActivity extends Activity {
    private static final String TAG = "PrayerTimes";
    private static final String[] PRAYER_NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final long REARM_DELAY_MS = 800L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean rearmPending;
    private final Runnable rearm = () -> {
        rearmPending = false;
        PrayerAlerts.rearm(this);
    };
    private final List<Runnable> refreshers = new ArrayList<>();
    private Typeface font;
    private int accent;
    private LinearLayout list;
    private TextView setup;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF050607);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(80), dp(40), dp(80), dp(40));
        scroll.addView(list);
        setContentView(scroll);

        TextView title = text(30, 400, 0xFFFFFFFF);
        title.setText("Prayer Times settings");
        title.setPadding(dp(16), 0, 0, dp(18));
        list.addView(title);

        section("Times");
        row("City", "Search by name; only the name you type is sent", o -> o.hasLocation() ? o.place : "Not set",
                this::searchCity, null);
        row("Calculation method", "As your mosque uses", o -> Options.methodName(o.method), () -> stepMethod(1),
                this::stepMethod);
        row("Asr", "Standard (Shafi, Maliki, Hanbali) or Hanafi", o -> o.school == 1 ? "Hanafi" : "Standard",
                () -> put(Options.SCHOOL, Options.load(this).school == 1 ? 0 : 1), d -> put(Options.SCHOOL,
                        Options.load(this).school == 1 ? 0 : 1));

        section("Reminders");
        toggle("Countdown and banner", "A countdown pill 15 minutes before each prayer and a banner, over any app",
                Options.ALERTS, o -> o.alerts);
        toggle("Azan", "At each prayer: what is playing pauses, the Azan plays, Dismiss stops it", Options.AZAN,
                o -> o.azan);
        for (int p : new int[]{PrayerTimes.FAJR, PrayerTimes.DHUHR, PrayerTimes.ASR, PrayerTimes.MAGHRIB, PrayerTimes.ISHA}) {
            toggle("   Azan at " + PRAYER_NAMES[p], "", Options.AZAN_PRAYER + p, o -> o.azanFor[p]);
        }
        row("Azan sound", "The recording to play: download it from its address, or copy one over adb (README)",
                o -> o.azanSound ? "Ready" : "Not set", this::chooseAzanSound, null);
        row("Test the Azan", "Plays it now, whatever the switches say", o -> "", () -> {
            if (AzanSound.ready(this)) startActivity(AzanActivity.test(this, PrayerTimes.DHUHR));
            else chooseAzanSound();
        }, null);

        section("Recitations");
        row("Morning recitation", "Surat Al-Baqarah every morning; ◀ ▶ change the time",
                o -> o.morning ? o.morningTime : "Off", () -> put(Options.MORNING, !Options.load(this).morning),
                this::stepMorningTime);
        toggle("Friday Al-Kahf", "Two hours before Dhuhr on Fridays", Options.KAHF, o -> o.kahf);

        section("Calendar");
        toggle("Ramadan wording", "In Ramadan, Fajr reads Suhoor ends and Maghrib reads Iftar", Options.RAMADAN,
                o -> o.ramadan);
        toggle("Hijri date on Home", "Next to the countdown on the Clean Your TV home card", Options.CARD_HIJRI,
                o -> o.cardHijri);
        row("Hijri date adjustment", "When your mosque starts the month a day earlier or later",
                o -> (o.hijriOffset > 0 ? "+" : "") + o.hijriOffset + "  ·  " + Hijri.text(LocalDate.now(), o.hijriOffset),
                null, d -> put(Options.HIJRI_OFFSET, Math.max(-2, Math.min(2, Options.load(this).hijriOffset + d))));

        section("Waking the TV");
        setup = text(15, 400, 0xB3FFFFFF);
        setup.setPadding(dp(16), dp(4), dp(16), dp(4));
        list.addView(setup);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // An Azan sound copied over adb since the last visit gets its alarms armed here.
        main.removeCallbacks(rearm);
        rearmPending = true;
        main.postDelayed(rearm, REARM_DELAY_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the screen arms the latest choices at once.
        if (rearmPending) {
            main.removeCallbacks(rearm);
            rearm.run();
        }
    }

    interface Value {
        String of(Options o);
    }

    interface Step {
        void step(int direction);
    }

    private void toggle(String name, String detail, String key, java.util.function.Predicate<Options> on) {
        row(name, detail, o -> on.test(o) ? "On" : "Off", () -> put(key, !on.test(Options.load(this))), null);
    }

    private void row(String name, String detail, Value value, Runnable onClick, Step onStep) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(14));
        focused.setColor((accent & 0x00FFFFFF) | 0x66000000);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, new GradientDrawable());
        r.setBackground(bg);
        r.setFocusable(true);
        r.setClickable(onClick != null);
        if (onClick != null) r.setOnClickListener(v -> onClick.run());
        if (onStep != null) {
            r.setOnKeyListener((v, code, e) -> {
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN) onStep.step(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            });
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(19, 400, 0xFFFFFFFF);
        label.setText(name);
        texts.addView(label);
        if (!detail.isEmpty()) {
            TextView d = text(13, 400, 0x99FFFFFF);
            d.setText(detail);
            texts.addView(d);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = text(19, 500, 0xFFFFFFFF);
        r.addView(v);
        list.addView(r);
        refreshers.add(() -> v.setText(value.of(Options.load(this))));
    }

    private void section(String name) {
        TextView t = text(14, 500, accent);
        t.setText(name.toUpperCase(Locale.ROOT));
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(16), dp(22), 0, dp(6));
        list.addView(t);
    }

    private void put(String key, Object value) {
        Options.put(this, key, value);
        refresh();
        main.removeCallbacks(rearm);
        rearmPending = true;
        main.postDelayed(rearm, REARM_DELAY_MS);
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
        if (setup != null) setup.setText(setupStatus());
    }

    private void stepMethod(int direction) {
        int[] ids = Options.METHOD_IDS;
        int now = Options.load(this).method, at = 0;
        for (int i = 0; i < ids.length; i++) if (ids[i] == now) at = i;
        put(Options.METHOD, ids[Math.floorMod(at + direction, ids.length)]);
    }

    private void stepMorningTime(int direction) {
        Options o = Options.load(this);
        LocalTime t;
        try {
            t = LocalTime.parse(o.morningTime);
        } catch (RuntimeException e) {
            t = LocalTime.of(6, 0);
        }
        put(Options.MORNING_TIME, t.plusMinutes(15L * direction).toString());
        if (!o.morning) put(Options.MORNING, true);
    }

    /** What waking the TV for the Azan and the recitations still needs; all of it is set once over adb (README). */
    private String setupStatus() {
        List<String> missing = new ArrayList<>();
        if (!android.provider.Settings.canDrawOverlays(this)) missing.add("draw over other apps");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !getSystemService(AlarmManager.class).canScheduleExactAlarms()) missing.add("exact alarms");
        String list = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null || !list.contains(getPackageName() + "/")) missing.add("standby service");
        else if (!SleepService.running()) missing.add("standby service (switched on but not running yet)");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && !getSystemService(NotificationManager.class)
                .isNotificationListenerAccessGranted(new ComponentName(this, RevivalService.class))) {
            missing.add("alarm revival");
        }
        Options o = Options.load(this);
        String sound = o.azan && !o.azanSound ? "The Azan is on but has no sound yet: choose one under Azan sound.\n"
                : "";
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return sound + "Putting the TV back to standby needs Android 9 or later; on this TV it stays on afterwards.";
        }
        return sound + (missing.isEmpty()
                ? "Ready: the Azan and the recitations wake the TV from standby and put it back afterwards."
                : "Needs the one-time adb setup in the README: " + String.join(", ", missing) + ".");
    }

    private void chooseAzanSound() {
        EditText address = new EditText(this);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        address.setHint("https://.../azan.mp3");
        address.setText(Options.load(this).azanUrl);
        new AlertDialog.Builder(this)
                .setTitle("Address of an Azan recording (MP3)")
                .setView(address)
                .setPositiveButton("Download", (d, w) -> downloadAzan(address.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void downloadAzan(String url) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        new Thread(() -> {
            String error = null;
            try {
                AzanSound.download(this, url);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Log.w(TAG, "Azan download failed: " + error);
            }
            String failed = error;
            main.post(() -> {
                if (isFinishing()) return;
                if (failed == null) put(Options.AZAN_URL, url);
                new AlertDialog.Builder(this)
                        .setMessage(failed == null ? "Azan sound saved." : "Could not use it: " + failed + ".")
                        .setPositiveButton("OK", null).show();
            });
        }, "azan-download").start();
    }

    private void searchCity() {
        EditText name = new EditText(this);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setSingleLine(true);
        name.setHint("City");
        new AlertDialog.Builder(this)
                .setTitle("Search for your city")
                .setView(name)
                .setPositiveButton("Search", (d, w) -> geocode(name.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void geocode(String query) {
        if (query.isEmpty()) return;
        new Thread(() -> {
            List<String[]> found = new ArrayList<>();
            try {
                JSONObject reply = new JSONObject(fetch("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en"
                        + "&format=json&name=" + URLEncoder.encode(query, "UTF-8")));
                JSONArray results = reply.optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    String admin = r.optString("admin1", ""), country = r.optString("country", "");
                    String label = r.getString("name") + (admin.isEmpty() ? "" : ", " + admin)
                            + (country.isEmpty() ? "" : ", " + country);
                    found.add(new String[]{r.getString("name"), label,
                            Double.toString(r.getDouble("latitude")), Double.toString(r.getDouble("longitude"))});
                }
            } catch (Exception e) {
                Log.w(TAG, "city search failed: " + e.getClass().getSimpleName());
            }
            main.post(() -> pickCity(query, found));
        }, "city-search").start();
    }

    private void pickCity(String query, List<String[]> found) {
        if (isFinishing()) return;
        if (found.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("No city found for \"" + query + "\", or no internet.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[found.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = found.get(i)[1];
        new AlertDialog.Builder(this).setTitle("Choose").setItems(labels, (d, i) -> {
            String[] c = found.get(i);
            Options.put(this, Options.LAT, c[2]);
            Options.put(this, Options.LON, c[3]);
            put(Options.PLACE, c[0]);
        }).show();
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

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : font);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
