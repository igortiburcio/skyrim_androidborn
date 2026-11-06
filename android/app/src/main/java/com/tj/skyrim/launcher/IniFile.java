package com.tj.skyrim.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small .ini in the Skyrim format: {@code [section]} headers with
 * {@code key=value} lines. Reading keeps unknown keys and the casing the file
 * already uses, so a rewrite only changes what was explicitly put; writing
 * uses the CRLF and blank-line layout the game's own files use.
 */
final class IniFile {

    private final Map<String, LinkedHashMap<String, String>> sections = new LinkedHashMap<>();

    /** An empty file. */
    static IniFile empty() {
        return new IniFile();
    }

    /** What is in f; empty when f does not exist or cannot be read. */
    static IniFile load(File f) {
        IniFile ini = new IniFile();
        ini.merge(f);
        return ini;
    }

    /** Adds whatever is in f on top of the current content (f wins). */
    IniFile merge(File f) {
        if (f == null || !f.exists()) return this;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
            String section = "General", line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    int end = line.indexOf(']');
                    section = (end > 0 ? line.substring(1, end) : line.substring(1)).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) put(section, line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Exception ignored) {
        }
        return this;
    }

    /** Sets a value, reusing the section and key spelling already present. */
    void put(String section, String key, String value) {
        String sectionKey = section;
        for (String s : sections.keySet()) if (s.equalsIgnoreCase(section)) sectionKey = s;
        LinkedHashMap<String, String> map = sections.get(sectionKey);
        if (map == null) sections.put(sectionKey, map = new LinkedHashMap<>());
        String keyKey = key;
        for (String k : map.keySet()) if (k.equalsIgnoreCase(key)) keyKey = k;
        map.put(keyKey, value);
    }

    void save(File f) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) {
            for (Map.Entry<String, LinkedHashMap<String, String>> section : sections.entrySet()) {
                w.write("[" + section.getKey() + "]\r\n");
                for (Map.Entry<String, String> kv : section.getValue().entrySet())
                    w.write(kv.getKey() + "=" + kv.getValue() + "\r\n");
                w.write("\r\n");
            }
        }
    }
}
