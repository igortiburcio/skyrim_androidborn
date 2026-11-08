package com.tj.skyrim.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/** The download state the foreground service and the launcher share. */
public class SteamDownloadStateTest {

    @Before
    public void reset() {
        SteamDownloadState.start(null);
    }

    @Test
    public void startResetsThePreviousRun() {
        SteamDownloadState.plan(2, 3, 1000);
        SteamDownloadState.progress(100, 1000, "a.bsa");
        SteamDownloadState.detail("working");

        SteamDownloadState.start("/games/skyrim");

        assertEquals(SteamDownloadState.RUNNING, SteamDownloadState.state());
        assertTrue(SteamDownloadState.isRunning());
        assertEquals(0, SteamDownloadState.done());
        assertEquals(0, SteamDownloadState.total());
        assertEquals(0, SteamDownloadState.depots());
        assertEquals(0, SteamDownloadState.files());
        assertEquals("", SteamDownloadState.file());
        assertEquals("", SteamDownloadState.detail());
        assertEquals("", SteamDownloadState.error());
        assertEquals("/games/skyrim", SteamDownloadState.target());
    }

    @Test
    public void nullTargetBecomesEmpty() {
        SteamDownloadState.start(null);

        assertEquals("", SteamDownloadState.target());
    }

    @Test
    public void progressKeepsTheKnownTotalWhenZeroIsReported() {
        SteamDownloadState.plan(1, 2, 500);

        SteamDownloadState.progress(10, 0, "a");
        assertEquals(500, SteamDownloadState.total());

        SteamDownloadState.progress(20, 40, "b");
        assertEquals(40, SteamDownloadState.total());
        assertEquals(20, SteamDownloadState.done());
        assertEquals("b", SteamDownloadState.file());
    }

    @Test
    public void doneCountsTheWholePlan() {
        SteamDownloadState.plan(1, 2, 1000);
        SteamDownloadState.progress(400, 1000, "a");

        SteamDownloadState.done("/games/skyrim");

        assertEquals(SteamDownloadState.DONE, SteamDownloadState.state());
        assertEquals(1000, SteamDownloadState.done());
        assertEquals("", SteamDownloadState.file());
        assertEquals("/games/skyrim", SteamDownloadState.target());
    }

    @Test
    public void doneWithoutAPathKeepsTheChosenTarget() {
        SteamDownloadState.start("/games/skyrim");

        SteamDownloadState.done(null);

        assertEquals("/games/skyrim", SteamDownloadState.target());
    }

    @Test
    public void errorFallsBackToAReadableMessage() {
        SteamDownloadState.error(null);
        assertEquals(SteamDownloadState.ERROR, SteamDownloadState.state());
        assertEquals("download failed", SteamDownloadState.error());

        SteamDownloadState.error("no connection");
        assertEquals("no connection", SteamDownloadState.error());
    }

    @Test
    public void cancelledStopsTheDownload() {
        SteamDownloadState.cancelled();

        assertEquals(SteamDownloadState.CANCELLED, SteamDownloadState.state());
        assertFalse(SteamDownloadState.isRunning());
    }

    @Test
    public void listenersAreNotifiedAndCanBeRemoved() {
        Counter counter = new Counter();
        SteamDownloadState.add(counter);
        try {
            assertEquals(0, counter.calls);

            SteamDownloadState.detail("x");
            assertEquals(1, counter.calls);

            SteamDownloadState.remove(counter);
            SteamDownloadState.detail("y");
            assertEquals(1, counter.calls);
        } finally {
            SteamDownloadState.remove(counter);
        }
    }

    @Test
    public void oneFailingListenerDoesNotStopTheOthers() {
        SteamDownloadState.Listener broken = new SteamDownloadState.Listener() {
            @Override
            public void onDownloadStateChanged() {
                throw new IllegalStateException("boom");
            }
        };
        Counter counter = new Counter();
        SteamDownloadState.add(broken);
        SteamDownloadState.add(counter);
        try {
            SteamDownloadState.detail("x");

            assertTrue(counter.calls > 0);
        } finally {
            SteamDownloadState.remove(broken);
            SteamDownloadState.remove(counter);
        }
    }

    @Test
    public void nullListenersAreIgnored() {
        SteamDownloadState.add(null);
        SteamDownloadState.remove(null);
    }

    private static final class Counter implements SteamDownloadState.Listener {
        int calls;

        @Override
        public void onDownloadStateChanged() {
            calls++;
        }
    }
}
