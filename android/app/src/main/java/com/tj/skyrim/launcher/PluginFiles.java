package com.tj.skyrim.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes the plugin files the game itself uses. Skyrim keeps them in
 * {@code %LOCALAPPDATA%\Skyrim}; the runtime maps that to
 * {@code port/save/Documents/Skyrim} (the shell shim returns Documents for
 * every CSIDL). The old launcher wrote only to
 * {@code Documents/My Games/Skyrim}, which the game never reads.
 *
 * <p>Both files are mirrored to My Games for compatibility, and a file whose
 * case differs (Plugins.txt vs plugins.txt) is removed: Android storage is
 * case-sensitive, but the game's lookup is not, so a stale duplicate could win.
 */
final class PluginFiles {

    private static final String PLUGINS = "Plugins.txt";
    private static final String LOADORDER = "loadorder.txt";

    private PluginFiles() {}

    /** The folder the game reads (%LOCALAPPDATA%\Skyrim). */
    static File gameDir(String folder) {
        if (folder == null) return null;
        return new File(resolve(resolve(new File(folder), "port", "save"), "Documents"), "Skyrim");
    }

    /** The folder that holds the INIs (Documents\My Games\Skyrim). */
    static File iniDir(String folder) {
        if (folder == null) return null;
        File docs = resolve(resolve(new File(folder), "port", "save"), "Documents");
        return new File(resolve(docs, "My Games"), "Skyrim");
    }

    /** The order in loadorder.txt, falling back to the active list. */
    static List<String> readOrder(String folder) {
        List<String> names = readNames(find(gameDir(folder), LOADORDER));
        if (names.isEmpty()) names = readNames(find(gameDir(folder), PLUGINS));
        if (names.isEmpty()) names = readNames(find(iniDir(folder), LOADORDER));
        if (names.isEmpty()) names = readNames(find(iniDir(folder), PLUGINS));
        return names;
    }

    /** The enabled plugins from the game's Plugins.txt. */
    static List<String> readActive(String folder) {
        List<String> names = readNames(find(gameDir(folder), PLUGINS));
        if (names.isEmpty()) names = readNames(find(iniDir(folder), PLUGINS));
        return names;
    }

    /** The newest mtime of any plugin file, or 0 when none exists. */
    static long lastModified(String folder) {
        long newest = 0;
        for (File dir : new File[] {gameDir(folder), iniDir(folder)}) {
            if (dir == null) continue;
            for (String name : new String[] {PLUGINS, LOADORDER}) {
                File f = find(dir, name);
                if (f != null) newest = Math.max(newest, f.lastModified());
            }
        }
        return newest;
    }

    /** Writes the active list and the full order to both locations. */
    static void write(String folder, List<Plugin> plugins) {
        if (folder == null) return;
        StringBuilder active = new StringBuilder(), order = new StringBuilder();
        for (Plugin p : plugins) {
            order.append(p.name).append("\r\n");
            if (p.on || p.alwaysOn()) active.append(p.name).append("\r\n");
        }
        for (File dir : new File[] {gameDir(folder), iniDir(folder)}) {
            writeOne(dir, PLUGINS, active.toString());
            writeOne(dir, LOADORDER, order.toString());
        }
    }

    /** The required position of the five official masters, then other ESMs, then ESPs. */
    static int rank(String name) {
        if (name.equalsIgnoreCase("Skyrim.esm")) return 0;
        if (name.equalsIgnoreCase("Update.esm")) return 1;
        if (name.equalsIgnoreCase("Dawnguard.esm")) return 2;
        if (name.equalsIgnoreCase("HearthFires.esm")) return 3;
        if (name.equalsIgnoreCase("Dragonborn.esm")) return 4;
        return name.toLowerCase(Locale.ROOT).endsWith(".esm") ? 10 : 20;
    }

    static boolean isOfficialMaster(String name) {
        return rank(name) < 10;
    }

    /* ------------------------------------------------------------------ */

    private static void writeOne(File dir, String name, String content) {
        if (dir == null) return;
        dir.mkdirs();
        File target = find(dir, name);
        if (target == null) target = new File(dir, name);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(target), "UTF-8")) {
            w.write(content);
        } catch (Exception ignored) {
            return;
        }
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isFile() && !f.equals(target) && f.getName().equalsIgnoreCase(name)) f.delete();
        }
    }

    private static File find(File dir, String name) {
        if (dir == null) return null;
        File exact = new File(dir, name);
        if (exact.isFile()) return exact;
        File[] list = dir.listFiles();
        if (list != null) {
            for (File f : list) {
                if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
            }
        }
        return null;
    }

    private static List<String> readNames(File file) {
        List<String> names = new ArrayList<>();
        if (file == null || !file.isFile()) return names;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("*")) line = line.substring(1).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if (!containsIgnoreCase(names, line)) names.add(line);
            }
        } catch (Exception ignored) {
        }
        return names;
    }

    /** Finds a directory component regardless of case, without creating it. */
    private static File resolve(File base, String... parts) {
        File dir = base;
        for (String name : parts) {
            File next = null;
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (f.isDirectory() && f.getName().equalsIgnoreCase(name)) {
                        next = f;
                        break;
                    }
                }
            }
            dir = next != null ? next : new File(dir, name);
        }
        return dir;
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        for (String s : list) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }
}
