package io.github.mkhalifa.miracast.helper;

import android.content.Context;

/** Shizuku UserService: Shizuku creates it by class name inside its shell-uid process. */
public final class HelperService extends IMiracastHelper.Stub {
    private final HelperCore core;

    public HelperService() {
        core = HelperCore.get();
        HelperLog.i("HelperService created by Shizuku");
    }

    public HelperService(Context context) {
        this();
    }

    @Override
    public void destroy() {
        HelperLog.i("destroy requested");
        core.shutdown();
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                HelperLog.w("destroy wait interrupted", e);
            }
            System.exit(0);
        }).start();
    }

    @Override
    public String status() {
        return core.status();
    }

    @Override
    public void setEnabled(boolean enabled) {
        core.setEnabled(enabled);
    }

    @Override
    public void setName(String name) {
        core.setName(name);
    }

    @Override
    public void setMice(String hostName, int capability) {
        core.setMice(hostName, capability);
    }

    @Override
    public void register(IHelperCallback callback) {
        core.register(callback);
    }

    @Override
    public void removeGroup() {
        core.removeGroup();
    }

    @Override
    public void launchReceiver(String source, int port, String via) {
        core.launchReceiver(source, port, via);
    }

    @Override
    public void setGroupMode(boolean on) {
        core.setGroupMode(on);
    }

    @Override
    public void setSessionActive(boolean active) {
        core.setSessionActive(active);
    }

    @Override
    public int version() {
        return HelperCore.VERSION;
    }
}
