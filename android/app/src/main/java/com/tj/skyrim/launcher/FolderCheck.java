package com.tj.skyrim.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a folder is a playable Skyrim install: a Data folder with
 * Skyrim.esm, the Steamless-unpacked exe, the three DLLs the port lifts code
 * from, and — when the packed exe is there — the exact Steam TESV.exe the
 * recompiled code was generated from (other versions have different code at
 * different addresses and crash).
 */
public final class FolderCheck {

    /** SHA-1 of the Steam TESV.exe (1.9.32.0.8). */
    private static final String SHA_TESV_STEAM = "f39aecebf6e332616c4c518617073f6e8250e0f8";

    private static final String[] REQUIRED_DLLS = {"d3dx9_42.dll", "X3DAudio1_7.dll", "binkw32.dll"};

    private final LauncherSettings settings;

    public FolderCheck(LauncherSettings settings) {
        this.settings = settings;
    }

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

        // Only the packed exe is checked: Steamless output differs between Steamless versions.
        File packed = Fs.findCi(dir, "TESV.exe");
        if (packed != null) checkSha(packed, SHA_TESV_STEAM, "TESV.exe", bad);
        if (Fs.findCi(dir, "TESV_unpacked.exe") == null && Fs.findCi(dir, "TESV.exe.unpacked.exe") == null)
            bad.add(packed != null ? "TESV_unpacked.exe missing (unpack TESV.exe with Steamless)"
                                   : "TESV_unpacked.exe missing");

        for (String n : REQUIRED_DLLS) if (Fs.findCi(dir, n) == null) bad.add(n + " missing");
        return bad;
    }

    private void checkSha(File f, String want, String name, List<String> bad) {
        String got = sha1(f);
        if (got == null) bad.add("cannot read " + name);
        else if (!got.equals(want)) bad.add(name + " is the wrong version (SHA-1 " + got.substring(0, 8)
                + ", expected " + want.substring(0, 8) + ")");
    }

    /** SHA-1 as hex, cached; null when the file cannot be read. */
    private String sha1(File f) {
        String cached = settings.cachedSha1(f);
        if (cached != null) return cached;
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            settings.cacheSha1(f, sb.toString());
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
