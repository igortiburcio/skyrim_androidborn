package com.tj.skyrim.launcher;

import android.view.View;
import android.widget.LinearLayout;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The "Graphics" tab: every option as a Dusklight-style row — steppers for the
 * choices, On/Off rows for the switches. Picking a preset fills the detail
 * rows, and changing the aspect rebuilds the resolutions while keeping the
 * height; the block snapshots itself into a LauncherChoices for the launch.
 */
public final class OptionsSection implements LauncherWindow.Content {

    private static final String[] PRESETS = {"Extra low", "Low", "Medium", "High", "Ultra"};
    private static final String[] ANTIALIASING = {"Off", "2x", "4x", "8x"};
    private static final String[] ANISOTROPY = {"Off", "2x", "4x", "8x", "16x"};
    private static final String[] TEXTURE_QUALITY = {"Low", "Medium", "High"};
    private static final String[] SHADOW_DETAIL = {"Low", "Medium", "High", "Ultra"};
    private static final String[] DECAL_QUANTITY = {"None", "Low", "Medium", "High"};
    private static final String[] RADIAL_BLUR = {"Low", "Medium", "High"};
    private static final String[] OBJECT_FADE = {"Short", "Medium", "Far", "Very far"};
    private static final String[] FRAME_CAP = {"Off", "30 fps", "60 fps", "120 fps"};
    private static final String[] CAMERA_MODES = {"Joystick", "Touch drag"};
    private static final String[] LANGUAGES =
            {"English", "French", "German", "Italian", "Spanish", "Polish", "Russian", "Japanese"};
    private static final int[] FRAME_CAP_FPS = {0, 30, 60, 120};

    /** What each preset sets on the detail rows (index = preset). */
    private static final int[] PRESET_TEXTURE = {0, 1, 1, 2, 2};
    private static final int[] PRESET_SHADOW = {0, 0, 1, 2, 3};
    private static final int[] PRESET_DECALS = {0, 0, 1, 2, 3};
    private static final int[] PRESET_BLUR = {0, 0, 0, 1, 2};
    private static final int[] PRESET_FADE = {0, 0, 1, 2, 3};
    private static final int[] PRESET_AA = {0, 0, 0, 2, 3};
    private static final int[] PRESET_ANISO = {0, 0, 2, 3, 4};

    private final Widgets widgets;
    private final LauncherSettings settings;
    private final DisplayModes modes;

    private final Map<String, SelectRow> rows = new LinkedHashMap<>();

    public OptionsSection(Widgets widgets, LauncherSettings settings, DisplayModes modes) {
        this.widgets = widgets;
        this.settings = settings;
        this.modes = modes;
    }

    @Override
    public CharSequence title() {
        return "Graphics";
    }

    @Override
    public void build(LinearLayout pane) {
        stepper(pane, "preset", "Detail", PRESETS,
                settings.option("preset", 1), r -> applyPreset(r.selected()));
        SelectRow aspect = stepper(pane, "aspect", "Aspect ratio", DisplayModes.ASPECTS,
                r -> rebuildResolutions(r));
        stepper(pane, "res", "Resolution",
                modes.names(aspect.selected()),
                settings.option("res", modes.defaultIndex(aspect.selected())), null);
        stepper(pane, "aa", "Antialiasing", ANTIALIASING, null);
        stepper(pane, "aniso", "Anisotropic filtering", ANISOTROPY, null);
        stepper(pane, "texq", "Texture quality", TEXTURE_QUALITY, null);
        stepper(pane, "shadow", "Shadow detail", SHADOW_DETAIL, null);
        stepper(pane, "decals", "Decal quantity", DECAL_QUANTITY, null);
        stepper(pane, "blur", "Radial blur quality", RADIAL_BLUR, null);
        stepper(pane, "fade", "Object fade distance", OBJECT_FADE, null);
        stepper(pane, "frcap", "Frame rate cap", FRAME_CAP, null);
        stepper(pane, "lang", "Language", LANGUAGES, null);

        toggle(pane, "fxaa", "FXAA", false);
        toggle(pane, "vsync", "Vertical sync", true);
        toggle(pane, "dof", "Depth of field", true);
        toggle(pane, "noshadows", "Disable shadows", false);
        toggle(pane, "trees", "Disable tree animations", false);
        toggle(pane, "teofis", "Disable post-processing (TEOFIS)", false);
        toggle(pane, "async", "Asynchronous rendering", true);
        toggle(pane, "sustained", "Sustained performance mode", false);
        toggle(pane, "fps", "Show FPS counter", false);
        toggle(pane, "nativeui", "Draw UI at native resolution", false);
        toggle(pane, "touch", "Touch controls", true);
        stepper(pane, "camera", "Camera control", CAMERA_MODES,
                settings.option("camera", 1), null);
        toggle(pane, "nintendo", "Nintendo button layout (A/B, X/Y swapped)", false);

        settings.aspectShown(aspect.selected());
    }

    @Override
    public View initialFocus() {
        return rows.get("preset");
    }

    /** Saves every row (when the activity pauses, and before playing). */
    public void persist() {
        for (Map.Entry<String, SelectRow> row : rows.entrySet()) {
            SelectRow r = row.getValue();
            if (r.isOnOffRow()) settings.setFlag(row.getKey(), r.on());
            else settings.setOption(row.getKey(), r.selected());
        }
    }

    /** The current options, ready to be written to the game's files. The rows
     * normally exist (the window is prepared at startup); if one is missing,
     * the saved setting stands in for it. */
    public LauncherChoices choices() {
        int aspect = value("aspect", 0);
        int[] mode = modes.list(aspect)[value("res", modes.defaultIndex(aspect))];
        return new LauncherChoices(
                value("preset", 1), aspect, mode[0], mode[1],
                value("aa", 0), value("aniso", 0),
                value("texq", 0), value("shadow", 0),
                value("decals", 0), value("blur", 0),
                value("fade", 0),
                FRAME_CAP_FPS[value("frcap", 0)],
                value("lang", 0),
                flag("fxaa", false), flag("vsync", true),
                flag("dof", true), flag("noshadows", false),
                flag("teofis", false), flag("trees", false),
                flag("async", true), flag("sustained", false),
                flag("fps", false), flag("nativeui", false),
                flag("touch", true), flag("nintendo", false), value("camera", 1));
    }

    /* Row value, or the saved setting when the row was never built. */
    private int value(String key, int def) {
        SelectRow row = rows.get(key);
        return row != null ? row.selected() : settings.option(key, def);
    }

    private boolean flag(String key, boolean def) {
        SelectRow row = rows.get(key);
        return row != null ? row.on() : settings.flag(key, def);
    }

    /* ------------------------------------------------------------------ */

    /** Extra low also drops the two expensive toggles. */
    private void applyPreset(int preset) {
        rows.get("texq").select(PRESET_TEXTURE[preset]);
        rows.get("shadow").select(PRESET_SHADOW[preset]);
        rows.get("decals").select(PRESET_DECALS[preset]);
        rows.get("blur").select(PRESET_BLUR[preset]);
        rows.get("fade").select(PRESET_FADE[preset]);
        rows.get("aa").select(PRESET_AA[preset]);
        rows.get("aniso").select(PRESET_ANISO[preset]);
        rows.get("fxaa").set(false);
        rows.get("noshadows").set(preset == 0);
        rows.get("dof").set(preset != 0);
    }

    /** Keeps the current height when the aspect changes. */
    private void rebuildResolutions(SelectRow aspectRow) {
        int newAspect = aspectRow.selected();
        int oldHeight = modes.list(settings.aspectShown())[
                Math.max(0, rows.get("res").selected())][1];
        int pick = modes.defaultIndex(newAspect);
        int[][] list = modes.list(newAspect);
        for (int i = 0; i < list.length; i++) if (list[i][1] == oldHeight) pick = i;
        rows.get("res").items(modes.names(newAspect), pick);
        settings.aspectShown(newAspect);
    }

    private SelectRow stepper(LinearLayout pane, String key, String label, String[] items,
                              SelectRow.Listener listener) {
        return add(pane, key, SelectRow.stepper(widgets, label, items,
                settings.option(key, 0), listener));
    }

    private SelectRow stepper(LinearLayout pane, String key, String label, String[] items,
                              int selected, SelectRow.Listener listener) {
        return add(pane, key, SelectRow.stepper(widgets, label, items, selected, listener));
    }

    private SelectRow toggle(LinearLayout pane, String key, String label, boolean defaultValue) {
        return add(pane, key, SelectRow.toggle(widgets, label,
                settings.flag(key, defaultValue), null));
    }

    private SelectRow add(LinearLayout pane, String key, SelectRow row) {
        pane.addView(row);
        rows.put(key, row);
        return row;
    }
}
