package com.majnioui.tv.prayer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Full screen recitation for a recitation alarm: turns the screen on, sets the media volume and streams the surah,
 * retrying while the network comes back after standby. It never plays over the Azan. Every way it ends (finished,
 * Stop, Back, failed, the TV turned off, another app taking the sound) goes through onDestroy, where Handover
 * says which volume to give back and whether to put the TV back to standby.
 */
public class QuranActivity extends Activity {
    private static final String TAG = "MorningQuran";
    private static final long RETRY_MS = 10_000L;
    private static final int MAX_TRIES = 12;
    /** A prepare that has not answered by then counts as a failed try. */
    private static final long PREPARE_TIMEOUT_MS = 60_000L;
    /** How long "could not reach the recitation" stays before the screen closes. */
    private static final long GIVE_UP_SHOW_MS = 30_000L;
    private static final String EXTRA_CARRY_WOKE = "carry_woke";
    private static final String EXTRA_CARRY_KEY = "carry_key";
    private static final String EXTRA_CARRY_VOLUME = "carry_volume";
    /** The recitation on screen, for the Azan to hand the sound back to. */
    private static QuranActivity current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable prepareTimeout = this::prepareTimedOut;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            // Turned off with the remote: the recitation stops with the TV.
            EventLog.i(c, TAG, "TV turned off during the recitation");
            Handover.STATE.recitationKeyPressed();
            finish();
        }
    };
    private MediaPlayer player;
    private AudioManager audio;
    private AudioAttributes attrs;
    private AudioFocusRequest focus;
    private TextView status;
    private ProgressBar progress;
    private int tries;
    private boolean prepared;
    /** Paused or not started because the Azan (or another app) has the sound. */
    private boolean waitingForSound;
    /** Registered with Handover: its end is reported once, from onDestroy. */
    private boolean started;
    /** A takeover recreate carries this recitation's state to the next instance instead of ending it. */
    private boolean handedOver;
    /** Where a stream that failed mid-way carries on from, in ms. */
    private int resumeAt;
    /** Played to its end: the TV goes to standby after a warning even if it was on or a key was pressed. */
    private boolean playedOut;
    private boolean keyLogged;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        audio = getSystemService(AudioManager.class);
        long at = getIntent().getLongExtra(MorningQuran.EXTRA_AT, System.currentTimeMillis());
        // Recreated by the system (a configuration change) while playing: Handover still holds this recitation.
        boolean resumed = state != null && Handover.STATE.recitationActive();
        VolumeGuard.reconcile(this);
        if (!resumed) {
            // Before the screen turns on: was the TV in standby, or woken a few minutes ago by the early wake?
            boolean screenOff = !getSystemService(PowerManager.class).isInteractive();
            boolean woke = PreWakeActivity.consumeWoke(this, at) | screenOff
                    | getIntent().getBooleanExtra(EXTRA_CARRY_WOKE, false);
            PreWakeActivity.close(at);
            MorningQuran.schedule(this);
            SleepService.ensureEnabled(this);
            String skip = skipReason(getIntent());
            if (skip == null && screenOff && !Screen.canPutBack()) skip = "TV in standby; this Android cannot put it back";
            if (skip != null) {
                EventLog.i(this, TAG, "recitation skipped: " + skip);
                finish();
                // A running Azan puts the TV back at its own end instead.
                if (Handover.STATE.standby(woke)) SleepService.sleep(this);
                return;
            }
            Handover.STATE.recitationStart(woke, getIntent().getBooleanExtra(EXTRA_CARRY_KEY, false),
                    getIntent().getIntExtra(EXTRA_CARRY_VOLUME, -1));
            EventLog.i(this, TAG, "alarm fired " + (System.currentTimeMillis() - at) / 1000 + " s after its time"
                    + (woke ? ", TV woken" : ""));
        }
        started = true;
        current = this;
        // Only now: a skipped recitation never lights the screen.
        Screen.turnOn(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(6, 18, 14));
        String title = getIntent().getStringExtra(MorningQuran.EXTRA_TITLE);
        root.addView(line(title != null ? title : getString(R.string.morning_quran_title), 44, Color.WHITE));
        root.addView(line(getString(R.string.morning_quran_reciter), 22, 0xB3FFFFFF));
        status = line("", 18, 0xFF2BB673);
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams((int) (640 * dp), (int) (8 * dp));
        plp.topMargin = (int) (28 * dp);
        root.addView(progress, plp);
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setAllCaps(false);
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        stop.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(26 * dp);
        bg.setColor(0xFF2BB673);
        stop.setBackground(bg);
        stop.setPadding((int) (48 * dp), 0, (int) (48 * dp), 0);
        stop.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (56 * dp));
        blp.topMargin = (int) (36 * dp);
        root.addView(stop, blp);
        setContentView(root);
        stop.requestFocus();

        attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .build();
        start();
    }

    /** Why a recitation alarm should not play: switched off, or late; null to play. */
    private String skipReason(Intent i) {
        long at = i.getLongExtra(MorningQuran.EXTRA_AT, System.currentTimeMillis());
        if (!MorningQuran.enabled(Options.load(this), i.getIntExtra(MorningQuran.EXTRA_KIND, MorningQuran.KIND_MORNING))) {
            return "switched off";
        }
        return System.currentTimeMillis() - at > MorningQuran.LATE_MS ? "late (the TV was asleep)" : null;
    }

    /** The Azan has ended: a recitation that waited for it starts or carries on. */
    static void azanFinished() {
        QuranActivity q = current;
        if (q != null && !q.closing() && q.prepared && q.waitingForSound) q.play();
    }

    /** Finished but not destroyed yet: nothing may start playing any more. */
    private boolean closing() {
        return isFinishing() || isDestroyed();
    }

    /** Starts or resumes playback, but never over the Azan; asks for the sound first. */
    private void play() {
        if (player == null || !prepared || closing()) return;
        if (!Handover.STATE.recitationMayPlay()) {
            waitingForSound = true;
            status.setText("After the Azan…");
            return;
        }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "no sound for the recitation: closed");
            finish();
            return;
        }
        waitingForSound = false;
        status.setText("");
        int volume = getIntent().getIntExtra(MorningQuran.EXTRA_VOLUME, -1);
        int set = Handover.STATE.recitationPlay(volume >= 0 ? volume : getResources().getInteger(R.integer.morning_quran_volume),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        if (set != WakePlan.KEEP) {
            setVolume(set);
            EventLog.i(this, TAG, "volume " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + " of "
                    + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        }
        if (!player.isPlaying()) player.start();
    }

    /** Another sound takes the focus: pause for the Azan, close for good for another app. */
    private void onFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            // Another app took the sound for good, also while loading or retrying: someone watches something else.
            EventLog.i(this, TAG, "another app took the sound: closed");
            Handover.STATE.recitationKeyPressed();
            finish();
            return;
        }
        if (player == null || !prepared || closing()) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (waitingForSound) {
                EventLog.i(this, TAG, "sound back: carrying on");
                play();
            }
        } else if (player.isPlaying()) {
            player.pause();
            waitingForSound = true;
            EventLog.i(this, TAG, "paused: another sound has the focus");
        }
    }

    private void setVolume(int volume) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    Math.min(volume, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
        } catch (SecurityException e) {
            Log.w(TAG, "could not set the volume", e);
        }
    }

    private void start() {
        tries++;
        status.setText(tries == 1 ? "Loading…" : "Waiting for the network…");
        player = new MediaPlayer();
        player.setAudioAttributes(attrs);
        player.setOnPreparedListener(mp -> {
            main.removeCallbacks(prepareTimeout);
            prepared = true;
            tries = 0;
            if (resumeAt > 0) mp.seekTo(resumeAt);
            EventLog.i(this, TAG, "loaded, " + mp.getDuration() / 60_000 + " min" + (resumeAt > 0 ? ", from " + resumeAt / 1000 + " s" : ""));
            play();
            tick();
        });
        player.setOnCompletionListener(mp -> {
            EventLog.i(this, TAG, "recitation finished");
            playedOut = true;
            finish();
        });
        player.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "playback error " + what + "/" + extra + ", try " + tries);
            retryOrGiveUp();
            return true;
        });
        try {
            String url = getIntent().getStringExtra(MorningQuran.EXTRA_URL);
            player.setDataSource(url != null ? url : getString(R.string.morning_quran_url));
            player.prepareAsync();
            main.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "could not open the recitation", e);
            retryOrGiveUp();
        }
    }

    private void prepareTimedOut() {
        Log.w(TAG, "the recitation did not load in " + PREPARE_TIMEOUT_MS / 1000 + " s, try " + tries);
        retryOrGiveUp();
    }

    private void retryOrGiveUp() {
        main.removeCallbacks(prepareTimeout);
        if (prepared && player != null) {
            try {
                resumeAt = player.getCurrentPosition();
            } catch (IllegalStateException e) {
                Log.w(TAG, "no position to carry on from", e);
            }
        }
        release();
        if (closing()) return;
        if (tries < MAX_TRIES) {
            main.postDelayed(this::start, RETRY_MS);
            return;
        }
        status.setText("Could not reach the recitation. Check the network.");
        main.postDelayed(this::finish, GIVE_UP_SHOW_MS);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && started) {
            if (!keyLogged) EventLog.i(this, TAG, "key " + KeyEvent.keyCodeToString(event.getKeyCode()) + ": someone is here");
            keyLogged = true;
            Handover.STATE.recitationKeyPressed();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int kind = intent.getIntExtra(MorningQuran.EXTRA_KIND, MorningQuran.KIND_MORNING);
        if (!started || kind == getIntent().getIntExtra(MorningQuran.EXTRA_KIND, MorningQuran.KIND_MORNING)) {
            EventLog.i(this, TAG, "already playing");
            return;
        }
        String skip = skipReason(intent);
        if (skip != null) {
            // An alarm that would not play anyway never interrupts the one playing.
            EventLog.i(this, TAG, "next recitation skipped (" + skip + "): this one carries on");
            return;
        }
        // Another recitation's alarm (Friday's Al-Kahf during a long morning one) takes over, keeping whether this
        // woke the TV, whether someone pressed a key, and the volume to restore at the very end.
        EventLog.i(this, TAG, "next recitation takes over");
        Object[] carry = Handover.STATE.recitationHandover();
        handedOver = true;
        setIntent(intent.putExtra(EXTRA_CARRY_WOKE, (Boolean) carry[0]).putExtra(EXTRA_CARRY_KEY, (Boolean) carry[1])
                .putExtra(EXTRA_CARRY_VOLUME, (Integer) carry[2]));
        recreate();
    }

    private void tick() {
        if (player == null) return;
        try {
            int d = player.getDuration();
            if (d > 0) progress.setProgress((int) (1000L * player.getCurrentPosition() / d));
        } catch (IllegalStateException e) {
            return;
        }
        main.postDelayed(this::tick, 5_000L);
    }

    private TextView line(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, 10, 0, 10);
        return v;
    }

    private void release() {
        prepared = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        release();
        if (focus != null) audio.abandonAudioFocusRequest(focus);
        if (current == this) current = null;
        if (started) {
            unregisterReceiver(screenOff);
            if (!handedOver && !isChangingConfigurations()) {
                Handover.Step step = Handover.STATE.recitationEnd();
                if (step.volume != WakePlan.KEEP) setVolume(step.volume);
                EventLog.i(this, TAG, "recitation ended" + (playedOut ? " at its end" : " early")
                        + (step.sleep ? ", standby" : playedOut && Screen.canPutBack() ? ", standby warning" : ", TV stays on"));
                if (step.sleep) SleepService.sleep(getApplicationContext());
                else if (playedOut && Screen.canPutBack()) StandbyWarningActivity.show(getApplicationContext());
            }
        }
        super.onDestroy();
    }
}
