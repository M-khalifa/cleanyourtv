package io.github.mkhalifa.miracast;

import android.app.Application;

/** Registers the Shizuku binder listener as early as possible, so a Shizuku server start brings the helper up. */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        HelperClient.get(this);
    }
}
