package com.tj.skyrim.launcher;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a folder is a playable Skyrim install: a Data folder with
 * Skyrim.esm. The exe and the three DLLs are mapped from the images compiled
 * into the app (gen/image_*.c), so only the game data lives in the folder.
 */
public final class FolderCheck {

    /** The problems with the folder; empty when it is ready to play. */
    List<String> problems(String path) {
        List<String> bad = new ArrayList<>();
        if (path == null) {
            bad.add("no folder chosen");
            return bad;
        }
        File dir = new File(path);
        if (!dir.isDirectory()) {
            bad.add("folder not found");
            return bad;
        }

        File data = Fs.findCi(dir, "Data");
        File esm = data != null ? Fs.findCi(data, "Skyrim.esm") : null;
        if (data == null) bad.add("Data folder missing");
        else if (esm == null) bad.add(data.canRead() ? "Data/Skyrim.esm missing" : "cannot read the Data folder");
        else if (!esm.canRead()) bad.add("cannot read Data/Skyrim.esm");
        return bad;
    }
}
