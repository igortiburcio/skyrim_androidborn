package com.tj.skyrim.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Prepares the port's face-tint switches for the next launch.
 *
 * <p>Skyrim can lose a character's face colours (the "dark face" bug) after the
 * character's race changes, for example when the player becomes a vampire. The
 * runtime keeps the last good face tint when a rebuild comes out empty
 * ({@code facefix.txt}) and writes a DDS dump of what the game built
 * ({@code facetrace.txt}), so a report can show which of the two failed.
 *
 * <p>Both switches live in the game's {@code port/save} folder, next to the
 * other port options, and are read once when the game starts.
 */
public final class FaceRepair {

    /** The draw switch that turns the tint repair on. */
    public static final String FIX_SWITCH = "facefix.txt";
    /** The switch that logs and dumps every face tint the game builds. */
    public static final String TRACE_SWITCH = "facetrace.txt";

    private FaceRepair() {
    }

    /** The folder the runtime reads its switches from. */
    public static File saveDir(File gameFolder) {
        return new File(gameFolder, "port/save");
    }

    /** Writes both switches (empty files) and returns the save folder. */
    public static File enable(File gameFolder) throws IOException {
        File save = saveDir(gameFolder);
        if (!save.isDirectory() && !save.mkdirs()) {
            throw new IOException("Could not create " + save);
        }
        write(new File(save, FIX_SWITCH));
        write(new File(save, TRACE_SWITCH));
        return save;
    }

    /** True when the repair switch is already in place. */
    public static boolean enabled(File gameFolder) {
        return gameFolder != null && new File(saveDir(gameFolder), FIX_SWITCH).isFile();
    }

    private static void write(File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[0]);
        }
    }
}
