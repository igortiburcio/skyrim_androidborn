package com.tj.skyrim.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Everything the launcher writes before the game starts, checked as files. */
public class GameConfigWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writesTheChosenResolutionAndFullscreen() throws Exception {
        File game = tmp.newFolder("game");
        write(game, new Choices().width(1920).height(1080).build());

        File prefs = prefs(game);
        assertEquals("1920", iniValue(prefs, "Display", "iSize W"));
        assertEquals("1080", iniValue(prefs, "Display", "iSize H"));
        assertEquals("1", iniValue(prefs, "Display", "bFull Screen"));
    }

    @Test
    public void disablingShadowsTurnsOffEveryPass() throws Exception {
        File game = tmp.newFolder("game");
        write(game, new Choices().shadowDetail(3).disableShadows(true).build());

        File prefs = prefs(game);
        for (String key : new String[] {
                "bDrawShadows", "bDeferredShadows", "bDrawLandShadows",
                "bShadowsOnGrass", "bShadowMaskZPrepass"}) {
            assertEquals(key, "0", iniValue(prefs, "Display", key));
        }
        assertEquals("4096", iniValue(prefs, "Display", "iShadowMapResolution"));
        assertEquals("2048", iniValue(prefs, "Display", "iShadowMapResolutionSecondary"));
    }

    @Test
    public void extraLowAppliesTheWholeLowPreset() throws Exception {
        File game = tmp.newFolder("game");
        write(game, new Choices().preset(0).textureQuality(0).shadowDetail(0).build());

        File prefs = prefs(game);
        assertEquals("0", iniValue(prefs, "Display", "bDrawShadows"));
        assertEquals("512", iniValue(prefs, "Display", "iShadowMapResolution"));
        assertEquals("512", iniValue(prefs, "Display", "iShadowMapResolutionSecondary"));
        assertEquals("2", iniValue(prefs, "Display", "iTexMipMapSkip"));
        assertEquals("2.0", iniValue(prefs, "LOD", "fLODFadeOutMultObjects"));
        assertEquals("1.0", iniValue(prefs, "LOD", "fLODFadeOutMultItems"));
        assertEquals("100", iniValue(prefs, "Particles", "iMaxParticles"));
        assertEquals("0", iniValue(prefs, "Water", "bUseWaterReflections"));
        assertEquals("20", iniValue(prefs, "Trees", "uiMaxSkinnedTreesToRender"));

        File ini = skyrimIni(game);
        assertEquals("0", iniValue(ini, "Grass", "bAllowCreateGrass"));
        assertEquals("0.0000", iniValue(ini, "Trees", "fUpdateBudget"));
    }

    @Test
    public void optionIndexesMapToTheGamesValues() throws Exception {
        File game = tmp.newFolder("game");
        write(game, new Choices()
                .antialiasing(2).anisotropy(4).textureQuality(0).shadowDetail(1)
                .decals(3).blur(2).fade(3)
                .fxaa(true).vsync(true).depthOfField(false)
                .build());

        File prefs = prefs(game);
        assertEquals("4", iniValue(prefs, "Display", "iMultiSample"));
        assertEquals("16", iniValue(prefs, "Display", "iMaxAnisotropy"));
        assertEquals("2", iniValue(prefs, "Display", "iTexMipMapSkip"));
        assertEquals("1024", iniValue(prefs, "Display", "iShadowMapResolution"));
        assertEquals("1000", iniValue(prefs, "Decals", "uMaxDecals"));
        assertEquals("100", iniValue(prefs, "Decals", "uMaxSkinDecals"));
        assertEquals("100", iniValue(prefs, "Display", "iMaxDecalsPerFrame"));
        assertEquals("25", iniValue(prefs, "Display", "iMaxSkinDecalsPerFrame"));
        assertEquals("2", iniValue(prefs, "Imagespace", "iRadialBlurLevel"));
        assertEquals("0", iniValue(prefs, "Imagespace", "bDoDepthOfField"));
        assertEquals("9.0", iniValue(prefs, "LOD", "fLODFadeOutMultActors"));
        assertEquals("1", iniValue(prefs, "Display", "bFXAAEnabled"));
        assertEquals("1", iniValue(prefs, "Display", "iPresentInterval"));
    }

    @Test
    public void thePresetOnlySeedsWhatTheOptionsDoNotSet() throws Exception {
        File game = tmp.newFolder("game");
        File prefs = prefs(game);
        prefs.getParentFile().mkdirs();
        writeText(prefs, "[Display]\r\nCustom=keep\r\n");
        writeText(new File(game, "high.ini"),
                "[Display]\r\nbFull Screen=0\r\nbDrawShadows=1\r\n");

        write(game, new Choices().preset(3).build());

        assertEquals("keep", iniValue(prefs, "Display", "Custom"));
        assertEquals("1", iniValue(prefs, "Display", "bFull Screen"));
        assertEquals("1", iniValue(prefs, "Display", "bDrawShadows"));
    }

    @Test
    public void displayCfgUsesTheRawAspect() throws Exception {
        File game = tmp.newFolder("game");
        write(game, new Choices().aspect(1).width(1920).height(1200).build());
        assertEquals("16 10\n", Fs.readText(new File(game, "port/save/display.cfg")));

        write(game, new Choices().aspect(4).width(2400).height(1080).build());
        assertEquals("0 0\n", Fs.readText(new File(game, "port/save/display.cfg")));
    }

    @Test
    public void touchCfgCarriesTheCameraMode() throws Exception {
        File game = tmp.newFolder("game");

        write(game, new Choices().build());
        assertEquals("1 0 0\n", Fs.readText(new File(game, "port/save/touch.cfg")));

        write(game, new Choices().touchControls(false).nintendoLayout(true).cameraMode(1).build());
        assertEquals("0 1 1\n", Fs.readText(new File(game, "port/save/touch.cfg")));
    }

    @Test
    public void switchesFollowTheOptions() throws Exception {
        File game = tmp.newFolder("game");
        File save = new File(game, "port/save");
        writeText(new File(save, "nativeui.txt"), "");

        write(game, new Choices().teofis(true).asyncRendering(true).build());

        assertTrue(new File(save, "teofis.txt").isFile());
        assertFalse("async is on, so noasync.txt must not exist",
                new File(save, "noasync.txt").exists());
        assertFalse("nativeui is no longer used", new File(save, "nativeui.txt").exists());

        write(game, new Choices().asyncRendering(false).build());

        assertFalse(new File(save, "teofis.txt").exists());
        assertTrue(new File(save, "noasync.txt").isFile());
    }

    @Test
    public void dxvkConfRewritesManagedKeysAndKeepsTheRest() throws Exception {
        File game = tmp.newFolder("game");
        File conf = new File(game, "port/dxvk.conf");
        writeText(conf, "custom = 1\nd3d9.maxFrameRate = 30\n");

        write(game, new Choices().frameCap(60).vsync(true).asyncRendering(false).build());

        String text = Fs.readText(conf);
        assertTrue(text, text.contains("custom = 1"));
        assertTrue(text, text.contains("d3d9.maxFrameRate = 60"));
        assertTrue(text, text.contains("d3d9.presentInterval = 1"));
        assertTrue(text, text.contains("d3d9.floatEmulation = True"));
        assertTrue(text, text.contains("dxvk.enableAsync = False"));
        assertTrue(text, text.contains("dxvk.enableStateCache = False"));
        assertEquals(1, count(text, "d3d9.maxFrameRate"));
    }

    @Test
    public void frameCapZeroDisablesTheLimit() throws Exception {
        File game = tmp.newFolder("game");

        write(game, new Choices().frameCap(0).asyncRendering(true).build());

        String text = Fs.readText(new File(game, "port/dxvk.conf"));
        assertTrue(text, text.contains("d3d9.maxFrameRate = 0"));
        assertTrue(text, text.contains("dxvk.enableAsync = True"));
    }

    @Test
    public void writesLanguageAndGrassToSkyrimIni() throws Exception {
        File game = tmp.newFolder("game");
        write(game, new Choices().language(2).build());

        File ini = skyrimIni(game);
        assertEquals("GERMAN", iniValue(ini, "General", "sLanguage"));
        assertEquals("1", iniValue(ini, "Grass", "bAllowCreateGrass"));
        assertEquals("1.5000", iniValue(ini, "Trees", "fUpdateBudget"));
    }

    @Test
    public void skyrimDefaultIniSeedsTheLanguageFile() throws Exception {
        File game = tmp.newFolder("game");
        writeText(new File(game, "Skyrim_default.ini"),
                "[General]\r\nsLanguage=ENGLISH\r\nbOther=keep\r\n");

        write(game, new Choices().language(1).build());

        File ini = skyrimIni(game);
        assertEquals("FRENCH", iniValue(ini, "General", "sLanguage"));
        assertEquals("keep", iniValue(ini, "General", "bOther"));
    }

    @Test
    public void existingSkyrimIniKeysAreKept() throws Exception {
        File game = tmp.newFolder("game");
        File ini = skyrimIni(game);
        writeText(ini, "[General]\r\nCustom=x\r\nsLanguage=ENGLISH\r\n");

        write(game, new Choices().language(7).build());

        assertEquals("x", iniValue(ini, "General", "Custom"));
        assertEquals("JAPANESE", iniValue(ini, "General", "sLanguage"));
    }

    @Test
    public void writesThePluginFiles() throws Exception {
        File game = tmp.newFolder("game");
        List<Plugin> plugins = new ArrayList<>(Arrays.asList(
                new Plugin("Skyrim.esm", true), new Plugin("Mod.esp", true)));

        new GameConfigWriter(new DisplayModes(2400, 1080))
                .write(new LaunchPlan(game.getPath(), new Choices().build(), plugins, null));

        File dir = PluginFiles.gameDir(game.getPath());
        assertEquals("Skyrim.esm\nMod.esp\n", Fs.readText(new File(dir, "Plugins.txt")));
    }

    /* ------------------------------------------------------------------ */

    private void write(File game, LauncherChoices choices) throws Exception {
        new GameConfigWriter(new DisplayModes(2400, 1080))
                .write(new LaunchPlan(game.getPath(), choices, new ArrayList<Plugin>(), null));
    }

    private static File prefs(File game) {
        return new File(game, "port/save/Documents/My Games/Skyrim/SkyrimPrefs.ini");
    }

    private static File skyrimIni(File game) {
        return new File(game, "port/save/Documents/My Games/Skyrim/Skyrim.ini");
    }

    private static void writeText(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) {
            w.write(text);
        }
    }

    /** Last value of key in section, or null. */
    private static String iniValue(File f, String section, String key) {
        String wanted = null;
        String current = "General";
        for (String line : Fs.readText(f).split("\n")) {
            line = line.trim();
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                current = end > 0 ? line.substring(1, end) : line.substring(1);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || !current.equalsIgnoreCase(section)) continue;
            if (line.substring(0, eq).trim().equalsIgnoreCase(key)) {
                wanted = line.substring(eq + 1).trim();
            }
        }
        return wanted;
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

    /** Defaults for one Ultra run; each test changes only what it asserts. */
    private static final class Choices {
        int preset = 4, aspect = 0, width = 1920, height = 1080;
        int antialiasing = 0, anisotropy = 0, textureQuality = 2, shadowDetail = 2;
        int decals = 2, blur = 0, fade = 1, frameCap = 0, language = 0;
        boolean fxaa = false, vsync = false, depthOfField = true, disableShadows = false;
        boolean teofis = false, disableTreeAnimations = false, asyncRendering = false;
        boolean sustainedPerformance = false, showFps = false;
        boolean touchControls = true, nintendoLayout = false;
        int cameraMode = 0;

        Choices preset(int v) { preset = v; return this; }
        Choices aspect(int v) { aspect = v; return this; }
        Choices width(int v) { width = v; return this; }
        Choices height(int v) { height = v; return this; }
        Choices antialiasing(int v) { antialiasing = v; return this; }
        Choices anisotropy(int v) { anisotropy = v; return this; }
        Choices textureQuality(int v) { textureQuality = v; return this; }
        Choices shadowDetail(int v) { shadowDetail = v; return this; }
        Choices decals(int v) { decals = v; return this; }
        Choices blur(int v) { blur = v; return this; }
        Choices fade(int v) { fade = v; return this; }
        Choices frameCap(int v) { frameCap = v; return this; }
        Choices language(int v) { language = v; return this; }
        Choices fxaa(boolean v) { fxaa = v; return this; }
        Choices vsync(boolean v) { vsync = v; return this; }
        Choices depthOfField(boolean v) { depthOfField = v; return this; }
        Choices disableShadows(boolean v) { disableShadows = v; return this; }
        Choices teofis(boolean v) { teofis = v; return this; }
        Choices disableTreeAnimations(boolean v) { disableTreeAnimations = v; return this; }
        Choices asyncRendering(boolean v) { asyncRendering = v; return this; }
        Choices sustainedPerformance(boolean v) { sustainedPerformance = v; return this; }
        Choices showFps(boolean v) { showFps = v; return this; }
        Choices touchControls(boolean v) { touchControls = v; return this; }
        Choices nintendoLayout(boolean v) { nintendoLayout = v; return this; }
        Choices cameraMode(int v) { cameraMode = v; return this; }

        LauncherChoices build() {
            return new LauncherChoices(
                    preset, aspect, width, height,
                    antialiasing, anisotropy, textureQuality, shadowDetail,
                    decals, blur, fade, frameCap, language,
                    fxaa, vsync, depthOfField, disableShadows,
                    teofis, disableTreeAnimations, asyncRendering,
                    sustainedPerformance, showFps, touchControls, nintendoLayout,
                    cameraMode);
        }
    }
}
