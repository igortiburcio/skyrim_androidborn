package com.tj.skyrim.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes why the app died where the player can find it. Android kills the
 * process on a Java crash before anything else runs, so this hooks the default
 * handler: the report is appended to &lt;game&gt;/port/last_exit.txt and the
 * system then handles the crash as usual.
 *
 * <p>Without a game folder the report goes to the app's external files folder
 * instead, so a crash in the launcher still leaves a trace.
 */
public final class CrashReport {

    private static final String FILE = "last_exit.txt";

    private CrashReport() {
    }

    /** Installs the handler; call once from every entry point of the app. */
    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                write(app, "crash in thread " + thread.getName(), error);
            } catch (Throwable ignored) {
                /* the crash itself must still reach the system */
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Appends one report; never throws. */
    public static void write(Context context, String what, Throwable error) {
        try {
            File dir = portDir(context);
            if (dir == null) return;
            try (FileWriter out = new FileWriter(new File(dir, FILE), true)) {
                out.write("\n" + stamp() + "\n" + describe(context) + "\n" + what + "\n");
                if (error != null) {
                    StringWriter text = new StringWriter();
                    error.printStackTrace(new PrintWriter(text));
                    out.write(text.toString());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** The folder the port writes to: &lt;game&gt;/port, or the app files. */
    static File portDir(Context context) {
        String folder = new LauncherSettings(context).folder();
        File dir = null;
        if (folder != null) dir = new File(folder, "port");
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            File ext = context.getExternalFilesDir(null);
            dir = ext != null ? ext : new File(context.getFilesDir(), "port");
            dir.mkdirs();
        }
        return dir.isDirectory() ? dir : null;
    }

    /** Device, version and selected options, one per line. */
    public static String describe(Context context) {
        LauncherSettings settings = new LauncherSettings(context);
        StringBuilder out = new StringBuilder();
        out.append("Skyrim Androidborn ").append(version(context)).append('\n');
        out.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append("), Android ")
                .append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
                .append(")\n");
        if (Build.VERSION.SDK_INT >= 31)
            out.append("soc: ").append(Build.SOC_MANUFACTURER).append(' ')
                    .append(Build.SOC_MODEL).append('\n');
        out.append("hardware: ").append(Build.HARDWARE).append('\n');
        String folder = settings.folder();
        out.append("folder: ").append(folder == null ? "(none)" : folder).append('\n');
        String driver = settings.driver();
        out.append("driver: ").append(driver == null ? "system" : driver).append('\n');
        out.append("preset: ").append(settings.option("preset", 1))
                .append(" res: ").append(settings.option("res", 0))
                .append(" aa: ").append(settings.option("aa", 0))
                .append(" frcap: ").append(settings.option("frcap", 0))
                .append(' ').append(settings.flag("vsync", true) ? "vsync" : "novsync")
                .append('\n');
        return out.toString();
    }

    /** The installed versionName, or "?" when the package cannot be read. */
    public static String version(Context context) {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "?" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
