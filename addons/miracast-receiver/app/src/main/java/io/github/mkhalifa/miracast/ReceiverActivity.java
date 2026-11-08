package io.github.mkhalifa.miracast;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Full-screen video for a session. Debug hook:
 * am start -n io.github.mkhalifa.miracast/.ReceiverActivity --es source IP --ei port PORT
 */
public final class ReceiverActivity extends Activity implements SessionService.Ui, SurfaceHolder.Callback {
    private SurfaceView surfaceView;
    private TextView status;
    private SessionService service;
    private SurfaceHolder holder;
    private boolean bound;
    private int videoW = 1920;
    private int videoH = 1080;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(20);
        status.setPadding(48, 48, 48, 48);
        root.addView(status, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
        handleIntent(getIntent());
        bindService(new Intent(this, SessionService.class), conn, Context.BIND_AUTO_CREATE);
        bound = true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        String source = i.getStringExtra("source");
        int port = i.getIntExtra("port", 7236);
        String via = i.getStringExtra("via");
        if (source == null) return;
        status.setText(getString(R.string.connecting, source));
        if (!"mice".equals(via)) SessionService.startSession(this, source, port, via == null ? "debug" : via);
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((SessionService.Local) b).service();
            service.attach(ReceiverActivity.this);
            if (holder != null) service.setSurface(holder.getSurface());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onDestroy() {
        if (service != null) {
            service.setSurface(null);
            service.attach(null);
        }
        if (bound) unbindService(conn);
        bound = false;
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            SessionLog.i("Back pressed: disconnecting");
            if (service != null) service.stopCurrent("Back on the TV");
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        if (service != null) service.setSurface(h.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        holder = null;
        if (service != null) service.setSurface(null);
    }

    @Override
    public void onSessionState(String state) {
        if (state.startsWith("first frame")) status.setVisibility(View.GONE);
        else if (!state.equals("streaming")) status.setText(state);
    }

    @Override
    public void onVideoSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        videoW = width;
        videoH = height;
        fitVideo();
    }

    @Override
    public void onSessionEnded(String reason) {
        SessionLog.i("receiver closing: " + reason);
        finish();
    }

    private void fitVideo() {
        View root = (View) surfaceView.getParent();
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) return;
        int fw = w;
        int fh = (int) ((long) w * videoH / videoW);
        if (fh > h) {
            fh = h;
            fw = (int) ((long) h * videoW / videoH);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width == fw && lp.height == fh) return;
        lp.width = fw;
        lp.height = fh;
        surfaceView.setLayoutParams(lp);
    }
}
