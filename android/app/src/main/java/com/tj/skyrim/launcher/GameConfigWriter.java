package com.tj.skyrim.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes everything the 0.30 runtime reads before the game starts:
 * <ul>
 *   <li>SkyrimPrefs.ini (graphics, from the chosen preset file plus the
 *       individual options),</li>
 *   <li>Skyrim.ini (the game language),</li>
 *   <li>Plugins.txt and loadorder.txt (the Data Files),</li>
 *   <li>display.cfg and touch.cfg,</li>
 *   <li>the nativeui switch,</li>
 *   <li>port/dxvk.conf (the frame rate cap).</li>
 * </ul>
 */
public final class GameConfigWriter {

    /** The INI each preset starts from (index = preset). */
    private static final String[] PRESET_FILES =
            {"low.ini", "low.ini", "medium.ini", "high.ini", "VeryHigh.ini"};
    private static final int[] AA_VALUES = {1, 2, 4, 8};
    private static final int[] ANISO_VALUES = {1, 2, 4, 8, 16};
    private static final int[] TEXQ_SKIP = {2, 1, 0};                 // iTexMipMapSkip
    private static final int[] SHADOW_RESOLUTION = {512, 1024, 2048, 4096};
    private static final int[] DECAL_COUNT = {0, 100, 250, 1000};
    private static final String[] FADE_MULT = {"2.5", "4.5", "6.5", "9.0"};
    private static final String[] LANG_CODES =
            {"ENGLISH", "FRENCH", "GERMAN", "ITALIAN", "SPANISH", "POLISH", "RUSSIAN", "JAPANESE"};

    private final DisplayModes modes;

    public GameConfigWriter(DisplayModes modes) {
        this.modes = modes;
    }

    /** Writes the plan's files; throws on the first failure. */
    public void write(LaunchPlan plan) throws Exception {
        LauncherChoices c = plan.choices;
        File game = new File(plan.folder);
        File save = new File(game, "port/save");
        File iniDir = new File(save, "Documents/My Games/Skyrim");
        iniDir.mkdirs();

        writePrefs(game, new File(iniDir, "SkyrimPrefs.ini"), c);
        writeLanguageIni(new File(iniDir, "Skyrim.ini"), game, c);
        writePlugins(iniDir, plan.plugins);

        int[] aspect = modes.rawAspect(c.aspect);
        writeLine(new File(save, "display.cfg"), aspect[0] + " " + aspect[1]);
        writeLine(new File(save, "touch.cfg"),
                (c.touchControls ? 1 : 0) + " " + (c.nintendoLayout ? 1 : 0) + " " + c.cameraMode);

        writeSwitch(save, "nativeui.txt", c.nativeUi);
        writeSwitch(save, "teofis.txt", c.teofis);
        writeSwitch(save, "noasync.txt", !c.asyncRendering);
        writeDxvkConf(new File(game, "port/dxvk.conf"), c);
    }

    /* ------------------------------------------------------------------ */

    /** Keeps what is in the file, starts from the preset's INI, applies the options. */
    private void writePrefs(File game, File out, LauncherChoices c) throws Exception {
        IniFile ini = IniFile.load(out);
        File preset = Fs.findCi(game, PRESET_FILES[c.preset]);
        if (preset != null) ini.merge(preset);

        ini.put("Display", "iSize W", Integer.toString(c.width));
        ini.put("Display", "iSize H", Integer.toString(c.height));
        ini.put("Display", "bFull Screen", "1");
        ini.put("Display", "iMultiSample", Integer.toString(AA_VALUES[c.antialiasing]));
        ini.put("Display", "iMaxAnisotropy", Integer.toString(ANISO_VALUES[c.anisotropy]));
        ini.put("Display", "iTexMipMapSkip", Integer.toString(TEXQ_SKIP[c.textureQuality]));

        int shadows = SHADOW_RESOLUTION[c.shadowDetail];
        boolean extraLow = c.preset == 0;
        boolean shadowsOff = c.disableShadows || extraLow;
        ini.put("Display", "iShadowMapResolution", Integer.toString(shadows));
        ini.put("Display", "iShadowMapResolutionPrimary", Integer.toString(shadows));
        ini.put("Display", "iShadowMapResolutionSecondary", Integer.toString(Math.max(512, shadows / 2)));
        /* Disabling shadows must turn the passes off too: only shrinking the
         * map leaves the deferred shadow pass running every frame, which made
         * the option slower than shadows on. Keep the preset's valid map
         * size/distances and disable the passes explicitly. */
        ini.put("Display", "bDrawShadows", shadowsOff ? "0" : "1");
        ini.put("Display", "bDeferredShadows", shadowsOff ? "0" : "1");
        ini.put("Display", "bDrawLandShadows", shadowsOff ? "0" : "1");
        ini.put("Display", "bShadowsOnGrass", shadowsOff ? "0" : "1");
        ini.put("Display", "bShadowMaskZPrepass", shadowsOff ? "0" : "1");

        int decals = DECAL_COUNT[c.decals];
        ini.put("Decals", "bDecals", decals > 0 ? "1" : "0");
        ini.put("Decals", "bSkinnedDecals", decals > 0 ? "1" : "0");
        ini.put("Decals", "uMaxDecals", Integer.toString(decals));
        ini.put("Decals", "uMaxSkinDecals", Integer.toString(decals / 10));
        ini.put("Display", "iMaxDecalsPerFrame", Integer.toString(decals / 10));
        ini.put("Display", "iMaxSkinDecalsPerFrame", Integer.toString(decals / 40));

        ini.put("Imagespace", "iRadialBlurLevel", Integer.toString(c.blur));
        ini.put("Imagespace", "bDoDepthOfField", c.depthOfField ? "1" : "0");

        String fade = FADE_MULT[c.fade];
        ini.put("LOD", "fLODFadeOutMultObjects", fade);
        ini.put("LOD", "fLODFadeOutMultItems", fade);
        ini.put("LOD", "fLODFadeOutMultActors", fade);

        ini.put("Display", "bFXAAEnabled", c.fxaa ? "1" : "0");
        ini.put("Display", "iPresentInterval", c.vsync ? "1" : "0");
        ini.put("MAIN", "bGamepadEnable", "1");
        ini.put("Controls", "bGamePadRumble", "1");

        if (extraLow) {
            /* The official "Extra low" preset's non-row values: grass off,
             * short LODs, 100 particles, cheap water, no skinned trees. The
             * rows above already carry the preset's textures/AA/decals/DOF. */
            ini.put("LOD", "fLODFadeOutMultObjects", "2.0");
            ini.put("LOD", "fLODFadeOutMultItems", "1.0");
            ini.put("LOD", "fLODFadeOutMultActors", "2.0");
            ini.put("Display", "fTreesMidLODSwitchDist", "1800.0000");
            ini.put("Display", "fMeshLODLevel2FadeTreeDistance", "1024.0000");
            ini.put("Display", "fMeshLODLevel1FadeTreeDistance", "1422.0000");
            ini.put("Display", "fMeshLODLevel2FadeDist", "1536.0000");
            ini.put("Display", "fMeshLODLevel1FadeDist", "2048.0000");
            ini.put("Display", "fLightLODStartFade", "500.0000");
            ini.put("Display", "fSpecularLODStartFade", "250.0000");
            ini.put("Display", "fDecalLOD1", "500.0000");
            ini.put("Display", "fDecalLOD2", "750.0000");
            ini.put("Display", "fShadowLODStartFade", "100.0000");
            ini.put("TerrainManager", "fTreeLoadDistance", "12000.0000");
            ini.put("TerrainManager", "fBlockMaximumDistance", "50000.0000");
            ini.put("TerrainManager", "fBlockLevel1Distance", "16384.0000");
            ini.put("TerrainManager", "fBlockLevel0Distance", "10240.0000");
            ini.put("TerrainManager", "fSplitDistanceMult", "0.4000");
            ini.put("Particles", "iMaxParticles", "100");
            ini.put("Water", "bUseWaterDisplacements", "0");
            ini.put("Water", "bUseWaterRefractions", "0");
            ini.put("Water", "bUseWaterReflections", "0");
            ini.put("Water", "bUseWaterDepth", "0");
            ini.put("Water", "iWaterReflectHeight", "64");
            ini.put("Water", "iWaterReflectWidth", "64");
        }
        if (c.disableTreeAnimations || extraLow) {
            ini.put("Trees", "bRenderSkinnedTrees", "0");
            ini.put("Trees", "uiMaxSkinnedTreesToRender", extraLow ? "20" : "40");
        } else {
            ini.put("Trees", "bRenderSkinnedTrees", "1");
            ini.put("Trees", "uiMaxSkinnedTreesToRender", "40");
        }

        ini.save(out);
    }

    /** sLanguage lives in Skyrim.ini; the folder's Skyrim_default.ini is the base
     *  when it is there (a game folder copied without it still gets the language). */
    private void writeLanguageIni(File out, File game, LauncherChoices c) throws Exception {
        IniFile ini = IniFile.load(out);
        if (!out.isFile()) ini.merge(Fs.findCi(game, "Skyrim_default.ini"));
        ini.put("General", "sLanguage", LANG_CODES[c.language]);
        boolean extraLow = c.preset == 0;
        /* Grass lives in Skyrim.ini, not SkyrimPrefs.ini. Extra low turns it
         * off; any other preset turns it back on. */
        ini.put("Grass", "bAllowCreateGrass", extraLow ? "0" : "1");
        ini.put("Grass", "bAllowLoadGrass", extraLow ? "0" : "1");
        ini.put("Grass", "bDrawShaderGrass", extraLow ? "0" : "1");
        /* fUpdateBudget lives in Skyrim.ini [Trees]; 1.5 is the game's default. */
        ini.put("Trees", "fUpdateBudget",
                (extraLow || c.disableTreeAnimations) ? "0.0000" : "1.5000");
        ini.save(out);
    }

    /** Plugins.txt = active plugins, loadorder.txt = every plugin in order. */
    private void writePlugins(File iniDir, List<Plugin> plugins) throws Exception {
        StringBuilder active = new StringBuilder(), order = new StringBuilder();
        for (Plugin p : plugins) {
            order.append(p.name).append("\r\n");
            if (p.on || p.alwaysOn()) active.append(p.name).append("\r\n");
        }
        writeRaw(new File(iniDir, "loadorder.txt"), order.toString());
        writeRaw(new File(iniDir, "Plugins.txt"), active.toString());
    }

    /** The runtime reads a few empty "switch" files next to the saves. */
    private void writeSwitch(File save, String name, boolean on) throws IOException {
        File f = new File(save, name);
        if (on && !f.isFile()) new FileOutputStream(f).close();
        if (!on && f.isFile()) f.delete();
    }

    /** Only the keys the launcher manages are rewritten; everything the user
     * put in dxvk.conf is left alone. Float emulation is on (prevents NaNs in
     * the HDR/blur passes), the DXVK state cache stays off (rebuilding it at
     * startup costs CPU), the present interval follows vertical sync, async
     * shader compilation follows the launcher option, and the frame cap is
     * always written. */
    private void writeDxvkConf(File out, LauncherChoices c) throws IOException {
        LinkedHashMap<String, String> managed = new LinkedHashMap<>();
        /* Always managed: a stale "maxFrameRate = 30" left in the file by an
         * older launcher combined with vsync made the frame limiter wait on
         * top of vblank (12 fps instead of 60). Writing 0 disables the cap. */
        managed.put("d3d9.maxFrameRate", c.frameCap > 0 ? Integer.toString(c.frameCap) : "0");
        managed.put("d3d9.presentInterval", c.vsync ? "1" : "0");
        managed.put("d3d9.floatEmulation", "True");
        managed.put("dxvk.enableAsync", c.asyncRendering ? "True" : "False");
        managed.put("dxvk.enableStateCache", "False");

        List<String> lines = new ArrayList<>();
        if (out.isFile()) {
            for (String s : Fs.readText(out).split("\n", -1)) {
                String t = s.trim();
                int eq = t.indexOf('=');
                String key = eq > 0 ? t.substring(0, eq).trim() : "";
                if (managed.containsKey(key)) continue;
                lines.add(s);
            }
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().isEmpty())
            lines.remove(lines.size() - 1);
        for (Map.Entry<String, String> e : managed.entrySet())
            lines.add(e.getKey() + " = " + e.getValue());
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        writeRaw(out, sb.toString());
    }

    private static void writeLine(File f, String line) throws IOException {
        writeRaw(f, line + "\n");
    }

    private static void writeRaw(File f, String content) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) {
            w.write(content);
        }
    }
}
