package com.tj.skyrim.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the launcher persists, in one place: the chosen folder, the
 * selected options, the load order and active plugins, and the selected GPU
 * driver.
 */
public final class LauncherSettings {

    private static final String PREFS = "launcher";

    private final SharedPreferences prefs;

    public LauncherSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ---- folder ---- */

    public String folder() {
        return prefs.getString("folder", null);
    }

    public void folder(String path) {
        prefs.edit().putString("folder", path).apply();
    }

    /**
     * The folder a Steam download last completed into. It survives choosing
     * another folder by hand, so the downloaded copy can still be removed.
     */
    public String steamFolder() {
        return prefs.getString("steamFolder", null);
    }

    public void steamFolder(String path) {
        prefs.edit().putString("steamFolder", path).apply();
    }

    /* ---- options (spinner indexes and checkbox states) ---- */

    public int option(String key, int def) {
        return prefs.getInt(key, def);
    }

    public void setOption(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    public boolean flag(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }

    public void setFlag(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    /** The aspect the resolution list was last shown for. */
    public int aspectShown() {
        return option("aspectShown", 0);
    }

    public void aspectShown(int value) {
        setOption("aspectShown", value);
    }

    /* ---- plugins ---- */

    public List<String> pluginOrder() {
        return lines(prefs.getString("pluginsOrder", ""));
    }

    public void pluginOrder(List<String> names) {
        prefs.edit().putString("pluginsOrder", join(names)).apply();
    }

    public List<String> pluginsOn() {
        return lines(prefs.getString("pluginsOn", ""));
    }

    public void pluginsOn(List<String> names) {
        prefs.edit().putString("pluginsOn", join(names)).apply();
    }

    /** When the launcher last wrote Plugins.txt/loadorder.txt. */
    public long pluginsWrittenAt() {
        return prefs.getLong("pluginsWrittenAt", 0);
    }

    public void pluginsWrittenAt(long time) {
        prefs.edit().putLong("pluginsWrittenAt", time).apply();
    }

    /* ---- GPU driver ---- */

    /** The directory name of the selected driver, or "" for the system driver. */
    public String driver() {
        return prefs.getString("driver", "");
    }

    public void driver(String dirName) {
        prefs.edit().putString("driver", dirName == null ? "" : dirName).apply();
    }

    /* ---- update download (survives a pause or a restart) ---- */

    /** The DownloadManager id of the update APK, or -1 when there is none. */
    public long updateDownloadId() {
        return prefs.getLong("updateDownloadId", -1);
    }

    public void updateDownloadId(long id) {
        prefs.edit().putLong("updateDownloadId", id).apply();
    }

    /** The release version the saved download belongs to. */
    public String updateVersion() {
        return prefs.getString("updateVersion", "");
    }

    public void updateVersion(String version) {
        prefs.edit().putString("updateVersion", version == null ? "" : version).apply();
    }

    /* ------------------------------------------------------------------ */

    private static List<String> lines(String joined) {
        List<String> r = new ArrayList<>();
        for (String s : joined.split("\n")) if (!s.isEmpty()) r.add(s);
        return r;
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sb.toString();
    }
}
