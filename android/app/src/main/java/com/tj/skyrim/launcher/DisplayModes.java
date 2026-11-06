package com.tj.skyrim.launcher;

import android.app.Activity;
import android.graphics.Point;
import android.view.Display;

/**
 * The display modes the game accepts (must match bridge_scaled_mode in
 * runtime/d3d9_bridge.cpp: the game only takes a size from this list). One
 * mode per height that keeps the chosen aspect; very wide phones (20:9 and up)
 * get 16:9 or so with black bars instead of a stretched UI.
 */
public final class DisplayModes {

    public static final String[] ASPECTS = {"16:9", "16:10", "4:3", "21:9", "Screen"};

    private static final int[][] ASPECT_VALUES = {{16, 9}, {16, 10}, {4, 3}, {21, 9}, {0, 0}};
    private static final int[] MODE_HEIGHTS =
            {1080, 900, 720, 648, 576, 540, 480, 432, 360, 288, 270, 216, 180, 144, 108};

    private final int screenWidth, screenHeight;

    /** @param screenWidth landscape width, screenHeight landscape height */
    public DisplayModes(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    /** The real landscape size of the activity's display. */
    public static DisplayModes of(Activity activity) {
        Point sz = new Point();
        activity.getWindowManager().getDefaultDisplay().getRealSize(sz);
        return new DisplayModes(Math.max(sz.x, sz.y), Math.min(sz.x, sz.y));
    }

    public int width() { return screenWidth; }
    public int height() { return screenHeight; }

    /** The {numerator, denominator} of an aspect; "Screen" is the display itself. */
    public int[] aspect(int aspectIndex) {
        int[] aspect = ASPECT_VALUES[aspectIndex];
        return aspect[0] == 0 ? new int[] {screenWidth, screenHeight} : aspect;
    }

    /** The raw aspect pair as display.cfg wants it ("Screen" is 0 0; the
     * native side knows what that means). */
    public int[] rawAspect(int aspectIndex) {
        return ASPECT_VALUES[aspectIndex].clone();
    }

    /** The {width, height} list for an aspect, tallest first. */
    public int[][] list(int aspectIndex) {
        int[] aspect = aspect(aspectIndex);
        int top = (int) Math.min(screenHeight, (long) screenWidth * aspect[1] / aspect[0]);
        java.util.ArrayList<int[]> modes = new java.util.ArrayList<>();
        modes.add(new int[] {(int) ((long) top * aspect[0] / aspect[1]) & ~1, top});
        for (int height : MODE_HEIGHTS)
            if (height < top)
                modes.add(new int[] {(int) ((long) height * aspect[0] / aspect[1]) & ~1, height});
        return modes.toArray(new int[0][]);
    }

    /** "960 x 540" style labels for the list of an aspect. */
    public String[] names(int aspectIndex) {
        int[][] modes = list(aspectIndex);
        String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++) names[i] = modes[i][0] + " x " + modes[i][1];
        return names;
    }

    /** The mode an Adreno 610 can manage by default (the tallest <= 540). */
    public int defaultIndex(int aspectIndex) {
        int[][] modes = list(aspectIndex);
        for (int i = 0; i < modes.length; i++) if (modes[i][1] <= 540) return i;
        return modes.length - 1;
    }
}
