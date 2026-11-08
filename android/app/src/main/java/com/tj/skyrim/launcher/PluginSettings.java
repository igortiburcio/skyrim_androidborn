package com.tj.skyrim.launcher;

import java.util.List;

/**
 * The part of the launcher state {@link PluginRegistry} reads and writes.
 * {@link LauncherSettings} is the real implementation; the interface exists so
 * the load-order logic can be unit-tested on the JVM without Android's
 * SharedPreferences.
 */
public interface PluginSettings {

    /** The chosen game folder, or null when none was chosen yet. */
    String folder();

    List<String> pluginOrder();

    void pluginOrder(List<String> names);

    List<String> pluginsOn();

    void pluginsOn(List<String> names);

    /** When the launcher last wrote Plugins.txt/loadorder.txt. */
    long pluginsWrittenAt();

    void pluginsWrittenAt(long time);
}
