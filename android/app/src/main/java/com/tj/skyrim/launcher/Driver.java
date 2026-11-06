package com.tj.skyrim.launcher;

import java.io.File;
import org.json.JSONObject;

/**
 * An installed GPU driver (a Turnip / Mr. Purple zip, the same ones the
 * emulators use): the directory it was extracted into, the name the user sees,
 * and the library that is handed to the game as <code>driver_lib</code>.
 */
public final class Driver {

    final File dir;
    final String label;
    final String library;

    private Driver(File dir, String label, String library) {
        this.dir = dir;
        this.label = label;
        this.library = library;
    }

    String dirName() {
        return dir.getName();
    }

    /**
     * Reads a driver from a directory that already holds an extracted zip; null
     * when there is no meta.json, or no libraryName in it — that is the format
     * the launcher installs and the game loads.
     */
    static Driver read(File dir) {
        try {
            File meta = new File(dir, "meta.json");
            if (!meta.isFile()) return null;
            JSONObject json = new JSONObject(Fs.readText(meta));
            String library = json.optString("libraryName", "");
            if (library.isEmpty()) return null;
            String vendor = json.optString("vendorName", "");
            String version = json.optString("driverVersion", json.optString("versionString", ""));
            String label = vendor.isEmpty() ? dir.getName() : vendor;
            if (!version.isEmpty()) label += " " + version;
            return new Driver(dir, label, library);
        } catch (Exception e) {
            return null;
        }
    }
}
