package io.github.mkhalifa.miracast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the listener after boot or an update; the helper follows when Shizuku's binder arrives. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SessionLog.i("broadcast " + i.getAction());
        if (!new Prefs(c).enabled()) return;
        SessionService.listen(c);
        HelperClient.get(c).bindIfAllowed();
    }
}
