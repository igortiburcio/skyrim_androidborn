package com.tj.skyrim.launcher;

import android.content.Context;
import android.graphics.Typeface;

/**
 * The launcher's typefaces (Alegreya SC for the wordmark, Fira Sans for the
 * UI), loaded from assets/fonts. Every font falls back to a system one, so
 * the launcher still works if the assets are missing (e.g. a build without
 * them); both are SIL OFL, see assets/fonts/LICENSE-*.
 */
public final class Fonts {

    /** The wordmark: small-caps serif with the feel of Skyrim's Trajan. */
    public final Typeface display;
    /** Menu items, tabs and row keys: condensed bold. */
    public final Typeface heading;
    /** Values and body text. */
    public final Typeface body;
    /** Emphasized values. */
    public final Typeface bodyBold;

    public Fonts(Context context) {
        display = load(context, "AlegreyaSC-Bold.ttf", Typeface.SERIF);
        heading = load(context, "FiraSansCondensed-Bold.ttf",
                Typeface.create("sans-serif-condensed", Typeface.BOLD));
        body = load(context, "FiraSans-Regular.ttf", Typeface.SANS_SERIF);
        bodyBold = load(context, "FiraSans-SemiBold.ttf",
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    private static Typeface load(Context context, String file, Typeface fallback) {
        try {
            Typeface t = Typeface.createFromAsset(context.getAssets(), "fonts/" + file);
            return t != null ? t : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
