package com.majnioui.tv.prayer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The cleanyourtv Home widget's glass for this app's screens and overlays: a see-through fill, a hairline edge,
 * 24dp corners and Outfit. No blur: the TV cannot blur what is behind a window. Over other apps (video) the fill
 * is dark, so the text stays readable on a bright frame.
 */
final class Glass {
    static final int LIGHT = 0x2EFFFFFF, DARK = 0xD9141418, EDGE = 0x40FFFFFF, INK = 0xFF101014;
    static final float RADIUS = 24;

    private Glass() {
    }

    private static Typeface outfit;

    /** Outfit at {@code weight} (300 light, 400, 500 medium). */
    static Typeface font(Context c, int weight) {
        if (outfit == null) outfit = c.getResources().getFont(R.font.outfit);
        return Build.VERSION.SDK_INT >= 28 ? Typeface.create(outfit, weight, false) : outfit;
    }

    /** The Home widget's light glass: a panel on this app's own dark screens. */
    static GradientDrawable light(float dp) {
        return shape(LIGHT, EDGE, 1, RADIUS * dp, dp);
    }

    /** Dark glass for a window over other apps; {@code accent} 0 keeps the white hairline, else a 2dp accent edge. */
    static GradientDrawable dark(float dp, int accent, float radiusDp) {
        return accent == 0 ? shape(DARK, EDGE, 1, radiusDp * dp, dp) : shape(DARK, accent, 2, radiusDp * dp, dp);
    }

    private static GradientDrawable shape(int fill, int edge, float edgeDp, float radius, float dp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, Math.round(edgeDp * dp)), edge);
        g.setCornerRadius(radius);
        return g;
    }

    /** The launcher's pill button: glass at rest, white with dark text when focused. */
    static void button(TextView b, float dp) {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xF5FFFFFF);
        focused.setCornerRadius(28 * dp);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, shape(LIGHT, EDGE, 1, 28 * dp, dp));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{INK, 0xFFFFFFFF}));
        b.setTypeface(font(b.getContext(), 500));
    }

    /** A centred glass card on the Home-like dark screen; add the content to {@code card}. */
    static View screen(Context c, LinearLayout card) {
        float dp = c.getResources().getDisplayMetrics().density;
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(light(dp));
        card.setPadding((int) (64 * dp), (int) (40 * dp), (int) (64 * dp), (int) (40 * dp));
        FrameLayout root = new FrameLayout(c);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1C1C21, 0xFF0B0B0D}));
        root.addView(card, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return root;
    }
}
