package com.tj.skyrim.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the launcher persists, in one place: the chosen folder, the
 * selected options, the load order and active plugins, the selected GPU
 * driver, and a SHA-1 cache for the game executable check.
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

    /* ---- GPU driver ---- */

    /** The directory name of the selected driver, or "" for the system driver. */
    public String driver() {
        return prefs.getString("driver", "");
    }

    public void driver(String dirName) {
        prefs.edit().putString("driver", dirName == null ? "" : dirName).apply();
    }

    /* ---- SHA-1 cache (keyed by path, size and modification time) ---- */

    /** The cached hex for f, or null when it was not hashed yet or changed. */
    public String cachedSha1(File f) {
        String stamp = f.length() + ":" + f.lastModified();
        String cached = prefs.getString("sha1:" + f.getAbsolutePath(), null);
        return cached != null && cached.startsWith(stamp + "=")
                ? cached.substring(stamp.length() + 1) : null;
    }

    public void cacheSha1(File f, String hex) {
        String stamp = f.length() + ":" + f.lastModified();
        prefs.edit().putString("sha1:" + f.getAbsolutePath(), stamp + "=" + hex).apply();
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
