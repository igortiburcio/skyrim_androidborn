package com.tj.skyrim.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Removing a game data folder must never touch the port (saves and logs). */
public class GameDataRemovalTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void removesDataAndAppInfoButKeepsThePort() throws Exception {
        File root = tmp.newFolder("game");
        File data = new File(root, "Data/meshes");
        assertTrue(data.mkdirs());
        assertTrue(new File(data, "a.nif").createNewFile());
        assertTrue(new File(root, "appinfo.bin").createNewFile());
        File save = new File(root, "port/save");
        assertTrue(save.mkdirs());
        assertTrue(new File(save, "save1.ess").createNewFile());

        assertTrue(GameDataRemoval.remove(root));

        assertFalse(new File(root, "Data").exists());
        assertFalse(new File(root, "appinfo.bin").exists());
        assertTrue(new File(save, "save1.ess").isFile());
    }

    @Test
    public void succeedsWhenThereIsNothingToRemove() throws Exception {
        File root = tmp.newFolder("empty");

        assertTrue(GameDataRemoval.remove(root));
    }

    @Test
    public void findsTheDataFolderRegardlessOfCase() throws Exception {
        File root = tmp.newFolder("game");
        assertTrue(new File(root, "data").mkdirs());

        assertTrue(GameDataRemoval.remove(root));

        assertFalse(new File(root, "data").exists());
    }
}
