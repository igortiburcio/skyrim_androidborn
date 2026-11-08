package com.tj.skyrim.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Load-order merging, tested through the settings seam instead of Android. */
public class PluginRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void firstRunActivatesOnlyTheOfficialMasters() throws Exception {
        File game = gameWith("Skyrim.esm", "Update.esm", "Other.esm", "Mod.esp");
        FakeSettings settings = new FakeSettings(game);

        PluginRegistry registry = new PluginRegistry(settings);
        registry.reload();

        assertEquals(Arrays.asList("Skyrim.esm", "Update.esm", "Other.esm", "Mod.esp"),
                names(registry));
        assertEquals(Arrays.asList("Skyrim.esm", "Update.esm"), onNames(registry));
    }

    @Test
    public void theSavedOrderWinsInsideAGroup() throws Exception {
        File game = gameWith("A.esp", "B.esp");
        FakeSettings settings = new FakeSettings(game);
        settings.order.addAll(Arrays.asList("B.esp", "A.esp"));
        settings.on.add("B.esp");
        settings.written = future();

        PluginRegistry registry = new PluginRegistry(settings);
        registry.reload();

        assertEquals(Arrays.asList("B.esp", "A.esp"), names(registry));
        assertEquals(Arrays.asList("B.esp"), onNames(registry));
    }

    @Test
    public void officialMastersKeepTheirRequiredOrder() throws Exception {
        File game = gameWith("Dragonborn.esm", "HearthFires.esm", "Dawnguard.esm",
                "Update.esm", "Skyrim.esm");
        FakeSettings settings = new FakeSettings(game);
        settings.order.addAll(Arrays.asList("Dragonborn.esm", "HearthFires.esm",
                "Dawnguard.esm", "Update.esm", "Skyrim.esm"));
        settings.written = future();

        PluginRegistry registry = new PluginRegistry(settings);
        registry.reload();

        assertEquals(Arrays.asList("Skyrim.esm", "Update.esm", "Dawnguard.esm",
                "HearthFires.esm", "Dragonborn.esm"), names(registry));
    }

    @Test
    public void skyrimAndUpdateCannotBeDisabled() throws Exception {
        File game = gameWith("Skyrim.esm", "Update.esm", "Mod.esp");
        FakeSettings settings = new FakeSettings(game);
        settings.order.addAll(Arrays.asList("Mod.esp", "Update.esm", "Skyrim.esm"));
        settings.on.add("Mod.esp");
        settings.written = future();

        PluginRegistry registry = new PluginRegistry(settings);
        registry.reload();

        assertEquals(Arrays.asList("Skyrim.esm", "Update.esm", "Mod.esp"),
                onNames(registry));
    }

    @Test
    public void gameFilesAreImportedWhenTheyAreNewer() throws Exception {
        File game = gameWith("Skyrim.esm", "Mod.esp");
        FakeSettings settings = new FakeSettings(game);
        settings.written = 0;
        File dir = PluginFiles.gameDir(game.getPath());
        assertTrue(dir.mkdirs());
        write(new File(dir, "loadorder.txt"), "Mod.esp\r\nSkyrim.esm\r\n");
        write(new File(dir, "Plugins.txt"), "Mod.esp\r\n");

        PluginRegistry registry = new PluginRegistry(settings);
        registry.reload();

        assertEquals(Arrays.asList("Skyrim.esm", "Mod.esp"), names(registry));
        assertEquals(Arrays.asList("Skyrim.esm", "Mod.esp"), onNames(registry));
        assertEquals(Arrays.asList("Skyrim.esm", "Mod.esp"), settings.order);
        assertEquals(Arrays.asList("Skyrim.esm", "Mod.esp"), settings.on);
    }

    @Test
    public void removedPluginsAreDroppedAndNewOnesAppended() throws Exception {
        File game = gameWith("Mod.esp", "New.esp");
        FakeSettings settings = new FakeSettings(game);
        settings.order.addAll(Arrays.asList("Gone.esp", "Mod.esp"));
        settings.on.addAll(Arrays.asList("Gone.esp", "Mod.esp"));
        settings.written = future();

        PluginRegistry registry = new PluginRegistry(settings);
        registry.reload();

        assertEquals(Arrays.asList("Mod.esp", "New.esp"), names(registry));
        assertEquals(Arrays.asList("Mod.esp"), onNames(registry));
    }

    @Test
    public void moveOnlyWorksInsideTheSameRankGroup() throws Exception {
        File game = gameWith("A.esp", "B.esp", "C.esp");
        FakeSettings settings = new FakeSettings(game);
        settings.order.addAll(Arrays.asList("A.esp", "B.esp", "C.esp"));
        settings.written = future();

        PluginRegistry registry = new PluginRegistry(settings);
        registry.reload();
        assertEquals(Arrays.asList("A.esp", "B.esp", "C.esp"), names(registry));

        registry.move(0, 1);
        assertEquals(Arrays.asList("B.esp", "A.esp", "C.esp"), names(registry));

        registry.move(0, -1);
        registry.move(2, 1);
        assertEquals(Arrays.asList("B.esp", "A.esp", "C.esp"), names(registry));

        File masters = gameWith("Skyrim.esm", "Update.esm");
        FakeSettings masterSettings = new FakeSettings(masters);
        PluginRegistry masterRegistry = new PluginRegistry(masterSettings);
        masterRegistry.reload();
        masterRegistry.move(0, 1);
        assertEquals(Arrays.asList("Skyrim.esm", "Update.esm"), names(masterRegistry));

        File mixed = gameWith("A.esp", "B.esm");
        FakeSettings mixedSettings = new FakeSettings(mixed);
        mixedSettings.order.addAll(Arrays.asList("B.esm", "A.esp"));
        mixedSettings.written = future();
        PluginRegistry mixedRegistry = new PluginRegistry(mixedSettings);
        mixedRegistry.reload();
        mixedRegistry.move(0, 1);
        assertEquals(Arrays.asList("B.esm", "A.esp"), names(mixedRegistry));
    }

    @Test
    public void saveWritesTheNormalizedOrderAndThePluginFiles() throws Exception {
        File game = gameWith("Skyrim.esm", "Mod.esp");
        FakeSettings settings = new FakeSettings(game);
        settings.order.addAll(Arrays.asList("Mod.esp", "Skyrim.esm"));
        settings.on.add("Mod.esp");
        settings.written = future();

        PluginRegistry registry = new PluginRegistry(settings);
        registry.reload();
        registry.save();

        assertEquals(Arrays.asList("Skyrim.esm", "Mod.esp"), settings.order);
        assertEquals(Arrays.asList("Skyrim.esm", "Mod.esp"), settings.on);
        File dir = PluginFiles.gameDir(game.getPath());
        assertEquals("Skyrim.esm\nMod.esp\n", Fs.readText(new File(dir, "Plugins.txt")));
        assertEquals("Skyrim.esm\nMod.esp\n", Fs.readText(new File(dir, "loadorder.txt")));
    }

    @Test
    public void noDataFolderMeansNoPlugins() throws Exception {
        File game = tmp.newFolder("game");
        FakeSettings settings = new FakeSettings(game);

        PluginRegistry registry = new PluginRegistry(settings);
        registry.reload();

        assertTrue(registry.isEmpty());
        assertTrue(registry.plugins().isEmpty());
    }

    @Test
    public void dataFolderIsFoundRegardlessOfCase() throws Exception {
        File game = tmp.newFolder("game");
        File data = new File(game, "data");
        assertTrue(data.mkdirs());
        assertTrue(new File(data, "Mod.esp").createNewFile());
        FakeSettings settings = new FakeSettings(game);

        PluginRegistry registry = new PluginRegistry(settings);
        registry.reload();

        assertFalse(registry.isEmpty());
        assertEquals(Arrays.asList("Mod.esp"), names(registry));
    }

    /* ------------------------------------------------------------------ */

    private File gameWith(String... pluginNames) throws Exception {
        File game = tmp.newFolder();
        File data = new File(game, "Data");
        assertTrue(data.mkdirs());
        for (String name : pluginNames) {
            assertTrue(new File(data, name).createNewFile());
        }
        return game;
    }

    private static List<String> names(PluginRegistry registry) {
        List<String> names = new ArrayList<>();
        for (Plugin p : registry.plugins()) names.add(p.name);
        return names;
    }

    private static List<String> onNames(PluginRegistry registry) {
        List<String> names = new ArrayList<>();
        for (Plugin p : registry.plugins()) if (p.on) names.add(p.name);
        return names;
    }

    private static long future() {
        return System.currentTimeMillis() + 3600_000L;
    }

    private static void write(File f, String text) throws Exception {
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(f))) {
            w.write(text);
        }
    }

    private static final class FakeSettings implements PluginSettings {
        String folder;
        final List<String> order = new ArrayList<>();
        final List<String> on = new ArrayList<>();
        long written;

        FakeSettings(File folder) {
            this.folder = folder.getPath();
        }

        @Override
        public String folder() {
            return folder;
        }

        @Override
        public List<String> pluginOrder() {
            return new ArrayList<>(order);
        }

        @Override
        public void pluginOrder(List<String> names) {
            order.clear();
            order.addAll(names);
        }

        @Override
        public List<String> pluginsOn() {
            return new ArrayList<>(on);
        }

        @Override
        public void pluginsOn(List<String> names) {
            on.clear();
            on.addAll(names);
        }

        @Override
        public long pluginsWrittenAt() {
            return written;
        }

        @Override
        public void pluginsWrittenAt(long time) {
            written = time;
        }
    }
}
