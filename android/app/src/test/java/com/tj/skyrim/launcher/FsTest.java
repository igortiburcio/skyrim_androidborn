package com.tj.skyrim.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Case-insensitive lookup and recursive deletion used by the launcher. */
public class FsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void findCiFindsAnotherCase() throws Exception {
        File dir = tmp.newFolder("Data");
        File real = new File(dir, "Skyrim.esm");
        assertTrue(real.createNewFile());

        File found = Fs.findCi(dir, "skyrim.esm");

        assertNotNull(found);
        assertEquals(real.getCanonicalPath(), found.getCanonicalPath());
    }

    @Test
    public void findCiReturnsNullForMissingAndNullDirectory() {
        assertNull(Fs.findCi(tmp.getRoot(), "missing.esp"));
        assertNull(Fs.findCi(null, "anything"));
    }

    @Test
    public void deleteTreeRemovesNestedContent() throws Exception {
        File root = tmp.newFolder("game");
        File data = new File(root, "Data/meshes");
        assertTrue(data.mkdirs());
        assertTrue(new File(data, "a.nif").createNewFile());

        Fs.deleteTree(new File(root, "Data"));

        assertFalse(new File(root, "Data").exists());
    }

    @Test
    public void readTextReturnsOneLinePerReadLine() throws Exception {
        File f = tmp.newFile("f.txt");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) {
            w.write("one\r\ntwo\nthree");
        }

        assertEquals("one\ntwo\nthree\n", Fs.readText(f));
    }

    @Test
    public void readTextReturnsEmptyWhenMissing() {
        assertEquals("", Fs.readText(new File(tmp.getRoot(), "nope.txt")));
    }
}
