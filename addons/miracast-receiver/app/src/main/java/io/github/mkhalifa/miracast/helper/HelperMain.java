package io.github.mkhalifa.miracast.helper;

import android.os.Looper;

import io.github.mkhalifa.miracast.mice.WscVendorElement;

/**
 * adb entry point: CLASSPATH=$(pm path ...) app_process / io.github.mkhalifa.miracast.helper.HelperMain
 * [--name N] [--mice HOST] [--cap 0x05] [--group true]. Runs until killed.
 */
public final class HelperMain {
    private HelperMain() {
    }

    public static void main(String[] args) {
        HelperLog.stdout = true;
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        String name = null;
        String mice = null;
        int cap = WscVendorElement.CAPABILITY_DEFAULT;
        boolean group = false;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--name":
                    name = args[i + 1];
                    break;
                case "--mice":
                    mice = args[i + 1];
                    break;
                case "--group":
                    group = Boolean.parseBoolean(args[i + 1]);
                    break;
                case "--cap":
                    cap = Integer.decode(args[i + 1]);
                    break;
                default:
                    HelperLog.i("unknown argument " + args[i]);
                    break;
            }
        }
        HelperCore core = HelperCore.get();
        if (name != null) core.setName(name);
        if (mice != null) core.setMice(mice, cap);
        core.setGroupMode(group);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> HelperLog.i("helper process exiting")));
        Looper.loop();
    }
}
