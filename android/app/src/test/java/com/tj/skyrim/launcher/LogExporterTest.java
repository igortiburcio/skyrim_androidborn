package com.tj.skyrim.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** What Export logs picks up next to the log itself. */
public class LogExporterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void faceDumpsListsOnlyDdsFilesInNameOrder() throws Exception {
        File game = tmp.newFolder("game");
        File dumps = new File(game, "port/save/texdump");
        assertTrue(dumps.mkdirs());
        write(new File(dumps, "tint_2_b.dds"));
        write(new File(dumps, "tint_1_a.dds"));
        write(new File(dumps, "notes.txt"));

        List<File> found = LogExporter.faceDumps(game.getAbsolutePath());

        assertEquals(2, found.size());
        assertEquals("tint_1_a.dds", found.get(0).getName());
        assertEquals("tint_2_b.dds", found.get(1).getName());
    }

    @Test
    public void faceDumpsIsEmptyWithoutTheFolder() throws Exception {
        File game = tmp.newFolder("game");
        assertTrue(LogExporter.faceDumps(game.getAbsolutePath()).isEmpty());
    }

    private static void write(File file) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {1, 2, 3});
        }
    }
}
