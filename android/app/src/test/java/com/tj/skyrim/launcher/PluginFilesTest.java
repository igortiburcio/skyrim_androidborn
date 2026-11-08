package com.tj.skyrim.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The Plugins.txt/loadorder.txt pair the game itself reads. */
public class PluginFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void rankKeepsOfficialMastersInOrderAndThenGroupsTheRest() {
        assertEquals(0, PluginFiles.rank("Skyrim.esm"));
        assertEquals(1, PluginFiles.rank("Update.esm"));
        assertEquals(2, PluginFiles.rank("Dawnguard.esm"));
        assertEquals(3, PluginFiles.rank("HearthFires.esm"));
        assertEquals(4, PluginFiles.rank("Dragonborn.esm"));
        assertEquals(10, PluginFiles.rank("Unofficial Patch.esm"));
        assertEquals(20, PluginFiles.rank("MyMod.esp"));

        assertTrue(PluginFiles.isOfficialMaster("update.esm"));
        assertFalse(PluginFiles.isOfficialMaster("Unofficial Patch.esm"));
    }

    @Test
    public void writePutsTheOrderAndActiveSetInBothLocations() throws Exception {
        File game = tmp.newFolder("game");

        PluginFiles.write(game.getPath(), Arrays.asList(
                new Plugin("Skyrim.esm", true),
                new Plugin("Update.esm", false),
                new Plugin("Mod.esp", true)));

        for (File dir : new File[] {
                PluginFiles.gameDir(game.getPath()), PluginFiles.iniDir(game.getPath())}) {
            assertNotNull(dir);
            assertEquals("Skyrim.esm\nUpdate.esm\nMod.esp\n",
                    Fs.readText(new File(dir, "Plugins.txt")));
            assertEquals("Skyrim.esm\nUpdate.esm\nMod.esp\n",
                    Fs.readText(new File(dir, "loadorder.txt")));
        }
    }

    @Test
    public void writeReadsBackTheSameOrder() throws Exception {
        File game = tmp.newFolder("game");

        PluginFiles.write(game.getPath(), Arrays.asList(
                new Plugin("Skyrim.esm", true),
                new Plugin("B.esp", false),
                new Plugin("A.esp", true)));

        assertEquals(Arrays.asList("Skyrim.esm", "B.esp", "A.esp"),
                PluginFiles.readOrder(game.getPath()));
        assertEquals(Arrays.asList("Skyrim.esm", "A.esp"),
                PluginFiles.readActive(game.getPath()));
    }

    @Test
    public void readOrderFallsBackToTheActiveList() throws Exception {
        File game = tmp.newFolder("game");
        writeFile(PluginFiles.gameDir(game.getPath()), "Plugins.txt", "A.esp\r\nB.esp\r\n");

        assertEquals(Arrays.asList("A.esp", "B.esp"), PluginFiles.readOrder(game.getPath()));
    }

    @Test
    public void readNamesStripsStarsCommentsAndDuplicates() throws Exception {
        File game = tmp.newFolder("game");
        writeFile(PluginFiles.gameDir(game.getPath()), "Plugins.txt",
                "*A.esp\r\n# comment\r\n; comment\r\n\r\nA.esp\r\nB.esp\r\n");

        List<String> active = PluginFiles.readActive(game.getPath());

        assertEquals(Arrays.asList("A.esp", "B.esp"), active);
    }

    @Test
    public void writeRemovesAStaleDuplicateWithAnotherCase() throws Exception {
        File game = tmp.newFolder("game");
        File dir = PluginFiles.gameDir(game.getPath());
        assertTrue(dir.mkdirs());
        File probe = new File(dir, "CaseProbe");
        assertTrue(probe.createNewFile());
        Assume.assumeFalse("case-insensitive filesystem", new File(dir, "caseprobe").exists());
        assertTrue(probe.delete());
        writeFile(dir, "plugins.txt", "stale\r\n");
        writeFile(dir, "Plugins.txt", "old\r\n");

        PluginFiles.write(game.getPath(), Arrays.asList(new Plugin("Skyrim.esm", true)));

        assertFalse(new File(dir, "plugins.txt").exists());
        assertEquals("Skyrim.esm\n", Fs.readText(new File(dir, "Plugins.txt")));
    }

    @Test
    public void lastModifiedIgnoresAFolderWithoutPluginFiles() throws Exception {
        File game = tmp.newFolder("game");

        assertEquals(0, PluginFiles.lastModified(game.getPath()));
    }

    @Test
    public void gameDirFindsTheFolderRegardlessOfCase() throws Exception {
        File game = tmp.newFolder("game");
        File dir = new File(game, "Port/Save/Documents/Skyrim");
        assertTrue(dir.mkdirs());
        assertTrue(new File(dir, "Plugins.txt").createNewFile());

        File resolved = PluginFiles.gameDir(game.getPath());

        assertEquals(dir.getCanonicalPath(), resolved.getCanonicalPath());
    }

    /* ------------------------------------------------------------------ */

    private static void writeFile(File dir, String name, String content) throws Exception {
        assertTrue(dir.mkdirs() || dir.isDirectory());
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, name)))) {
            w.write(content);
        }
    }
}
