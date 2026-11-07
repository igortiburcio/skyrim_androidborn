package com.tj.skyrim.launcher;

/**
 * One .esm/.esp in the Data folder, with its position in the load order and
 * whether it is active. Skyrim.esm is special: always first and always on.
 */
public final class Plugin {

    final String name;
    boolean on;

    Plugin(String name, boolean on) {
        this.name = name;
        this.on = on;
    }

    boolean isMaster() {
        return name.toLowerCase().endsWith(".esm");
    }

    /** One of the five masters the engine requires at the top. */
    boolean officialMaster() {
        return PluginFiles.isOfficialMaster(name);
    }

    /** Skyrim.esm and Update.esm: the engine and the DLCs depend on them. */
    boolean alwaysOn() {
        return name.equalsIgnoreCase("Skyrim.esm") || name.equalsIgnoreCase("Update.esm");
    }
}
