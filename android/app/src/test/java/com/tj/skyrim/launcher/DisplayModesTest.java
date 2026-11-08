package com.tj.skyrim.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Resolution lists handed to the game; these must match what the runtime accepts. */
public class DisplayModesTest {

    /** Landscape 20:9 phone, the common case for the launcher. */
    private static final int W = 2400, H = 1080;

    @Test
    public void listStartsAtTheLargestModeAndOnlyGetsSmaller() {
        int[][] modes = new DisplayModes(W, H).list(0);

        assertArrayEquals(new int[] {1920, 1080}, modes[0]);
        for (int i = 1; i < modes.length; i++) {
            assertTrue("heights must go down", modes[i][1] < modes[i - 1][1]);
        }
        for (int[] mode : modes) {
            assertTrue("width must be positive", mode[0] > 0);
            assertEquals("width must be even", 0, mode[0] & 1);
        }
    }

    @Test
    public void everyAspectKeepsItsShape() {
        DisplayModes modes = new DisplayModes(W, H);

        assertEquals(1728, modes.list(1)[0][0]);  // 16:10
        assertEquals(1440, modes.list(2)[0][0]);  // 4:3
        assertEquals(2398, modes.list(3)[0][0]);  // 21:9
        assertEquals(2400, modes.list(4)[0][0]);  // Screen
    }

    @Test
    public void screenAspectIsTheDisplayItself() {
        DisplayModes modes = new DisplayModes(W, H);

        assertArrayEquals(new int[] {W, H}, modes.aspect(4));
        assertArrayEquals(new int[] {0, 0}, modes.rawAspect(4));
        assertArrayEquals(new int[] {16, 9}, modes.rawAspect(0));
    }

    @Test
    public void rawAspectReturnsACopy() {
        DisplayModes modes = new DisplayModes(W, H);

        int[] first = modes.rawAspect(0);
        first[0] = 99;

        assertArrayEquals(new int[] {16, 9}, modes.rawAspect(0));
    }

    @Test
    public void defaultIsTheLargestModeUpTo540() {
        DisplayModes modes = new DisplayModes(W, H);
        int index = modes.defaultIndex(0);
        int[][] list = modes.list(0);

        assertEquals(540, list[index][1]);
        assertTrue(list[index - 1][1] > 540);
    }

    @Test
    public void namesMatchTheList() {
        DisplayModes modes = new DisplayModes(W, H);
        int[][] list = modes.list(0);
        String[] names = modes.names(0);

        assertEquals(list.length, names.length);
        assertEquals("1920 x 1080", names[0]);
    }

    @Test
    public void veryWideScreenDoesNotOverflow() {
        DisplayModes modes = new DisplayModes(3200, 1440);

        for (int aspect = 0; aspect < DisplayModes.ASPECTS.length; aspect++) {
            for (int[] mode : modes.list(aspect)) {
                assertTrue(mode[0] > 0 && mode[0] <= 3200);
                assertTrue(mode[1] > 0 && mode[1] <= 1440);
            }
        }
    }
}
