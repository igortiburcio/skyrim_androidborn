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

## 0.2.3 — 2026-11-08

Performance pass for heavy world scenes.

### Performance
- [x87 top of stack kept in a local register — about 25% more FPS in the heaviest Whiterun scenes]
  The common fld/fstp/fmul/fadd path no longer reloads the FPU stack pointer for every instruction.
- [Internal symbols hidden and PLT calls removed — less overhead on every recompiled call and a faster start]
  Generated functions no longer go through the dynamic linker.
- [Interlocked operations without redundant fences — fewer stalls in busy scenes]
  The atomic read-modify-write already provides the ordering x86 needs.
- [Watchdog diagnostics kept out of the hot path — lower CPU use while playing]
  The per-import record only keeps the cheap last-call fields during normal play.
- [Guest memory asks for huge pages — fewer TLB misses in the open world]
  The 4 GiB guest window uses 2 MiB pages when the kernel allows them.
- [ARMv8.2/LSE build — faster locks and reference counts on modern phones]
  Interlocked operations compile to single instructions instead of outline helper calls.

### Notes
This build targets ARMv8.2/LSE (Cortex-A75/A55 and newer). The Retroid Pocket
G2 and Xiaomi 15T are supported; older ARMv8.0 devices should stay on 0.2.2.

Tested on a Retroid Pocket G2 (Adreno A22, Android 15) at Whiterun, 1280x720
low, frame cap off: 39.6 fps on 0.2.2 and 49.6 fps on this build, with CPU per
frame about 20% lower. CPU and GPU temperatures were similar in both runs
(about 101 °C and 75 °C).

## 0.2.2 — 2026-11-08

Test suites and the release gate.

### Tests
- [Automated tests for launcher settings and Steam downloads — broken settings and corrupted downloads are caught before a release]
  The launcher and Steam client suites run on the JVM, and the release script
  refuses to publish when one of them fails.
- [Native tests for touch controls, textures and INI settings — control and graphics regressions are caught before a release]
  Stick clamping, DXT decoding, the second-screen layout and the x87 helpers
  are checked on the host, without a phone or the game files.

### Notes
Tested on a Retroid Pocket G2 (Adreno A22, Android 15): launcher, touch
overlay, A button, drag camera, save load, 30 fps and audio.

## 0.2.1 — 2026-11-07

Blue weapon glitch and new-object flicker.

### Fixes
- [Pipeline cache save is now synchronized with pipeline creation — no more flat blue weapon or wrong draws]
  The save thread takes an exclusive lock while pipeline creation holds a
  shared lock, so the driver never reads the cache in the middle of a write.
- [Old pipeline cache is discarded and new saves are checksummed — a corrupted cache can no longer come back]
  The cache moved to a versioned file that is validated on load; the pre-fix
  file is deleted on first launch and rebuilt cleanly.
- [Turning off "Asynchronous rendering" stops new-object flicker — play without flashing at the cost of a hitch on first sight]
  With async on, first-time objects can skip a frame while they compile; with
  it off, loading a new effect can pause the frame instead.

### Notes
Tested on a Retroid Pocket G2 (Adreno A22, Android 15): launcher, save load
and 30 fps with the fix installed; flicker gone with async off. The flat blue
weapon was reproduced on both Adreno and Mali-G720 (Xiaomi 15T, PanVK).

## 0.2.0 — 2026-11-07

Steam install, a simpler launcher and smaller game folders.

### Steam
- [Steam sign-in with a QR code — download the game data without a PC]
  Scan the code with the Steam app; the session stays encrypted on the device
  so the next launch only asks for Continue.
- [Steam download of the 2011 game and DLCs — the downloaded folder is selected automatically]
  The optional 4.7 GB high-resolution pack and the PC executable/DLLs are
  skipped.
- [Foreground download service — the transfer survives the screen turning off]
  A notification shows the progress and has a Cancel button.
- [Remove Steam download or Remove this game folder — reclaim storage without losing saves]
  Both ask for a second tap; only Data and appinfo.bin are deleted, and
  port/save stays.

### Launcher
- [Game folder only needs Data — no PC executable or DLLs to copy]
  The executable images are built into the app and mapped at launch.
- [Game Files is one screen; Settings holds Graphics, GPU Driver and Plugins — fewer clicks]
  The menu is Play, Game Files and Settings, and the load order moved into
  Settings.
- [Built-in folder browser with Internal storage and SD card shortcuts — pick folders the system picker refuses]
  Useful for SD-card roots and Download.
- [Launcher checks GitHub for updates and installs them — no sideloading for each new version]
  A New update board shows the patch notes and the download/install buttons.

### Fixes
- [Load order follows Plugins.txt and loadorder.txt — DLCs and mods load in the intended order]
  The five official masters stay at the top in their required order, and the
  launcher imports the files the game already uses.
- [Enabled plugins now reach the game — changes in the launcher actually load in Skyrim]
  Plugins.txt and loadorder.txt are written to port/save/Documents/Skyrim,
  where the game reads them, and mirrored to Documents/My Games/Skyrim.
- [The non-working "Draw UI at native resolution" option is gone — no misleading setting]
  Choose the screen's own resolution when you want the sharpest UI.

### Notes
Tested on a Retroid Pocket G2 (Adreno A22, Android 15): QR and saved-session
login, 8.4 GB Steam verification, menu, save list and 30 fps. The Retroid's
background cleaner (`com.rp.settings`) force-stops apps when the screen turns
off; exempt Skyrim Androidborn if a download stops.

## 0.1.6 — 2026-11-06

### Launcher
- [Launcher checks GitHub for new releases — you are told when an update is available and can install it from the launcher]
  A New update board appears on the right with the patch notes and the
  download/install buttons. The APK downloads in the background and Android's
  installer opens.

## 0.1.5 — 2026-11-06

Touch controls.

### Touch
- [Buttons are labelled on screen — you always know which button you are pressing]
  A/B/X/Y, LB/RB/LT/RT and the Xbox View/Menu symbols are drawn inside each
  button, and the keyboard button shows a clear key layout.
- [Drag camera — look around by sliding your finger anywhere on the screen]
  The camera follows the swipe and stops shortly after you lift the finger.
- [Camera control can be changed in the launcher — the old fixed joystick is still available]
  A "Camera control" row chooses between Joystick and Touch drag.

### Notes
Tested on a Retroid Pocket G2 (Adreno A22, Android 15): launcher, touch
labels, drag camera, joystick camera, save load and 30 fps.

## 0.1.4 — 2026-11-06

Audio fix.

### Audio
- [Slow audio start no longer mutes the session — music and effects work on Mali with PanVK]
  The mixer waits for the audio device and recovers instead of staying silent
  until the next launch.

### Notes
Tested on a Retroid Pocket G2 (Adreno A22, Android 15). A Mali-G720 (Xiaomi 15T,
Android 15) needs the PanVK driver; see the README.

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
