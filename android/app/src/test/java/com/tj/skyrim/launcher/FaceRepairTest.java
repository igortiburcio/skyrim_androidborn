package com.tj.skyrim.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The Support tab's face repair switches. */
public class FaceRepairTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void enableWritesBothSwitches() throws Exception {
        File game = tmp.newFolder("game");

        File save = FaceRepair.enable(game);

        assertEquals(new File(game, "port/save"), save);
        assertTrue(new File(save, FaceRepair.FIX_SWITCH).isFile());
        assertTrue(new File(save, FaceRepair.TRACE_SWITCH).isFile());
        assertTrue(new File(save, FaceRepair.REBUILD_SWITCH).isFile());
        assertTrue(FaceRepair.enabled(game));
    }

    @Test
    public void enableIsIdempotentAndKeepsTheFolder() throws Exception {
        File game = tmp.newFolder("game");
        File save = FaceRepair.enable(game);
        assertTrue(new File(save, "save.ess").createNewFile());

        FaceRepair.enable(game);

        assertTrue(new File(save, "save.ess").isFile());
    }

    @Test
    public void enabledIsFalseBeforeTheFirstTap() throws Exception {
        File game = tmp.newFolder("game");

        assertFalse(FaceRepair.enabled(game));
        assertFalse(FaceRepair.enabled(null));
    }
}
