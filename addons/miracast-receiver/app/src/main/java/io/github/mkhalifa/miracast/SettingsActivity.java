package io.github.mkhalifa.miracast;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** D-pad settings: on/off, receiver name, Shizuku grant, helper status and the last session log. */
public final class SettingsActivity extends Activity implements HelperClient.Listener {
    private static final int[] CAPABILITIES = {0x05, 0x0A, 0x48};
    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextView status;
    private TextView log;
    private Button toggle;
    private Button capability;
    private Button groupMode;
    private EditText name;
    private String helperStatus = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(64, 40, 64, 40);
        col.setBackgroundColor(Color.rgb(16, 20, 28));

        TextView title = text(getString(R.string.app_name), 30);
        col.addView(title);
        status = text("", 16);
        col.addView(status);

        toggle = button("", v -> {
            prefs.setEnabled(!prefs.enabled());
            applyEnabled();
            refresh();
        });
        col.addView(toggle);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        name = new EditText(this);
        name.setText(prefs.name());
        name.setTextColor(Color.WHITE);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nameRow.addView(button(getString(R.string.save_name), v -> saveName()));
        col.addView(nameRow);

        col.addView(button(getString(R.string.grant_shizuku), v -> {
            HelperClient.get(this).requestPermission();
            HelperClient.get(this).bindIfAllowed();
        }));
        col.addView(button(getString(R.string.restart_helper), v -> {
            HelperClient c = HelperClient.get(this);
            c.stopHelper();
            main.postDelayed(c::bindIfAllowed, 1_000);
        }));
        groupMode = button("", v -> {
            prefs.setGroupMode(!prefs.groupMode());
            HelperClient.get(this).pushConfig();
            refresh();
        });
        col.addView(groupMode);
        capability = button("", v -> {
            int cur = prefs.capability();
            int next = CAPABILITIES[0];
            for (int i = 0; i < CAPABILITIES.length; i++) {
                if (CAPABILITIES[i] == cur) next = CAPABILITIES[(i + 1) % CAPABILITIES.length];
            }
            prefs.setCapability(next);
            HelperClient.get(this).pushConfig();
            refresh();
        });
        col.addView(capability);

        col.addView(text(getString(R.string.last_log), 18));
        log = text("", 12);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        col.addView(log);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 20, 28));
        scroll.addView(col);
        setContentView(scroll);
        toggle.requestFocus();
        applyEnabled();
    }

    private void applyEnabled() {
        HelperClient c = HelperClient.get(this);
        if (prefs.enabled()) {
            SessionService.listen(this);
            c.bindIfAllowed();
        } else {
            SessionService.disable(this);
        }
        c.pushConfig();
    }

    private void saveName() {
        String n = name.getText().toString().trim();
        prefs.setName(n.isEmpty() ? Prefs.DEFAULT_NAME : n);
        name.setText(prefs.name());
        SessionLog.i("name set to " + prefs.name() + " (host " + prefs.hostName() + ")");
        HelperClient.get(this).pushConfig();
        if (prefs.enabled()) SessionService.listen(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HelperClient.get(this).addListener(this);
        tick.run();
    }

    @Override
    protected void onPause() {
        HelperClient.get(this).removeListener(this);
        main.removeCallbacks(tick);
        super.onPause();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, 2_000);
        }
    };

    @Override
    public void onHelperStatus(String s) {
        helperStatus = s;
        refresh();
    }

    private void refresh() {
        String shizuku = !HelperClient.shizukuRunning() ? getString(R.string.shizuku_off)
                : HelperClient.shizukuGranted() ? getString(R.string.shizuku_ok) : getString(R.string.shizuku_ungranted);
        status.setText(getString(R.string.status_line, prefs.name(), prefs.hostName(), shizuku) + "\n" + helperStatus.trim());
        toggle.setText(prefs.enabled() ? R.string.turn_off : R.string.turn_on);
        groupMode.setText(prefs.groupMode() ? R.string.group_mode_on : R.string.group_mode_off);
        capability.setText(getString(R.string.capability, String.format(Locale.ROOT, "0x%02X", prefs.capability())));
        log.setText(SessionLog.tail(40));
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, 8, 0, 8);
        return t;
    }

    private Button button(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        b.setFocusable(true);
        return b;
    }
}
