package com.tj.skyrim;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
import com.tj.skyrim.launcher.FolderBrowser;
import com.tj.skyrim.launcher.FolderCheck;
import com.tj.skyrim.launcher.GameConfigWriter;
import com.tj.skyrim.launcher.GameDataRemoval;
import com.tj.skyrim.launcher.GameFilesSection;
import com.tj.skyrim.launcher.GameLauncher;
import com.tj.skyrim.launcher.GamepadController;
import com.tj.skyrim.launcher.GamepadController.Host;
import com.tj.skyrim.launcher.LaunchPlan;
import com.tj.skyrim.launcher.LauncherSettings;
import com.tj.skyrim.launcher.LauncherWindow;
import com.tj.skyrim.launcher.OptionsSection;
import com.tj.skyrim.launcher.PluginRegistry;
import com.tj.skyrim.launcher.PluginsSection;
import com.tj.skyrim.launcher.SteamDownloadState;
import com.tj.skyrim.launcher.StorageAccess;
import com.tj.skyrim.launcher.UpdateChecker;
import com.tj.skyrim.launcher.UpdateDownloader;
import com.tj.skyrim.launcher.UpdatePanel;
import com.tj.skyrim.launcher.Widgets;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.security.MessageDigest;
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
        implements Host, GameFilesSection.Listener, DriverSection.Listener,
        LauncherWindow.Listener {

    private static final int REQUEST_FOLDER = 1;
    private static final int REQUEST_DRIVER_ZIP = 3;
    private static final long UPDATE_CHECK_INTERVAL_MS = 30 * 60 * 1000L;

    private LauncherSettings settings;
    private StorageAccess storage;
    private GamepadController gamepad;
    private Widgets widgets;
    private DisplayModes modes;
    private GameConfigWriter configWriter;

    private GameFilesSection gameFiles;
    private PluginsSection pluginsSection;
    private DriverSection driverSection;
    private OptionsSection optionsSection;
    private LauncherWindow window;
    private List<LauncherWindow.Content> gameFilesTabs;
    private List<LauncherWindow.Content> settingsTabs;

    private BackdropView backdrop;
    private LinearLayout menuGroup;
    private Button playMenu, gameFilesMenu, settingsMenu;
    private TextView statusText, versionText;

    /* update check, download and install */
    private UpdatePanel updatePanel;
    private UpdateDownloader updateDownloader;
    private UpdateChecker.Release pendingUpdate;
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private boolean updateDownloading;
    private boolean updatePolling;
    private boolean resumed;
    private long updateDownloadId = -1;
    private long lastUpdateCheck;

    /* the menu button that opened the window, so closing returns to it */
    private Button windowOpener;

    /* continue once the storage prompt is answered */
    private boolean pendingPick, pendingPlay, pendingDownloadFolder;
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

        gameFiles = new GameFilesSection(widgets, settings, new FolderCheck(), storage, this);
        pluginsSection = new PluginsSection(widgets, new PluginRegistry(settings));
        driverSection = new DriverSection(widgets, new DriverRegistry(settings), this);
        optionsSection = new OptionsSection(widgets, settings, modes);
        updateDownloader = new UpdateDownloader(this);
        gameFilesTabs = Arrays.asList(gameFiles);
        settingsTabs = Arrays.asList(optionsSection, driverSection, pluginsSection);

        ViewGroup root = buildScreen();
        setContentView(root);
        updatePanel = new UpdatePanel(this, widgets, root, new UpdatePanel.Listener() {
            @Override public void onDownloadRequested() { startUpdateDownload(); }

            @Override public void onInstallRequested() { installUpdate(); }

            @Override public void onOpenReleaseRequested() { openReleasePage(); }
        });
        window = new LauncherWindow(this, widgets, root);
        window.setListener(this);
        /* the Game Files pane exists from the start, so Play works even if no
         * window was ever opened; choices() and selectedDriver() fall back to
         * the saved settings for the lazily built Settings tabs */
        window.prepare(gameFilesTabs);
        root.post(this::fitMenu);

        refresh();
        focusDefaultControl();
        playMenu.post(this::focusDefaultControlIfLost);
        checkForUpdates(BuildConfig.DEBUG
                && getIntent().getBooleanExtra("force_update", false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        widgets.applyWindow();
        /* safety: the menu must never stay focus-blocked from a window cycle */
        if (!window.isOpen()) menuGroup.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        refresh();
        if (getCurrentFocus() == null && !window.isOpen()) focusDefaultControl();
        menuGroup.post(this::fitMenu);
        if (updateDownloading) startUpdatePolling();
        if (pendingUpdate == null
                && System.currentTimeMillis() - lastUpdateCheck > UPDATE_CHECK_INTERVAL_MS) {
            checkForUpdates(false);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) widgets.applyWindow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        gamepad.pause();
        pluginsSection.persist();
        optionsSection.persist();
        stopUpdatePolling();
    }

    @Override
    protected void onDestroy() {
        updateHandler.removeCallbacksAndMessages(null);
        if (gameFiles != null) gameFiles.close();
        super.onDestroy();
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
        (playMenu != null && playMenu.isEnabled() ? playMenu : gameFilesMenu).requestFocus();
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
        updatePanel.setFocusable(false);
    }

    @Override
    public void onWindowClosed(int tabIndex) {
        menuGroup.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        updatePanel.setFocusable(true);
        optionsSection.persist();
        pluginsSection.persist();
        Button menu = windowOpener;
        windowOpener = null;
        if (menu != null) menu.requestFocus();
        else focusDefaultControl();
    }


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
            if (pendingDownloadFolder) onChooseDownloadFolderRequested();
        }
        pendingPick = pendingPlay = pendingDownloadFolder = false;
        gameFiles.refresh();
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
            gameFiles.error("That location is not a plain folder on this device; "
                    + "choose one on internal storage or the SD card.");
            return;
        }
        settings.folder(path);
        refresh();
        /* the folder is there but cannot be read: legacy storage was refused */
        if (!askedAllFiles && !storage.allFilesGranted() && anyUnreadable(gameFiles.problems())) {
            askedAllFiles = true;
            storage.requestAllFiles();
        }
    }

    private void openFolderPicker() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER);
    }

    /* ---- Steam download folder ---- */

    @Override
    public void onChooseDownloadFolderRequested() {
        if (!storage.granted()) {
            pendingDownloadFolder = true;
            storage.request();
            return;
        }
        // The system picker refuses SD-card roots and Download; this browser
        // lists the real directories and starts from the current game folder.
        FolderBrowser.show(this, settings.folder(),
                path -> gameFiles.downloadInto(path));
    }

    @Override
    public void onDownloadFinished(String path) {
        settings.folder(path);
        settings.steamFolder(path);
        refresh();
    }

    /* ---- removing a game data copy ---- */

    @Override
    public void onRemoveSteamRequested() {
        removeGameData(true);
    }

    @Override
    public void onRemoveFolderRequested() {
        removeGameData(false);
    }

    private void removeGameData(boolean steamCopy) {
        if (SteamDownloadState.isRunning()) {
            gameFiles.refresh();
            gameFiles.note("Wait for the Steam download to finish before removing data.", false);
            return;
        }
        final String path = steamCopy ? settings.steamFolder() : settings.folder();
        if (path == null || path.isEmpty()) {
            gameFiles.refresh();
            return;
        }
        new Thread(() -> {
            boolean removed = GameDataRemoval.remove(new File(path));
            runOnUiThread(() -> {
                if (!removed) {
                    gameFiles.refresh();
                    gameFiles.note("Could not remove " + path
                            + ". Check the storage permission and try again.", false);
                    return;
                }
                if (steamCopy) {
                    settings.steamFolder(null);
                    if (samePath(path, settings.folder())) settings.folder(null);
                } else {
                    settings.folder(null);
                }
                refresh();
                gameFiles.note((steamCopy ? "Steam data removed." : "Game data removed.")
                        + " port/save was kept.", true);
            });
        }, "remove-game-data").start();
    }

    private static boolean samePath(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b) || a.equals(b + "/") || b.equals(a + "/");
    }

    /* ---- play ---- */

    private void play() {
        if (isFinishing() || !gameFiles.ready()) return;
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
            GameLauncher.start(this, plan);
            android.util.Log.i("SkyrimLauncher", "play: SkyrimActivity started");
        } catch (Throwable t) {
            /* show it instead of crashing, and keep the trace for reporting */
            gameFiles.error("Could not start the game: " + t);
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
        gameFilesMenu = menuButton("Game Files", gameFilesTabs, 0);
        settingsMenu = menuButton("Settings", settingsTabs, 0);

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
        corner.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

    private Button menuButton(String label, final List<LauncherWindow.Content> set,
                              final int tabIndex) {
        final Button[] holder = new Button[1];
        holder[0] = widgets.menuButton(menuGroup, label, v -> {
            windowOpener = holder[0];
            window.open(set, tabIndex);
        });
        return holder[0];
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
        gameFiles.refresh();
        pluginsSection.reload();
        driverSection.refresh();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean ready = gameFiles.ready();
        playMenu.setEnabled(ready);
        playMenu.setFocusable(ready);
        playMenu.setFocusableInTouchMode(ready);
        playMenu.setAlpha(ready ? 1f : 0.5f);
        statusText.setText(ready ? "\u2713 Skyrim ready" : "\u26A0 " + gameFiles.statusLine());
        statusText.setTextColor(ready ? widgets.okColor() : widgets.warnColor());
        if (pendingUpdate != null) {
            versionText.setText("v" + widgets.versionName() + "  \u2192  " + pendingUpdate.version);
            versionText.setTextColor(Widgets.GOLD);
        } else {
            versionText.setText("v" + widgets.versionName());
            versionText.setTextColor(widgets.hintColor());
        }
    }

    /* ---- updates ---- */

    private void checkForUpdates(boolean force) {
        lastUpdateCheck = System.currentTimeMillis();
        if (BuildConfig.DUAL) return;
        UpdateChecker.check(this, force, release -> {
            if (release == null || isFinishing() || isDestroyed()) return;
            pendingUpdate = release;
            updatePanel.setRelease(release);
            updatePanel.show();
            refreshStatus();
            menuGroup.post(this::fitMenu);
            resumeSavedDownload(release);
        });
    }

    private void startUpdateDownload() {
        if (pendingUpdate == null || pendingUpdate.apkUrl.isEmpty()) return;
        try {
            updateDownloadId = updateDownloader.start(pendingUpdate);
            settings.updateDownloadId(updateDownloadId);
            settings.updateVersion(pendingUpdate.version);
            updateDownloading = true;
            updatePanel.downloading(true);
            startUpdatePolling();
        } catch (Throwable t) {
            updatePanel.error("Could not start the download: " + t.getMessage());
        }
    }

    /** A download started before a pause or a restart is picked up again. */
    private void resumeSavedDownload(UpdateChecker.Release release) {
        long id = settings.updateDownloadId();
        if (id < 0 || !release.version.equals(settings.updateVersion())) return;
        updateDownloadId = id;
        updateDownloader.poll(id, new UpdateDownloader.Listener() {
            @Override public void onProgress(long soFar, long total) {
                updateDownloading = true;
                updatePanel.downloading(true);
                updatePanel.progress(soFar, total);
                startUpdatePolling();
            }

            @Override public void onComplete() {
                updatePanel.downloaded(true);
            }

            @Override public void onError(String message) {
                settings.updateDownloadId(-1);
                settings.updateVersion("");
            }
        });
    }

    private void startUpdatePolling() {
        if (!resumed) return;
        updatePolling = true;
        updateHandler.removeCallbacks(updatePoll);
        updateHandler.post(updatePoll);
    }

    private void stopUpdatePolling() {
        updatePolling = false;
        updateHandler.removeCallbacks(updatePoll);
    }

    private final Runnable updatePoll = new Runnable() {
        @Override public void run() {
            if (!updatePolling || !updateDownloading || updateDownloadId < 0) return;
            updateDownloader.poll(updateDownloadId, new UpdateDownloader.Listener() {
                @Override public void onProgress(long soFar, long total) {
                    updatePanel.progress(soFar, total);
                    if (updatePolling) updateHandler.postDelayed(updatePoll, 500);
                }

                @Override public void onComplete() {
                    updateDownloading = false;
                    stopUpdatePolling();
                    updatePanel.downloaded(true);
                }

                @Override public void onError(String message) {
                    updateDownloading = false;
                    stopUpdatePolling();
                    settings.updateDownloadId(-1);
                    settings.updateVersion("");
                    updatePanel.error(message);
                }
            });
        }
    };

    private void installUpdate() {
        if (pendingUpdate == null || updateDownloadId < 0) return;
        File apk = updateDownloader.fileFor(pendingUpdate);
        if (apk == null || !apk.isFile()) {
            updatePanel.downloaded(false);
            settings.updateDownloadId(-1);
            settings.updateVersion("");
            updatePanel.error("The update file is missing. Download it again.");
            return;
        }
        PackageManager pm = getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (info == null || !getPackageName().equals(info.packageName)) {
            updatePanel.downloaded(false);
            settings.updateDownloadId(-1);
            settings.updateVersion("");
            apk.delete();
            updatePanel.error("The downloaded file is not a Skyrim Androidborn update.");
            return;
        }
        if (info.versionName != null && !pendingUpdate.version.equals(info.versionName)) {
            updatePanel.downloaded(false);
            settings.updateDownloadId(-1);
            settings.updateVersion("");
            apk.delete();
            updatePanel.error("The downloaded update is not the expected version.");
            return;
        }
        if (pendingUpdate.sha256.isEmpty()) openInstaller();
        else verifyThenInstall(apk);
    }

    private void verifyThenInstall(File apk) {
        updatePanel.verifying();
        final String expected = pendingUpdate.sha256;
        new Thread(() -> {
            final String actual = sha256(apk);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (actual == null || !actual.equalsIgnoreCase(expected)) {
                    apk.delete();
                    settings.updateDownloadId(-1);
                    settings.updateVersion("");
                    updatePanel.downloaded(false);
                    updatePanel.error("The update file is damaged. Download it again.");
                } else {
                    openInstaller();
                }
            });
        }, "update-verify").start();
    }

    private void openInstaller() {
        updatePanel.downloaded(true);
        if (!getPackageManager().canRequestPackageInstalls()) {
            updatePanel.error("Allow installs for Skyrim Androidborn, then tap Install update again.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                } catch (Exception ignored) {
                }
            }
            return;
        }
        Uri uri = updateDownloader.uriFor(updateDownloadId);
        if (uri == null) {
            settings.updateDownloadId(-1);
            settings.updateVersion("");
            updatePanel.downloaded(false);
            updatePanel.error("The update file is not ready. Download it again.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, UpdateDownloader.APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            updatePanel.error("Could not open the installer: " + e.getMessage());
        }
    }

    private void openReleasePage() {
        if (pendingUpdate == null || pendingUpdate.htmlUrl.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pendingUpdate.htmlUrl)));
        } catch (Exception e) {
            updatePanel.error("Could not open the release page: " + e.getMessage());
        }
    }

    private static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) digest.update(buffer, 0, count);
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean anyUnreadable(List<String> problems) {
        for (String p : problems) if (p.startsWith("cannot read")) return true;
        return false;
    }
}
