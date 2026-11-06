package com.tj.skyrim.launcher;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Data Files in load order. Scans the Data folder for .esm/.esp files,
 * keeps the order and the active set the user saved, appends files that
 * appeared since, and drops files that went away. Masters come before plugins
 * (Skyrim.esm first); inside each group the order is alphabetical unless the
 * user moved something.
 */
public final class PluginRegistry {

    private final LauncherSettings settings;
    private final List<Plugin> plugins = new ArrayList<>();

    public PluginRegistry(LauncherSettings settings) {
        this.settings = settings;
    }

    /** The plugins in the current load order; Skyrim.esm is always first. */
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
            String low = f.toLowerCase();
            if (low.endsWith(".esm") || low.endsWith(".esp")) names.add(f);
        }

        List<String> order = settings.pluginOrder();
        List<String> on = settings.pluginsOn();
        boolean firstRun = order.isEmpty();

        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        names.sort((a, b) -> Boolean.compare(!isMaster(a), !isMaster(b)));
        for (String n : names) plugins.add(new Plugin(n, on.contains(n) || (firstRun && isMaster(n))));

        /* the saved order wins over the alphabetical one (stable sort) */
        plugins.sort((a, b) -> {
            int ia = order.indexOf(a.name), ib = order.indexOf(b.name);
            return Integer.compare(ia == -1 ? Integer.MAX_VALUE : ia, ib == -1 ? Integer.MAX_VALUE : ib);
        });
    }

    /** Saves the order and the active set. */
    void save() {
        List<String> order = new ArrayList<>(), on = new ArrayList<>();
        for (Plugin p : plugins) {
            order.add(p.name);
            if (p.on || p.alwaysOn()) on.add(p.name);
        }
        settings.pluginOrder(order);
        settings.pluginsOn(on);
    }

    /** Moves a plugin one way or the other in the load order. */
    void move(int from, int by) {
        int to = from + by;
        if (from < 0 || from >= plugins.size() || to < 0 || to >= plugins.size()) return;
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

    private static boolean isMaster(String name) {
        return name.toLowerCase().endsWith(".esm");
    }
}
