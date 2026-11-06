package com.tj.skyrim.launcher;

import android.content.ContentResolver;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The GPU drivers installed under &lt;game&gt;/port/drivers: listing them,
 * installing a zip (which only survives when it carries a meta.json with a
 * libraryName), selecting one and removing it. The selection applies on the
 * next Play, when the chosen driver is passed to the game.
 */
public final class DriverRegistry {

    /** The outcome of an install: exactly one of driver/error is set. */
    static final class InstallResult {
        final Driver driver;   /* the installed driver, or null */
        final String error;    /* what went wrong, or null */

        InstallResult(Driver driver, String error) {
            this.driver = driver;
            this.error = error;
        }
    }

    private static final String NO_META = "Could not install the driver: no meta.json / libraryName in the zip.";

    private final LauncherSettings settings;
    private final List<Driver> drivers = new ArrayList<>();

    public DriverRegistry(LauncherSettings settings) {
        this.settings = settings;
    }

    /** Where the zips are extracted to. */
    File root() {
        String path = settings.folder();
        return path == null ? null : new File(path, "port/drivers");
    }

    /** The installed drivers, ordered by name. */
    List<Driver> drivers() {
        return drivers;
    }

    /** Re-reads port/drivers. */
    void refresh() {
        drivers.clear();
        File root = root();
        if (root == null || !root.isDirectory()) return;
        String[] listing = root.list();
        if (listing == null) return;
        for (String name : listing) {
            Driver driver = Driver.read(new File(root, name));
            if (driver != null) drivers.add(driver);
        }
        Collections.sort(drivers, (a, b) -> a.label.compareTo(b.label));
    }

    /** The selected driver, or null for the system driver. */
    Driver selected() {
        String chosen = settings.driver();
        for (Driver d : drivers) if (d.dirName().equals(chosen)) return d;
        return null;
    }

    void select(Driver driver) {
        settings.driver(driver == null ? "" : driver.dirName());
    }

    /** Deletes a driver; the selection falls back to the system driver. */
    void remove(Driver driver) {
        if (driver == null) return;
        Fs.deleteTree(driver.dir);
        if (driver.dirName().equals(settings.driver())) select(null);
        refresh();
    }

    /**
     * Extracts the picked zip into port/drivers/&lt;zip name&gt; and keeps it only when
     * it is a real driver. The meta.json may sit at the zip root or one level
     * down (some zips wrap everything in a folder).
     */
    InstallResult install(Uri zipUri, ContentResolver resolver) {
        File root = root();
        if (root == null) return new InstallResult(null, "Choose the Skyrim folder first.");

        String name = zipUri.getLastPathSegment();
        if (name == null) name = "driver";
        else if (name.lastIndexOf('.') > 0) name = name.substring(0, name.lastIndexOf('.'));
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");

        File dir = new File(root, name);
        Fs.deleteTree(dir);
        dir.mkdirs();
        try {
            extractZip(zipUri, resolver, dir);
        } catch (Exception e) {
            Fs.deleteTree(dir);
            return new InstallResult(null, "Could not install the driver: " + e);
        }

        Driver driver = Driver.read(dir);
        if (driver == null)
            for (String child : dir.list() == null ? new String[0] : dir.list()) {
                File nested = new File(dir, child);
                if (!nested.isDirectory()) continue;
                driver = Driver.read(nested);
                if (driver != null) break;
            }
        if (driver == null) {
            Fs.deleteTree(dir);
            return new InstallResult(null, NO_META);
        }

        select(driver);
        refresh();
        return new InstallResult(driver, null);
    }

    private static void extractZip(Uri zipUri, ContentResolver resolver, File dir) throws Exception {
        try (InputStream in = resolver.openInputStream(zipUri);
             ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                while (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty() || name.contains("..")) continue;   /* no escaping the dir */
                File out = new File(dir, name);
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = zip.read(buf)) > 0; ) fo.write(buf, 0, n);
                }
            }
        }
    }
}
