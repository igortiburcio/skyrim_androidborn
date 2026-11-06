# Changelog

All notable changes to **Skyrim Androidborn** are recorded here. Each bullet
starts with the technical change and its positive impact in the form
`[Technical change — Positive impact]` (Portuguese:
`[Mudança técnica — Impacto positivo]`). The version shown by the app is the
`versionName`; the Android `versionCode` only grows so updates install over
older builds.

Skyrim Androidborn is a fork of
[ttom795/Skyrim_Android](https://github.com/ttom795/Skyrim_Android), developed
independently.

## 0.1.3 — 2026-11-06

Launcher icon.

### Launcher
- [Skyrim dragon icon — the app is easy to recognize on the home screen] The
  app now uses the Skyrim dragon emblem instead of the plain diamond icon.

### Notes
Tested on a Retroid Pocket G2 (Adreno A22, Android 15).

## 0.1.2 — 2026-11-06

Touch controls.

### Touch
- [Right-side look stick — camera control works like a controller analog
  stick] The right half of the screen has a fixed base with a knob that stays
  inside it; drag in any direction to look on touch-only devices.
- [LT/RT buttons — left and right hand attacks work on touch-only devices]
  The triggers are now passed to the game instead of staying at zero.
- [Overlay stays visible — the first touch acts immediately] The old 2-second
  auto-hide consumed the first touch and disabled the controls; it is gone.

### Notes
Tested on a Retroid Pocket G2 (Adreno A22, Android 15).

## 0.1.1 — 2026-11-06

Performance and heat pass.

### Performance
- [Sampling profiler is now opt-in — lower CPU use and less heat while playing]
  It only runs when you create `port/save/prof.txt`, instead of on every
  launch.
- [New "Disable post-processing (TEOFIS)" option — more GPU headroom, a cooler device and no solid-blue underwater view]
  It skips tonemapping, bloom, colour grading and depth of field, and also
  fixes the black world map and the solid-blue underwater screen.
- [New "Disable tree animations" option — less CPU work in forests] It stops
  the per-frame tree animation.
- ["Extra low" preset now applies the full low-end set — one-click maximum performance]
  Grass, shadows, decals, anti-aliasing, anisotropic filtering, depth of
  field and skinned trees are off; LOD and terrain distances are shorter,
  particles are capped at 100 and water is cheap.
- ["Disable shadows" now turns the shadow passes off — the option finally saves GPU and CPU work]
  It no longer only shrinks the shadow map, so it is not slower than shadows
  on.
- [Float emulation is enabled — fewer black dots and HDR/blur glitches]
  Invalid values no longer reach the HDR and blur passes.
- [New "Asynchronous rendering" and "Sustained performance mode" toggles — control smoothness and heat]
  Async is on by default; sustained mode asks Android for a steadier clock.
- [Package id unchanged — saves, settings and drivers survive the update]
  The release installs over 0.1.0.

### Notes
Tested on a Retroid Pocket G2 (Adreno A22, Android 15).

## 0.1.0 — 2026-11-06

First release under the Androidborn name.

### Launcher
- [Custom launcher — set up the game folder, graphics, GPU driver, plugins and controls without editing files]
  Everything is in one place before Play.
- [Custom Vulkan driver install and selection — use a Turnip/Mr. Purple driver per game]
  The selected driver is loaded only for the game.
- [Graphics presets and per-option controls — tune the game for your device]
  Presets from Extra low to Ultra, resolution, anti-aliasing, anisotropic
  filtering, textures, shadows, decals, blur, object fade and vertical sync.
- [Gamepad navigation and touch overlay — play with or without a controller]
  The touch overlay includes a Nintendo button layout option.

### Graphics
- [30 fps frame cap with a persistent pipeline cache, async shaders and a non-spinning limiter — stable frame rate and low CPU/heat]
  The cap uses MAILBOX presentation and does not busy-wait.
- [Persistent pipeline cache — no shader rebuilds every launch] Compiled
  pipelines are saved and reused.
- [Edge-to-edge immersive mode — full-screen on Android 15 with no black strips]
  The status and navigation bars no longer inset the game.

### Audio
- [xWMA decoding off the mixing thread and a deeper audio queue — music and voices without crackle]
  Buffers are fully decoded before the mixer sees them, and the queue stays
  above one device buffer.

### Update
- [Package id unchanged — saves kept] Saves live outside the app folder.
