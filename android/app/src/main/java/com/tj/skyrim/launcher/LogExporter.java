package com.tj.skyrim.launcher;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs what a bug report needs into one zip inside the game's port folder:
 * the port log, the last exit report, the DXVK config, the load order and a
 * device summary. Older exports beyond five are removed so the folder does
 * not grow every time someone presses the button.
 */
public final class LogExporter {

    private static final int KEEP = 5;

    private LogExporter() {
    }

    /**
     * Writes the zip and returns it.
     *
     * @throws IOException with a message the launcher can show as-is.
     */
    public static File export(Context context, LauncherSettings settings) throws IOException {
        String folder = settings.folder();
        if (folder == null) throw new IOException("Choose the Skyrim folder first.");
        File port = new File(folder, "port");
        if (!port.isDirectory() && !port.mkdirs())
            throw new IOException("The port folder could not be created.");

        String name = "logs-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + ".zip";
        File out = new File(port, name);

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            add(zip, new File(port, "log.txt"), "log.txt");
            add(zip, new File(port, "last_exit.txt"), "last_exit.txt");
            add(zip, new File(port, "dxvk.conf"), "dxvk.conf");
            File plugins = pluginsFile(folder);
            if (plugins != null) add(zip, plugins, "Plugins.txt");
            zip.putNextEntry(new ZipEntry("device.txt"));
            zip.write(CrashReport.describe(context).getBytes("UTF-8"));
            zip.closeEntry();
        } catch (IOException e) {
            out.delete();
            throw new IOException("The log zip could not be written: " + e.getMessage(), e);
        }

        prune(port);
        return out;
    }

    private static void add(ZipOutputStream zip, File file, String name) throws IOException {
        if (!file.isFile()) return;
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }

    /** Plugins.txt lives in the game's save folder; its case varies. */
    private static File pluginsFile(String folder) {
        File dir = new File(new File(folder, "port/save/Documents"), "Skyrim");
        File direct = new File(dir, "Plugins.txt");
        if (direct.isFile()) return direct;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isFile() && file.getName().equalsIgnoreCase("Plugins.txt")) return file;
        }
        return null;
    }

    private static void prune(File port) {
        File[] files = port.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        int kept = 0;
        for (File file : files) {
            if (!file.isFile() || !file.getName().startsWith("logs-")
                    || !file.getName().endsWith(".zip")) continue;
            if (++kept > KEEP) file.delete();
        }
    }
}
