package io.github.mkhalifa.miracast.helper;

import java.util.ArrayList;
import java.util.List;

/** Parses `ip neigh show dev <iface>` output into IPv4 addresses whose entry is REACHABLE or DELAY. */
public final class IpNeigh {
    private IpNeigh() {
    }

    public static List<String> liveIpv4(String output) {
        List<String> out = new ArrayList<>();
        if (output == null) return out;
        for (String line : output.split("\r?\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 2 || !isIpv4(f[0])) continue;
            String state = f[f.length - 1];
            if ((state.equals("REACHABLE") || state.equals("DELAY")) && !out.contains(f[0])) out.add(f[0]);
        }
        return out;
    }

    static boolean isIpv4(String s) {
        String[] p = s.split("\\.");
        if (p.length != 4) return false;
        for (String x : p) {
            if (x.isEmpty() || x.length() > 3) return false;
            for (int i = 0; i < x.length(); i++) if (!Character.isDigit(x.charAt(i))) return false;
            if (Integer.parseInt(x) > 255) return false;
        }
        return true;
    }
}
