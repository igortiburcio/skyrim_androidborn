package com.tj.skyrim;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.tj.skyrim.launcher.BackdropView;
import com.tj.skyrim.launcher.DisplayModes;
import com.tj.skyrim.launcher.DriverRegistry;
import com.tj.skyrim.launcher.DriverSection;
import com.tj.skyrim.launcher.FolderCheck;
import com.tj.skyrim.launcher.FolderSection;
import com.tj.skyrim.launcher.GameConfigWriter;
import com.tj.skyrim.launcher.GameLauncher;
import com.tj.skyrim.launcher.GamepadController;
import com.tj.skyrim.launcher.GamepadController.Host;
import com.tj.skyrim.launcher.LaunchPlan;
import com.tj.skyrim.launcher.LauncherSettings;
import com.tj.skyrim.launcher.LauncherWindow;
import com.tj.skyrim.launcher.OptionsSection;
import com.tj.skyrim.launcher.PluginRegistry;
import com.tj.skyrim.launcher.PluginsSection;
import com.tj.skyrim.launcher.StorageAccess;
import com.tj.skyrim.launcher.Widgets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

/**
 * The prelaunch screen, Dusklight-style: the bundled Skyrim wallpaper (or a
 * Nordic night painted in code) behind a SKYRIM wordmark and a menu on the
 * left, the folder status and version in the bottom-right corner, and a
 * gamepad legend under the menu. Choosing a tab opens the settings window;
 * playing writes what the 0.30 runtime reads and starts SkyrimActivity with
 * the extras it expects. This activity is only the wiring — every block
 * lives in {@link com.tj.skyrim.launcher}.
 */
public class LauncherActivity extends Activity
        implements Host, FolderSection.Listener, DriverSection.Listener, LauncherWindow.Listener {

    private static final int REQUEST_FOLDER = 1;
    private static final int REQUEST_DRIVER_ZIP = 3;

    private LauncherSettings settings;
    private StorageAccess storage;
    private GamepadController gamepad;
    private Widgets widgets;
    private DisplayModes modes;
    private GameConfigWriter configWriter;

    private FolderSection folderSection;
    private PluginsSection pluginsSection;
    private DriverSection driverSection;
    private OptionsSection optionsSection;
    private LauncherWindow window;
    private List<LauncherWindow.Content> tabs;

    private BackdropView backdrop;
    private LinearLayout menuGroup;
    private Button playMenu, folderMenu, pluginsMenu, driverMenu, graphicsMenu;
    private TextView statusText, versionText;

    /* the menu button that opened the window, so closing returns to it */
    private Button windowOpener;

    /* continue once the storage prompt is answered */
    private boolean pendingPick, pendingPlay;
    private boolean askedAllFiles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settings = new LauncherSettings(this);
        storage = new StorageAccess(this);
        widgets = new Widgets(this);
        widgets.applyWindow();
        modes = DisplayModes.of(this);
        gamepad = new GamepadController(this);
        configWriter = new GameConfigWriter(modes);

        backdrop = new BackdropView(this);
        backdrop.setWallpaper(BackdropView.loadAsset(this, "backdrop.jpg"));
        deleteLegacyWallpaper();

        folderSection = new FolderSection(widgets, settings, new FolderCheck(settings), storage, this);
        pluginsSection = new PluginsSection(widgets, new PluginRegistry(settings));
        driverSection = new DriverSection(widgets, new DriverRegistry(settings), this);
        optionsSection = new OptionsSection(widgets, settings, modes);
        tabs = Arrays.asList(folderSection, pluginsSection, driverSection, optionsSection);

        ViewGroup root = buildScreen();
        setContentView(root);
        window = new LauncherWindow(this, widgets, root);
        window.setListener(this);
        /* the panes (and their rows) exist from the start, so Play works even
         * if the settings window was never opened */
        window.prepare(tabs);
        root.post(this::fitMenu);

        refresh();
        focusDefaultControl();
        playMenu.post(this::focusDefaultControlIfLost);
    }

    @Override
    protected void onResume() {
        super.onResume();
        widgets.applyWindow();
        /* safety: the menu must never stay focus-blocked from a window cycle */
        if (!window.isOpen()) menuGroup.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        refresh();
        if (getCurrentFocus() == null && !window.isOpen()) focusDefaultControl();
        menuGroup.post(this::fitMenu);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) widgets.applyWindow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        gamepad.pause();
        pluginsSection.persist();
        optionsSection.persist();
    }

    /* ---- gamepad (GamepadController.Host) ---- */

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return gamepad.dispatchKeyEvent(event);
    }

    /**
     * Motion events enter here before any view sees them, so the stick and a
     * hat D-pad are translated even if a view wanted the event.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return gamepad.onGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event);
    }

    @Override
    public View currentFocus() {
        return getCurrentFocus();
    }

    @Override
    public void focusDefaultControl() {
        (playMenu != null && playMenu.isEnabled() ? playMenu : folderMenu).requestFocus();
    }

    private void focusDefaultControlIfLost() {
        if (getCurrentFocus() == null && !window.isOpen()) focusDefaultControl();
    }

    @Override
    public void goBack() {
        if (window.isOpen()) window.close();
        else if (!isFinishing()) onBackPressed();
    }

    @Override
    public void onBackPressed() {
        if (window != null && window.isOpen()) {
            window.close();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean passThrough(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean previousTab() {
        return window.previous();
    }

    @Override
    public boolean nextTab() {
        return window.next();
    }

    /* ---- the settings window ---- */

    @Override
    public void onWindowOpened(int tabIndex) {
        /* the D-pad must not escape the window into the menu behind it */
        menuGroup.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    @Override
    public void onWindowClosed(int tabIndex) {
        menuGroup.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        Button menu = windowOpener != null ? windowOpener : sectionMenu(tabIndex);
        windowOpener = null;
        if (menu != null) menu.requestFocus();
        else focusDefaultControl();
    }

    private Button sectionMenu(int tabIndex) {
        return tabIndex >= 0 && tabIndex < sectionButtons.length ? sectionButtons[tabIndex] : null;
    }

    private final Button[] sectionButtons = new Button[4];

    /* ---- folder and driver flows (the activity owns the pickers) ---- */

    @Override
    public void onChooseFolderRequested() {
        if (storage.granted()) openFolderPicker();
        else {
            pendingPick = true;
            storage.request();
        }
    }

    @Override
    public void onAllFilesRequested() {
        storage.requestAllFiles();
    }

    @Override
    public void onChooseZipRequested() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), REQUEST_DRIVER_ZIP);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
        super.onRequestPermissionsResult(request, perms, results);
        if (request != StorageAccess.PERMISSION_REQUEST) return;
        if (storage.granted()) {
            if (pendingPick) openFolderPicker();
            if (pendingPlay) play();
        }
        pendingPick = pendingPlay = false;
        folderSection.refresh();
        refreshStatus();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == REQUEST_FOLDER) onFolderPicked(data.getData());
        else if (request == REQUEST_DRIVER_ZIP)
            driverSection.onZipPicked(data.getData(), getContentResolver());
    }

    private void onFolderPicked(Uri uri) {
        String path = StorageAccess.folderPathOf(uri);
        if (path == null) {
            folderSection.error("That location is not a plain folder on this device; "
                    + "choose one on internal storage or the SD card.");
            return;
        }
        settings.folder(path);
        refresh();
        /* the folder is there but cannot be read: legacy storage was refused */
        if (!askedAllFiles && !storage.allFilesGranted() && anyUnreadable(folderSection.problems())) {
            askedAllFiles = true;
            storage.requestAllFiles();
        }
    }

    private void openFolderPicker() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER);
    }

    /* ---- play ---- */

    private void play() {
        if (isFinishing() || !folderSection.ready()) return;
        if (!storage.granted()) {
            pendingPlay = true;
            storage.request();
            return;
        }

        try {
            optionsSection.persist();
            pluginsSection.persist();
            LaunchPlan plan = new LaunchPlan(settings.folder(), optionsSection.choices(),
                    pluginsSection.snapshot(), driverSection.selectedDriver());
            configWriter.write(plan);
            android.util.Log.i("SkyrimLauncher", "play: game=" + settings.folder()
                    + " driver=" + (driverSection.selectedDriver() != null ? "custom" : "system"));
            GameLauncher.start(this, plan, modes);
            android.util.Log.i("SkyrimLauncher", "play: SkyrimActivity started");
        } catch (Throwable t) {
            /* show it instead of crashing, and keep the trace for reporting */
            folderSection.error("Could not start the game: " + t);
            saveErrorLog(t);
        }
    }

    private void saveErrorLog(Throwable t) {
        try {
            File port = new File(settings.folder(), "port");
            port.mkdirs();
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(port, "launcher_error.txt")))) {
                w.write(trace.toString());
            }
        } catch (Exception ignored) {
        }
    }

    /* ---- the prelaunch screen ---- */

    private ViewGroup buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        menuGroup = new LinearLayout(this);
        menuGroup.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        menuLp.leftMargin = widgets.dp(64);
        menuGroup.setLayoutParams(menuLp);

        widgets.wordmark(menuGroup);
        playMenu = widgets.menuButton(menuGroup, "Play", v -> play());
        LinearLayout.LayoutParams playLp = (LinearLayout.LayoutParams) playMenu.getLayoutParams();
        playLp.topMargin = widgets.dp(28);
        playMenu.setLayoutParams(playLp);
        folderMenu = menuButton("Game folder", 0);
        pluginsMenu = menuButton("Data files", 1);
        driverMenu = menuButton("GPU driver", 2);
        graphicsMenu = menuButton("Graphics", 3);

        TextView legend = widgets.legend("\u24B6 Select      \u24B7 Back      LB/RB Tab      \u25B2\u25BC Order");
        LinearLayout.LayoutParams legendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        legendLp.topMargin = widgets.dp(20);
        legend.setPadding(widgets.dp(2), 0, 0, 0);
        menuGroup.addView(legend, legendLp);
        root.addView(menuGroup);

        /* status + version stacked in the free bottom-right corner, away from the menu */
        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.VERTICAL);
        corner.setGravity(Gravity.RIGHT);
        FrameLayout.LayoutParams cornerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT);
        cornerLp.rightMargin = widgets.dp(28);
        cornerLp.bottomMargin = widgets.dp(24);

        statusText = widgets.overlayText("", 14);
        statusText.setMaxLines(2);
        statusText.setMaxWidth(widgets.dp(320));
        statusText.setGravity(Gravity.END);
        corner.addView(statusText);

        versionText = widgets.overlayText("", 12);
        versionText.setTextColor(widgets.hintColor());
        versionText.setGravity(Gravity.END);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionLp.topMargin = widgets.dp(2);
        corner.addView(versionText, versionLp);

        root.addView(corner, cornerLp);
        return root;
    }

    private Button menuButton(String label, final int tabIndex) {
        return sectionButtons[tabIndex] = widgets.menuButton(menuGroup, label, v -> {
            windowOpener = sectionButtons[tabIndex];
            window.open(tabs, tabIndex);
        });
    }

    /** A wallpaper picked in an older build; the bundled one replaced it. */
    private void deleteLegacyWallpaper() {
        File dir = new File(getFilesDir(), "launcher");
        if (dir.isDirectory()) {
            String[] l = dir.list();
            if (l != null) for (String f : l) new File(dir, f).delete();
            dir.delete();
        }
    }

    /** Shrinks the menu column when it is taller than the screen, so every
     * item (and the D-pad focus on it) stays visible. */
    private void fitMenu() {
        if (menuGroup == null) return;
        ViewGroup parent = (ViewGroup) menuGroup.getParent();
        int available = parent.getHeight() - widgets.dp(12);
        if (available <= 0 || menuGroup.getHeight() <= 0) return;
        float scale = Math.min(1f, available / (float) menuGroup.getHeight());
        menuGroup.setScaleX(scale);
        menuGroup.setScaleY(scale);
    }

    /** Brings every block up to date with the settings and the file system. */
    private void refresh() {
        folderSection.refresh();
        pluginsSection.reload();
        driverSection.refresh();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean ready = folderSection.ready();
        playMenu.setEnabled(ready);
        playMenu.setFocusable(ready);
        playMenu.setFocusableInTouchMode(ready);
        playMenu.setAlpha(ready ? 1f : 0.5f);
        statusText.setText(ready ? "\u2713 Skyrim ready" : "\u26A0 " + folderSection.statusLine());
        statusText.setTextColor(ready ? widgets.okColor() : widgets.warnColor());
        versionText.setText("v" + widgets.versionName());
    }

    private static boolean anyUnreadable(List<String> problems) {
        for (String p : problems) if (p.startsWith("cannot read")) return true;
        return false;
    }
}
