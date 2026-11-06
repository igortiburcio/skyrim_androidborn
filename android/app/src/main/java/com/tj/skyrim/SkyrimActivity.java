package com.tj.skyrim;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import java.io.File;
import java.io.FileWriter;
import java.util.Date;
import java.util.List;
import org.libsdl.app.SDLActivity;

public class SkyrimActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "dxvk_d3d9", "main" };
    }

    /* argv[1] = game folder, argv[2] = save folder, from the launcher. */
    @Override
    protected String[] getArguments() {
        String game = getIntent().getStringExtra("game");
        return game == null ? new String[0] : new String[] { game };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        saveLastExit();
        setDriverEnv();
        setupEnv();
        windowTweaks();
        super.onCreate(savedInstanceState);
        if (BuildConfig.DUAL) startHudScreen();
    }

    /* The launcher passes the selected GPU driver as Intent extras; the
     * native loader reads these before libmain starts. The driver library is
     * copied to internal storage by the launcher (dlopen from shared storage
     * is not allowed), and Gradle extracts the adrenotools hooks to
     * nativeLibraryDir because of useLegacyPackaging. */
    private void setDriverEnv() {
        try {
            String dir = getIntent().getStringExtra("driver_dir");
            String lib = getIntent().getStringExtra("driver_lib");
            Os.setenv("LB_DRIVER_DIR", dir == null ? "" : dir, true);
            Os.setenv("LB_DRIVER_LIB", lib == null ? "" : lib, true);
            Os.setenv("LB_HOOK_DIR", getApplicationInfo().nativeLibraryDir + "/", true);
            File tmp = new File(getCacheDir(), "driver_tmp");
            tmp.mkdirs();
            Os.setenv("LB_DRIVER_TMP", tmp.getPath() + "/", true);
            File shaders = new File(getFilesDir(), "shadercache");
            shaders.mkdirs();
            Os.setenv("LB_PIPECACHE_DIR", shaders.getPath(), true);
        } catch (Throwable ignored) {
        }
    }

    /* DXVK reads its config from here; the launcher only manages a few keys
     * and leaves the rest of the file alone. */
    private void setupEnv() {
        try {
            /* the launcher's "Show FPS counter" toggle: DXVK's HUD draws it */
            if (getIntent().getIntExtra("ui_fps", 0) == 1)
                Os.setenv("DXVK_HUD", "fps", true);
            String game = getIntent().getStringExtra("game");
            if (game == null) return;
            File dxvk = new File(game, "port/dxvk.conf");
            if (dxvk.isFile()) Os.setenv("DXVK_CONFIG_FILE", dxvk.getAbsolutePath(), true);
            if (new File(game, "port/save/noasync.txt").isFile()) Os.setenv("LB_NOASYNC", "1", true);
        } catch (Throwable ignored) {
        }
    }

    /* Full-screen, edge-to-edge, keep the screen on: the SDL window is then
     * the same size as the Android surface (no black strip from the navigation
     * bar) and the cutout is used. */
    private void windowTweaks() {
        try {
            WindowManager.LayoutParams a = getWindow().getAttributes();
            a.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30 ? 3 : 1;
            getWindow().setAttributes(a);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            /* Optional: ask the platform for sustained performance instead of
             * short clock boosts. On a handheld with active cooling this keeps
             * the SoC at a steadier (cooler) point instead of ramping up. */
            if (Build.VERSION.SDK_INT >= 24)
                getWindow().setSustainedPerformanceMode(
                        getIntent().getIntExtra("sustained", 0) == 1);
            if (Build.VERSION.SDK_INT >= 30) {
                /* Android 15 enforces the insets controller over the legacy
                 * SYSTEM_UI_FLAG_* path: without this the SurfaceView is laid
                 * out inside the status/navigation bars and the game buffer is
                 * shown centred with a black strip above and below. */
                getWindow().setDecorFitsSystemWindows(false);
                getWindow().setStatusBarColor(Color.TRANSPARENT);
                getWindow().setNavigationBarColor(Color.TRANSPARENT);
                getWindow().setStatusBarContrastEnforced(false);
                getWindow().setNavigationBarContrastEnforced(false);
            }
            hideSystemBars();
        } catch (Throwable ignored) {
        }
    }

    private void hideSystemBars() {
        try {
            getWindow().getDecorView();  /* the decor must exist before insets */
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        /* SDL re-applies its own window flags while starting up; hide the
         * bars once more after the surface has been created. */
        getWindow().getDecorView().postDelayed(this::hideSystemBars, 500);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        if (hudScreen != null) { hudScreen.dismiss(); hudScreen = null; }
        super.onDestroy();
    }

    // ---- Dual build: the HUD on the second display (runtime/hud_screen.cpp) ----
    static native void nativeHudSurface(android.view.Surface surface);

    private Presentation hudScreen;
    private DisplayManager displays;

    private void startHudScreen() {
        displays = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        displays.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { showHudScreen(); }
            @Override public void onDisplayRemoved(int id) {
                if (hudScreen != null && hudScreen.getDisplay().getDisplayId() == id) {
                    hudScreen.dismiss();
                    hudScreen = null;
                }
            }
            @Override public void onDisplayChanged(int id) {}
        }, new Handler(Looper.getMainLooper()));
        showHudScreen();
    }

    private Display secondDisplay() {
        Display[] p = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display d : p) if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        for (Display d : displays.getDisplays()) if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        return null;
    }

    private void showHudScreen() {
        if (hudScreen != null) return;
        Display d = secondDisplay();
        if (d == null) return;
        try {
            hudScreen = new Presentation(this, d);
            SurfaceView view = new SurfaceView(hudScreen.getContext());
            view.setBackgroundColor(Color.TRANSPARENT);
            view.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {}
                @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
                    if (mBrokenLibraries) return;
                    nativeHudSurface(h.getSurface());
                }
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    if (mBrokenLibraries) return;
                    nativeHudSurface(null);
                }
            });
            hudScreen.setContentView(view);
            // the game keeps the input focus
            hudScreen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            hudScreen.show();
        } catch (Exception e) {
            hudScreen = null;
        }
    }

    /* Why the previous run ended (crash, low memory kill, ...), kept in
     * files/last_exit.txt since log.txt is replaced on every launch. */
    private void saveLastExit() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(null, 0, 3);
            String game = getIntent().getStringExtra("game");
            File dir = game != null ? new File(game, "port") : null;
            if (dir != null) dir.mkdirs();
            if (l.isEmpty() || dir == null) return;
            try (FileWriter w = new FileWriter(new File(dir, "last_exit.txt"))) {
                for (ApplicationExitInfo e : l) {
                    w.write(new Date(e.getTimestamp()) + ": reason " + reasonName(e.getReason())
                            + " status " + e.getStatus() + " importance " + e.getImportance()
                            + " pss " + e.getPss() / 1024 + "MB rss " + e.getRss() / 1024 + "MB"
                            + " desc " + e.getDescription() + "\n");
                }
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                w.write("device RAM " + mi.totalMem / (1 << 20) + "MB, available now " + mi.availMem / (1 << 20)
                        + "MB, low-memory threshold " + mi.threshold / (1 << 20) + "MB\n");
            }
        } catch (Exception ignored) {
        }
    }

    private static String reasonName(int r) {
        switch (r) {
        case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
        case ApplicationExitInfo.REASON_CRASH: return "CRASH_JAVA";
        case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
        case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
        case ApplicationExitInfo.REASON_ANR: return "ANR";
        case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
        case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
        case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
        case ApplicationExitInfo.REASON_OTHER: return "OTHER";
        default: return "code " + r;
        }
    }
}
