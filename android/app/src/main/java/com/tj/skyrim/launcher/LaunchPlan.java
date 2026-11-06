package com.tj.skyrim.launcher;

import java.util.List;

/**
 * Everything one Play needs, frozen at the moment the button was pressed:
 * the game folder, the chosen options, the load order and the GPU driver
 * (null for the system driver).
 */
public final class LaunchPlan {

    final String folder;
    final LauncherChoices choices;
    final List<Plugin> plugins;
    final Driver driver;

    public LaunchPlan(String folder, LauncherChoices choices, List<Plugin> plugins, Driver driver) {
        this.folder = folder;
        this.choices = choices;
        this.plugins = plugins;
        this.driver = driver;
    }
}
