package com.tj.skyrim.launcher;

/**
 * The options the user picked, frozen as they will be written to the game's
 * files. Indexes mean the same as in the option rows; the resolution is the
 * concrete mode (width x height), not the row index.
 */
public final class LauncherChoices {

    final int preset;          /* Detail: 0 Extra low .. 4 Ultra */
    final int aspect;          /* DisplayModes.ASPECTS index */
    final int width, height;   /* the selected display mode */
    final int antialiasing;    /* x1 x2 x4 x8 */
    final int anisotropy;      /* x1 x2 x4 x8 x16 */
    final int textureQuality;  /* Low Medium High */
    final int shadowDetail;    /* Low Medium High Ultra */
    final int decals;          /* None Low Medium High */
    final int blur;            /* radial blur level 0..2 */
    final int fade;            /* object fade 0..3 */
    final int frameCap;        /* 0 = off, else 30/60/120 fps */
    final int language;        /* index into the language list */

    final boolean fxaa;
    final boolean vsync;
    final boolean depthOfField;
    final boolean disableShadows;
    final boolean teofis;             /* disable end-of-frame post-processing */
    final boolean disableTreeAnimations;
    final boolean asyncRendering;     /* compile shaders on background threads */
    final boolean sustainedPerformance;
    final boolean showFps;
    final boolean nativeUi;      /* draw the UI at the display's resolution */
    final boolean touchControls;
    final boolean nintendoLayout;

    LauncherChoices(int preset, int aspect, int width, int height,
                    int antialiasing, int anisotropy, int textureQuality, int shadowDetail,
                    int decals, int blur, int fade, int frameCap, int language,
                    boolean fxaa, boolean vsync, boolean depthOfField, boolean disableShadows,
                    boolean teofis, boolean disableTreeAnimations, boolean asyncRendering,
                    boolean sustainedPerformance,
                    boolean showFps, boolean nativeUi, boolean touchControls, boolean nintendoLayout) {
        this.preset = preset;
        this.aspect = aspect;
        this.width = width;
        this.height = height;
        this.antialiasing = antialiasing;
        this.anisotropy = anisotropy;
        this.textureQuality = textureQuality;
        this.shadowDetail = shadowDetail;
        this.decals = decals;
        this.blur = blur;
        this.fade = fade;
        this.frameCap = frameCap;
        this.language = language;
        this.fxaa = fxaa;
        this.vsync = vsync;
        this.depthOfField = depthOfField;
        this.disableShadows = disableShadows;
        this.teofis = teofis;
        this.disableTreeAnimations = disableTreeAnimations;
        this.asyncRendering = asyncRendering;
        this.sustainedPerformance = sustainedPerformance;
        this.showFps = showFps;
        this.nativeUi = nativeUi;
        this.touchControls = touchControls;
        this.nintendoLayout = nintendoLayout;
    }
}
