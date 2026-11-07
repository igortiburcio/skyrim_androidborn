package com.tj.skyrim.launcher;

import android.app.Activity;
import android.content.Intent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Starts the game with the Intent extras its SkyrimActivity expects: the game
 * folder, the FPS/touch overlays and the GPU driver to load (Turnip / Mr.
 * Purple).
 */
public final class GameLauncher {

    private GameLauncher() {}

    public static void start(Activity from, LaunchPlan plan) throws Exception {
        Intent intent = new Intent();
        intent.setClassName("com.tj.skyrim", "com.tj.skyrim.SkyrimActivity");
        intent.putExtra("game", plan.folder);
        intent.putExtra("ui_fps", plan.choices.showFps ? 1 : 0);
        intent.putExtra("ui_touch", plan.choices.touchControls ? 1 : 0);
        intent.putExtra("sustained", plan.choices.sustainedPerformance ? 1 : 0);
        if (plan.driver != null) {
            /* libadrenotools can only dlopen from app-private storage: copy
             * the installed driver there and pass that path. */
            File internal = new File(from.getFilesDir(), "drivers/" + plan.driver.dirName());
            internal.mkdirs();
            File src = new File(plan.driver.dir, plan.driver.library);
            File dst = new File(internal, plan.driver.library);
            if (!dst.isFile() || dst.length() != src.length()) copy(src, dst);
            intent.putExtra("driver_dir", internal.getAbsolutePath() + "/");
            intent.putExtra("driver_lib", plan.driver.library);
        }
        from.startActivity(intent);
        /* the launcher stays in the back stack: finishing it in the same tick
         * as the game starts killed the shared process on some devices */
    }

    private static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
    }
}
