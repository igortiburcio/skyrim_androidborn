package com.tj.skyrim.launcher;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Data Files in load order. Scans the Data folder for .esm/.esp files and
 * merges them with the order and active set saved by the launcher. On the
 * first run (or after the game's files were edited elsewhere) it imports the
 * game's own Plugins.txt/loadorder.txt instead of inventing an alphabetical
 * order. The five official masters always stay at the top in their required
 * order; mods keep the order the user chose.
 */
public final class PluginRegistry {

    private final LauncherSettings settings;
    private final List<Plugin> plugins = new ArrayList<>();

    public PluginRegistry(LauncherSettings settings) {
        this.settings = settings;
    }

    /** The plugins in the current load order. */
    List<Plugin> plugins() {
        return plugins;
    }

    /** Re-reads the Data folder and merges it with what was saved. */
    void reload() {
        plugins.clear();

        File data = dataDir();
        if (data == null || !data.isDirectory()) return;

        List<String> names = new ArrayList<>();
        String[] listing = data.list();
        if (listing == null) return;
        for (String f : listing) {
            String low = f.toLowerCase(Locale.ROOT);
            if (low.endsWith(".esm") || low.endsWith(".esp")) names.add(f);
        }
        /* Deterministic base for plugins that are new to the launcher. */
        names.sort(String.CASE_INSENSITIVE_ORDER);

        String folder = settings.folder();
        boolean external = PluginFiles.lastModified(folder) > settings.pluginsWrittenAt();
        List<String> order = settings.pluginOrder();
        List<String> on = settings.pluginsOn();
        if (external) {
            List<String> fileOrder = PluginFiles.readOrder(folder);
            List<String> fileActive = PluginFiles.readActive(folder);
            if (!fileOrder.isEmpty()) {
                List<String> merged = new ArrayList<>(fileOrder);
                for (String n : order) if (!containsIgnoreCase(merged, n)) merged.add(n);
                order = merged;
            }
            if (!fileActive.isEmpty()) on = fileActive;
        }

        boolean firstRun = order.isEmpty() && !external;
        Set<String> active = lowerSet(on);
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.putIfAbsent(order.get(i).toLowerCase(Locale.ROOT), i);
        }

        for (String n : names) {
            boolean isOn = active.contains(n.toLowerCase(Locale.ROOT))
                    || (firstRun && PluginFiles.isOfficialMaster(n));
            plugins.add(new Plugin(n, isOn));
        }

        /* Official masters first in their required order, then other ESMs,
         * then ESPs; inside a group the saved (or imported) order wins. */
        plugins.sort((a, b) -> {
            int ra = PluginFiles.rank(a.name), rb = PluginFiles.rank(b.name);
            if (ra != rb) return Integer.compare(ra, rb);
            Integer ia = position.get(a.name.toLowerCase(Locale.ROOT));
            Integer ib = position.get(b.name.toLowerCase(Locale.ROOT));
            return Integer.compare(ia == null ? Integer.MAX_VALUE : ia,
                    ib == null ? Integer.MAX_VALUE : ib);
        });

        for (Plugin p : plugins) if (p.alwaysOn()) p.on = true;

        /* The game's files were newer than our last write: adopt them and
         * write the normalized version back. */
        if (external) save();
    }

    /** Saves the order and active set, and updates the game's files at once. */
    void save() {
        /* The folder may be unmounted or unreadable: never erase the game's
         * plugin files just because the scan found nothing. */
        if (plugins.isEmpty() && dataDir() == null) return;
        List<String> order = new ArrayList<>(), on = new ArrayList<>();
        for (Plugin p : plugins) {
            order.add(p.name);
            if (p.on || p.alwaysOn()) on.add(p.name);
        }
        settings.pluginOrder(order);
        settings.pluginsOn(on);
        String folder = settings.folder();
        if (folder != null) {
            PluginFiles.write(folder, plugins);
            settings.pluginsWrittenAt(
                    Math.max(System.currentTimeMillis(), PluginFiles.lastModified(folder)));
        }
    }

    /** Moves a plugin one way or the other inside its rank group. */
    void move(int from, int by) {
        int to = from + by;
        if (from < 0 || from >= plugins.size() || to < 0 || to >= plugins.size()) return;
        Plugin moving = plugins.get(from);
        if (moving.officialMaster()) return;
        if (PluginFiles.rank(moving.name) != PluginFiles.rank(plugins.get(to).name)) return;
        plugins.add(to, plugins.remove(from));
    }

    boolean isEmpty() {
        return plugins.isEmpty();
    }

    /** The Data folder of the chosen Skyrim install. */
    File dataDir() {
        String path = settings.folder();
        return path == null ? null : Fs.findCi(new File(path), "Data");
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        for (String s : list) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static Set<String> lowerSet(List<String> names) {
        Set<String> set = new HashSet<>();
        for (String s : names) set.add(s.toLowerCase(Locale.ROOT));
        return set;
    }
}
