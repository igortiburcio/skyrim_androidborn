package com.tj.skyrim.launcher;

import java.io.File;

/** Removes a Skyrim game data folder while keeping the port (saves and logs). */
public final class GameDataRemoval {

    private GameDataRemoval() {}

    /**
     * Deletes the Data folder and the generated appinfo.bin under {@code root}.
     * Everything else (port/save) is left untouched.
     *
     * @return true when the Data folder is gone.
     */
    public static boolean remove(File root) {
        File data = Fs.findCi(root, "Data");
        if (data != null) {
            Fs.deleteTree(data);
        }
        File appinfo = Fs.findCi(root, "appinfo.bin");
        if (appinfo != null) {
            Fs.deleteTree(appinfo);
        }
        return data == null || !data.exists();
    }
}
