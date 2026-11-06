package com.tj.skyrim.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/** Small file helpers shared by the launcher: case-insensitive lookup inside a
 * folder, recursive deletion and reading a whole text file. */
final class Fs {

    private Fs() {}

    /** Finds a file in dir regardless of case (game folders come from Windows). */
    static File findCi(File dir, String name) {
        if (dir == null) return null;
        File f = new File(dir, name);
        if (f.exists()) return f;
        String[] l = dir.list();
        if (l != null) for (String s : l) if (s.equalsIgnoreCase(name)) return new File(dir, s);
        return null;
    }

    /** Deletes f and, when it is a directory, everything inside it. */
    static void deleteTree(File f) {
        if (f.isDirectory()) {
            String[] l = f.list();
            if (l != null) for (String c : l) deleteTree(new File(f, c));
        }
        f.delete();
    }

    /** The whole file as text, or "" when it cannot be read. */
    static String readText(File f) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
