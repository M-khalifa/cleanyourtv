package io.github.mkhalifa.miracast.helper;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** System context that identifies itself as com.android.shell (uid 2000), as the framework expects for shell calls. */
final class ShellContext extends ContextWrapper {
    static final String SHELL = "com.android.shell";
    private final AttributionSource source;

    private ShellContext(Context base) {
        super(base);
        source = new AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL).build();
    }

    @Override
    public String getPackageName() {
        return SHELL;
    }

    @Override
    public String getOpPackageName() {
        return SHELL;
    }

    @Override
    public AttributionSource getAttributionSource() {
        return source;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    /** Uses the process's ActivityThread when there is one (Shizuku), otherwise creates one (plain app_process). */
    static ShellContext create() throws ReflectiveOperationException {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        if (thread == null) {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            Constructor<?> ctor = at.getDeclaredConstructor();
            ctor.setAccessible(true);
            thread = ctor.newInstance();
            Field current = at.getDeclaredField("sCurrentActivityThread");
            current.setAccessible(true);
            current.set(null, thread);
            HelperLog.i("created ActivityThread for app_process");
        }
        Context system = (Context) at.getDeclaredMethod("getSystemContext").invoke(thread);
        return new ShellContext(system);
    }
}
