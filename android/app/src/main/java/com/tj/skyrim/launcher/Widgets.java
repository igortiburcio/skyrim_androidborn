package com.tj.skyrim.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * The launcher's design system: Dusklight's structure with a Skyrim skin.
 * Cold night surfaces, parchment text and one muted gold accent; Alegreya SC
 * and Fira Sans typography; flat rows with a gold focus ring; and the
 * prelaunch menu that fills with a gold wipe when focused. Everything is
 * drawn in code (no resources), so the same classes build every variant.
 */
public final class Widgets {

    /* ---- palette: cold night, parchment, muted gold ---- */
    public static final int TEXT = 0xFFDCD5C2;
    public static final int TEXT_DIM = 0xFF9BA1A6;
    public static final int GOLD = 0xFFC9A961;
    public static final int GOLD_BRIGHT = 0xFFE8CE93;
    public static final int GOLD_DEEP = 0xFF8C6B2F;
    public static final int BORDER = 0xFF6B6350;
    public static final int SURFACE = 0xF212161B;      /* the settings window */
    public static final int OK_COLOR = 0xFFA6C989;
    public static final int BAD_COLOR = 0xFFD98C7A;
    public static final int WARN_COLOR = 0xFFE3B96B;
    private static final int OVER_GOLD = 0xFF14100A;   /* text over the gold wipe */

    private static final int RADIUS_DP = 14;

    private final Activity activity;
    private final Fonts fonts;

    public Widgets(Activity activity) {
        this.activity = activity;
        this.fonts = new Fonts(activity);
    }

    public Fonts fonts() { return fonts; }
    public Context context() { return activity; }
    public int textColor() { return TEXT; }
    public int hintColor() { return TEXT_DIM; }
    public int okColor() { return OK_COLOR; }
    public int badColor() { return BAD_COLOR; }
    public int warnColor() { return WARN_COLOR; }

    public int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                activity.getResources().getDisplayMetrics());
    }

    /** Fullscreen, immersive frame; the backdrop view paints the rest. */
    public void applyWindow() {
        View decor = activity.getWindow().getDecorView();  /* installs the decor */
        if (Build.VERSION.SDK_INT >= 30) {
            /* The insets controller owns the layout on Android 15; the legacy
             * flags alone leave the content inside the system bars (black
             * strips top and bottom). getInsetsController() is only valid once
             * the decor view exists, hence the decor grab above and the guard. */
            try {
                activity.getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c = activity.getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } catch (Throwable ignored) {
            }
        }
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /* ---- prelaunch ---- */

    /** The wordmark: SKYRIM in small caps with a gold gradient. */
    public TextView wordmark(LinearLayout parent) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText("SKYRIM");
        title.setTypeface(fonts.display);
        title.setTextSize(36);
        title.setLetterSpacing(0.05f);
        title.setShadowLayer(dp(8), 0, 0, 0x66000000);
        title.getPaint().setShader(new LinearGradient(0, 0, 0, dp(40),
                new int[] {0xFFF2DFAA, GOLD, GOLD_DEEP}, new float[] {0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        block.addView(title);

        TextView subtitle = new TextView(activity);
        subtitle.setText("ANDROIDBORN");
        subtitle.setTypeface(fonts.heading);
        subtitle.setTextSize(10);
        subtitle.setLetterSpacing(0.30f);
        subtitle.setTextColor(TEXT_DIM);
        subtitle.setPadding(dp(3), dp(3), 0, 0);
        block.addView(subtitle);

        parent.addView(block);
        return title;
    }

    /**
     * A big prelaunch menu item: transparent until focused, then the Dusklight
     * gold wipe with dark text. 40dp tall, uppercase, 20sp — sized for a phone
     * in landscape, where five of them plus the wordmark must fit.
     */
    public Button menuButton(LinearLayout parent, String label, View.OnClickListener onClick) {
        Button b = new Button(activity);
        b.setText(label.toUpperCase(Locale.ROOT));
        b.setTypeface(fonts.heading);
        b.setTextSize(20);
        b.setLetterSpacing(0.04f);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setTextColor(menuTextColors());
        b.setBackground(menuBackground());
        b.setOnClickListener(onClick);
        focusable(b);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), dp(40));
        lp.topMargin = dp(5);
        parent.addView(b, lp);
        return b;
    }

    /** Text over the artwork; a soft black glow keeps it readable. */
    public TextView overlayText(String s, int sp) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTypeface(fonts.body);
        t.setTextSize(sp);
        t.setTextColor(TEXT);
        t.setShadowLayer(dp(5), 0, 0, 0xB3000000);
        return t;
    }

    /** The gamepad legend: dim text with the button glyphs in gold. */
    public TextView legend(String s) {
        SpannableString span = new SpannableString(s);
        for (String glyph : new String[] {"\u24B6", "\u24B7", "LB", "RB"}) {
            int i = 0;
            while ((i = span.toString().indexOf(glyph, i)) >= 0) {
                span.setSpan(new ForegroundColorSpan(GOLD), i, i + glyph.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i += glyph.length();
            }
        }
        TextView t = new TextView(activity);
        t.setText(span);
        t.setTypeface(fonts.body);
        t.setTextSize(13);
        t.setLetterSpacing(0.05f);
        t.setTextColor(TEXT_DIM);
        return t;
    }

    /* ---- inside the settings window ---- */

    /** A line of body text inside a pane. */
    public TextView text(LinearLayout parent, String s, int sp, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTypeface(bold ? fonts.bodyBold : fonts.body);
        t.setTextSize(sp);
        t.setTextColor(TEXT);
        parent.addView(t, paneRow());
        return t;
    }

    /** A secondary button (choose folder, install driver zip, ...). */
    public Button actionButton(LinearLayout parent, String label, View.OnClickListener onClick) {
        Button b = new Button(activity);
        b.setText(label);
        b.setTypeface(fonts.body);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackground(fillBackground());
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setOnClickListener(onClick);
        focusable(b);
        parent.addView(b, paneRow());
        return b;
    }

    /** A small button the caller positions itself (the plugin arrows). */
    public Button compactButton(String label, View.OnClickListener onClick) {
        Button b = new Button(activity);
        b.setText(label);
        b.setTypeface(fonts.body);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackground(fillBackground());
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setOnClickListener(onClick);
        focusable(b);
        return b;
    }

    /* ---- focus ---- */

    /**
     * Makes a view usable with a gamepad: focusable, also in touch mode (or
     * requestFocus() does nothing on a phone). The focus look itself comes
     * from each component's background.
     */
    public <T extends View> T focusable(T view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        return view;
    }

    /* ---- shared drawables ---- */

    /**
     * Rows and secondary buttons: a dark translucent fill with a hairline
     * border, and a gold ring over a gold-tinted fill when focused/pressed.
     */
    StateListDrawable fillBackground() {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_pressed},
                rounded(0x4DD8C08A, dp(2), GOLD));
        background.addState(new int[] {android.R.attr.state_focused},
                rounded(0x2ED8C08A, dp(2), GOLD));
        background.addState(new int[] {}, rounded(0x330B0E12, dp(1), 0x406B6350));
        return background;
    }

    private GradientDrawable rounded(int fillColor, int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(RADIUS_DP));
        d.setStroke(strokePx, strokeColor);
        return d;
    }

    /** The prelaunch menu: idle is transparent, focus fills with a gold wipe. */
    private StateListDrawable menuBackground() {
        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(dp(6));
        GradientDrawable wipe = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {GOLD_BRIGHT, 0x00C9A961});
        wipe.setCornerRadius(dp(6));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_pressed}, wipe);
        background.addState(new int[] {android.R.attr.state_focused}, wipe);
        background.addState(new int[] {android.R.attr.state_selected}, wipe);
        background.addState(new int[] {}, idle);
        return background;
    }

    private ColorStateList menuTextColors() {
        return new ColorStateList(new int[][] {
                {android.R.attr.state_pressed},
                {android.R.attr.state_focused},
                {android.R.attr.state_selected},
                {}},
                new int[] {OVER_GOLD, OVER_GOLD, OVER_GOLD, TEXT});
    }

    /** Margins of a row inside a pane (8dp of air between rows). */
    LinearLayout.LayoutParams paneRow() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        return lp;
    }

    /** The version this APK reports, for the wordmark and the corner text. */
    public String versionName() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
