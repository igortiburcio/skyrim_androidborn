package com.tj.skyrim.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Reading and rewriting a Skyrim .ini without losing what the game wrote. */
public class IniFileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readsSectionsInOrderAndWritesCrlf() throws Exception {
        File in = write("in.ini",
                "; a comment\n"
                        + "[Display]\n"
                        + "iSize W=1920\n"
                        + "iSize H=1080\n"
                        + "\n"
                        + "[Grass]\n"
                        + "bAllowCreateGrass=1\n");

        File out = new File(tmp.getRoot(), "out.ini");
        IniFile.load(in).save(out);

        assertEquals("[Display]\r\n"
                + "iSize W=1920\r\n"
                + "iSize H=1080\r\n"
                + "\r\n"
                + "[Grass]\r\n"
                + "bAllowCreateGrass=1\r\n"
                + "\r\n", text(out));
    }

    @Test
    public void keysBeforeAnySectionGoToGeneral() throws Exception {
        File in = write("in.ini",
                "bFull Screen=1\n"
                        + "[Display]\n"
                        + "iSize W=1\n");

        File out = new File(tmp.getRoot(), "out.ini");
        IniFile.load(in).save(out);

        assertEquals("[General]\r\n"
                + "bFull Screen=1\r\n"
                + "\r\n"
                + "[Display]\r\n"
                + "iSize W=1\r\n"
                + "\r\n", text(out));
    }

    @Test
    public void putReusesTheSpellingTheFileAlreadyUses() throws Exception {
        File in = write("in.ini",
                "[Display]\n"
                        + "iSize w=800\n");

        File out = new File(tmp.getRoot(), "out.ini");
        IniFile ini = IniFile.load(in);
        ini.put("display", "iSize W", "1920");
        ini.save(out);

        assertEquals("[Display]\r\n"
                + "iSize w=1920\r\n"
                + "\r\n", text(out));
    }

    @Test
    public void putAddsEachKeyOnce() throws Exception {
        File out = new File(tmp.getRoot(), "out.ini");
        IniFile ini = IniFile.empty();
        ini.put("Display", "iSize W", "1");
        ini.put("Display", "iSize W", "2");
        ini.save(out);

        String text = text(out);
        assertEquals(1, count(text, "iSize W="));
        assertTrue(text.contains("iSize W=2"));
    }

    @Test
    public void mergeLetsTheSecondFileWin() throws Exception {
        File base = write("base.ini",
                "[Display]\n"
                        + "iSize W=1\n"
                        + "Keep=base\n");
        File preset = write("preset.ini",
                "[Display]\n"
                        + "iSize W=2\n"
                        + "New=preset\n");

        File out = new File(tmp.getRoot(), "out.ini");
        IniFile.load(base).merge(preset).save(out);

        String text = text(out);
        assertTrue(text.contains("iSize W=2"));
        assertTrue(text.contains("Keep=base"));
        assertTrue(text.contains("New=preset"));
        assertFalse(text.contains("iSize W=1"));
    }

    @Test
    public void commentsBlankLinesAndHashLinesAreDropped() throws Exception {
        File in = write("in.ini",
                "; semicolon\n"
                        + "# hash\n"
                        + "\n"
                        + "[Display]\n"
                        + "; inside\n"
                        + "iSize W=1\n");

        File out = new File(tmp.getRoot(), "out.ini");
        IniFile.load(in).save(out);

        assertEquals("[Display]\r\niSize W=1\r\n\r\n", text(out));
    }

    @Test
    public void valueKeepsEqualsSignsAfterTheFirstOne() throws Exception {
        File in = write("in.ini",
                "[General]\n"
                        + "key=a=b\n");

        File out = new File(tmp.getRoot(), "out.ini");
        IniFile.load(in).save(out);

        assertTrue(text(out).contains("key=a=b"));
    }

    @Test
    public void emptySectionsAreDropped() throws Exception {
        File in = write("in.ini", "[Empty]\n");

        File out = new File(tmp.getRoot(), "out.ini");
        IniFile.load(in).save(out);

        assertEquals("", text(out));
    }

    @Test
    public void missingFileLoadsAsEmpty() throws Exception {
        File out = new File(tmp.getRoot(), "out.ini");
        IniFile.load(new File(tmp.getRoot(), "does-not-exist.ini")).save(out);

        assertEquals("", text(out));
    }

    /* ------------------------------------------------------------------ */

    private File write(String name, String content) throws IOException {
        File f = tmp.newFile(name);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) {
            w.write(content);
        }
        return f;
    }

    private static String text(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Reader r = new InputStreamReader(new FileInputStream(f))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = r.read(buffer)) > 0) {
                sb.append(buffer, 0, count);
            }
        }
        return sb.toString();
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }
}
