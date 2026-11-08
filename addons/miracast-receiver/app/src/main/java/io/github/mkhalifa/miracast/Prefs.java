package io.github.mkhalifa.miracast;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.UUID;

import io.github.mkhalifa.miracast.mice.WscVendorElement;

/** Settings stored on the TV: on/off, receiver name, MS-MICE container id and capability byte. */
public final class Prefs {
    private final SharedPreferences sp;
    private final Context ctx;

    public Prefs(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("miracast", Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return sp.getBoolean("enabled", true);
    }

    public void setEnabled(boolean on) {
        sp.edit().putBoolean("enabled", on).apply();
    }

    public static final String DEFAULT_NAME = "KhalifaTV";

    /** Name shown in Win+K. */
    public String name() {
        String n = sp.getString("name", null);
        return n != null && !n.trim().isEmpty() ? n.trim() : DEFAULT_NAME;
    }

    public void setName(String n) {
        sp.edit().putString("name", n).apply();
    }

    /** mDNS host label derived from the name: ASCII letters, digits and '-', no dots. */
    public String hostName() {
        return hostLabel(name());
    }

    public static String hostLabel(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        String s = sb.length() == 0 ? DEFAULT_NAME : sb.toString();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    /** Stable GUID for the _display._tcp TXT record. */
    public String containerId() {
        String id = sp.getString("container_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
            sp.edit().putString("container_id", id).apply();
        }
        return id;
    }

    /** Keep an autonomous Wi-Fi Direct group up (for PCs that may connect RTSP over Wi-Fi Direct). */
    public boolean groupMode() {
        return sp.getBoolean("group_mode", false);
    }

    public void setGroupMode(boolean on) {
        sp.edit().putBoolean("group_mode", on).apply();
    }

    public int capability() {
        return sp.getInt("mice_capability", WscVendorElement.CAPABILITY_DEFAULT);
    }

    public void setCapability(int cap) {
        sp.edit().putInt("mice_capability", cap).apply();
    }
}
