# Skyrim Androidborn

An unofficial native Android port of the 2011 PC version of *The Elder Scrolls
V: Skyrim* (not Special Edition). It runs on arm64 Android devices with
Vulkan 1.1.

Tested on a **Retroid Pocket G2** (Adreno A22, Android 15). Also tested on a
**Xiaomi 15T** (Mali-G720, Android 15) with the PanVK driver.

Current version: **0.2.0** — see [CHANGELOG.md](CHANGELOG.md).

## Screenshots

![Skyrim Androidborn launcher](images/androidborn-launcher.jpg)

![Graphics settings](images/androidborn-settings.jpg)

![Gameplay](images/androidborn-gameplay.jpg)

## What you need

- arm64 Android 9 or newer with Vulkan 1.1.
- Around 3 GB of RAM and 10 GB of free storage.
- A Steam account that owns the 2011 game, or a `Data` folder from your own
  PC copy. The 2011 original only; Special Edition does not work.
- A controller is recommended; the touch overlay works without one.

## Install

1. **Install the APK.** Open it on the device (allow "Install unknown apps"
   for your file manager or browser) or run `adb install -r
   Skyrim-Androidborn-<version>.apk`. Newer versions install over older ones.
2. **Open the app**, choose **Game Files** and scroll down to **Steam**.
3. **Sign in with Steam.** Scan the QR code with the Steam app on your phone
   and confirm.
4. **Download the game data.** Pick a folder; the launcher downloads the 2011
   game data and its DLCs and selects that folder automatically.
5. **Tap Play.**

No PC or manual extraction is needed: the launcher gets the game data
directly from Steam (about 8.4 GB). The optional 4.7 GB high-resolution pack
and the PC executable/DLLs are skipped.

### If you already have a Data folder

1. Copy the `Data` folder from your PC install into a folder outside
   `Android/data`, for example `Internal storage/Skyrim/Data`.
2. In **Game Files**, choose **Choose Skyrim folder** and pick
   `Internal storage/Skyrim`.
3. Tap Play.

## Your saves and files

Everything the port writes goes into a `port` folder inside the game folder.
Saves live under `port/save/Documents/My Games/Skyrim`; the log is
`port/log.txt`. Uninstalling the app does not delete them.

### Removing a copy

**Game Files** has **Remove Steam download** and **Remove this game folder**.
Both ask for a second tap, delete only `Data` and `appinfo.bin`, and keep
`port/save` so your saves stay. Removing the folder that is currently selected
also clears it, so choose another copy before pressing Play.

## Controls

- Bluetooth, USB and integrated controllers use the Xbox layout by default;
  enable the Nintendo layout to swap A/B and X/Y.
- The touch overlay has a left stick, A/B/X/Y, LT/RT, shoulders, Start and
  Back, all labelled, plus a keyboard button and Xbox View/Menu symbols.
  LT/RT are the left/right hand attacks.
- **Camera control** in the launcher picks between **Touch drag** (default:
  slide a finger anywhere outside the buttons and the movement stick; the
  camera follows the swipe and stops when you lift) and **Joystick** (the
  fixed-base analog stick on the right half of the screen).

## Settings

**Game Files** holds the folder chooser, the Steam sign-in/download and the
removal buttons. **Settings** opens three tabs: **Graphics**, **GPU Driver**
and **Plugins**.

| Setting | What it does | Why it helps |
|---|---|---|
| **Detail** | Extra low, Low, Medium, High or Ultra. Low is the default. | One click for the right balance; Extra low is the maximum-savings preset. |
| **Resolution** | The screen's own resolution or a lower one, stretched to fill the screen. | Lower resolutions are the biggest GPU saving; the screen's own is the sharpest UI. |
| **Antialiasing / Anisotropic filtering** | MSAA and anisotropic filtering levels. | Lower values reduce GPU work. |
| **Texture quality / Shadow detail / Decals / Radial blur / Object fade** | The same options as the PC launcher. | Tune visuals against performance. |
| **Vertical sync** | Locks presentation to the display refresh. | Smooth motion when the frame rate is uncapped. |
| **Disable shadows** | Turns the shadow passes off. | Less GPU and CPU work, and it no longer runs slower than shadows on. |
| **Disable tree animations** | Stops per-frame tree animation. | Less CPU work in forests. |
| **Disable post-processing (TEOFIS)** | Skips tonemapping, bloom, colour grading and depth of field. | Large GPU saving, cooler device, and it fixes the black world map and the solid-blue underwater screen. |
| **Asynchronous rendering** | Compiles shaders on background threads (on by default). | Avoids freezes when new effects appear; turn it off if objects pop in or flash. |
| **Sustained performance mode** | Asks Android for a steady clock instead of short boosts. | Can keep the device cooler and the fan quieter. |
| **Touch controls / Nintendo layout** | Shows the touch overlay; swaps A/B and X/Y on controllers. | Play without a controller, or match Nintendo buttons. |
| **Camera control** | Picks the drag-anywhere camera or the fixed-base look stick. | Use the camera that feels right on your phone. |

**Plugins** (in Settings) sets the load order and which plugins are active.
The five official masters keep their required order at the top; only mods can
be moved.

## Updates

The launcher checks GitHub for a newer release when it opens. When one is
available, a **New update** board appears on the right side of the launcher
with the patch notes, the download and an **Install update** button. Android
asks you to allow installs from the app the first time; after that, the new
version installs over the old one and your saves stay where they are.

## Troubleshooting

If the game does not start, check that the `Data` folder (with
`Data/Skyrim.esm`) is in the folder you selected. `port/log.txt` is rewritten
on every launch; attach it when reporting a problem.

### Downloads stop when the screen turns off

Skyrim Androidborn downloads in a foreground service, so Android keeps the
transfer alive in the background. Some handhelds ship a background cleaner
that force-stops apps anyway; on the Retroid Pocket G2 it is
`com.rp.settings`. Exempt Skyrim Androidborn from that cleaner, or keep the
launcher open while the data downloads.

### Mali GPUs

The stock Mali Vulkan driver crashes while Skyrim compiles its shaders, so the
game closes shortly after Play. Install a PanVK driver zip in
**Settings > GPU Driver** and select it before playing. Tested with
`mali-panvk-27.0.0-devel-r10` (`libvulkan_panfrost.so`); newer PanVK builds
from the emulator community should also work. The launcher installs the same
driver zips the emulators use.

## Technical details

### Game files

The game folder only needs the data from the 2011 game:

| Item | Notes |
|---|---|
| `Data/` | The game data folder, with `Skyrim.esm` inside. |

The executable and the three DLLs are not read from the folder: the build
maps their data sections from `gen/image_*.c` and supplies the code from the
recompiled `gen/`. They are still needed when building the port from source.

Other releases (retail disc, older patches, Special Edition) are untested.
Special Edition is 64-bit DirectX 11 and does not work.

The plugin files are written to `port/save/Documents/Skyrim` (the game's
`%LOCALAPPDATA%\Skyrim`) and mirrored to `Documents/My Games/Skyrim`. The
launcher imports the existing order and active set on first run.

Optional switches: create an empty file with one of these names in
`port/save/`:

| File | Effect |
|---|---|
| `singlecore.txt` | Runs all game threads on one core (slower, but rules out threading problems). |
| `allcores.txt` | Lets every thread use every core. |
| `fastcores.txt` | Keeps all game threads on the fast cores. |
| `prof.txt` | Turns the sampling profiler on (off by default). |
| `teofis.txt` | Same as the "Disable post-processing" option. |
| `noasync.txt` | Same as turning off "Asynchronous rendering". |

### Building from source

The build has two stages:

1. Generate `gen/` from your game files. This is slow and only needs to be
   redone when the lifter changes. It also writes the PE images
   (`gen/image_*.c`) the runtime maps; both must come from the same files.
2. Build the APK with Gradle, the Android NDK and CMake.

Linux or WSL is the easiest host for stage 1. Stage 2 works on Windows, Linux
and macOS.

#### 1. Generate the recompiled code

Requirements: Python 3.10+ and:

```sh
pip install capstone==5.0.9 pefile==2024.8.26
```

`GAME` must contain `TESV_unpacked.exe` (the final 1.9.32.0.8 `TESV.exe`
after removing SteamStub with
[Steamless](https://github.com/atom0s/Steamless)), `d3dx9_42.dll`,
`binkw32.dll` and `X3DAudio1_7.dll`. Run from the repository root:

```sh
tools/regen_gen.sh GAME
```

Discovery takes several hours; lifting takes about 15 minutes. The generated
C is around 340 MB, plus about 95 MB of image source (about 18 MB in the
APK). `tools/regen_gen.sh` also validates that the images keep every table
the loader reads (a DLL whose import/export tables live in `.text` keeps it).

The image keeps the game's `.text`: MSVC leaves jump tables inside the code
section and the recompiled code reads them as data (the `memcpy` at
`0xF52E30` jumps through a table at `0xF52F38`). Dropping it makes the game
fault on that table, so `--text drop` is only for experiments, together with
`LB_IMAGE_GUARD=1` (which turns the omitted range into `PROT_NONE`).

If `gen/` already exists and the game files did not change, the four
`tools/embed_image.py` calls at the end of `tools/regen_gen.sh` are enough;
there is no need to rediscover and lift again.

#### 2. Build the APK

Requirements:

- JDK 17 or newer.
- Android SDK Platform 34, Build-Tools 34, CMake 3.22.1.
- NDK r27 or newer (built with 29.0.14206865).

```sh
sdkmanager "platforms;android-34" "build-tools;34.0.0" "cmake;3.22.1" "ndk;29.0.14206865"
```

Then, from the repository root:

```sh
tools/build_custom.sh Skyrim-Androidborn-debug.apk    # debug/test build
tools/build_release.sh Skyrim-Androidborn-release.apk # release build
```

The first build takes 15 to 60 minutes. Recompiled files use 1 to 2 GB of RAM
each, so parallel compiles are capped with `LB_GEN_JOBS` (default 3). Raise it
in `android/app/build.gradle` with `"-DLB_GEN_JOBS=6"` if you have the RAM.

For a desktop test build (Linux), install `libsdl2-dev libvulkan-dev
libavcodec-dev libavutil-dev`, run `desktop/build.sh` and then
`build/lb GAME_DIR SAVE_DIR`. The Makefile takes `FFMPEG` and `FFMPEG_LIB`
when the FFmpeg 6 prefix is not `/usr`.

#### 3. Release

```sh
tools/release.sh 0.2.0
```

The release script checks that `CHANGELOG.md` has a section for the version,
that every bullet follows the `[Technical change — Positive impact]` format,
builds the release APK and creates the GitHub Release with the APK attached.
See [RULES.md](RULES.md).

### Steam client and licenses

The `steam/` module is a Java port of SteamKit2 and keeps LGPL-2.1
(`steam/LICENSE-LGPL-2.1.txt`); its protobuf definitions are public domain
(`steam/LICENSE-PROTOBUFS`). Steam and the Steam service belong to Valve
Corporation; this project is not affiliated with or endorsed by Valve.

Skyrim Androidborn is a fork of
[ttom795/Skyrim_Android](https://github.com/ttom795/Skyrim_Android).
Third-party code keeps its own licenses: SDL2 (zlib) in
`android/app/jni/SDL2`, dxvk-native (zlib) in `android/app/jni/dxvk`. The
project's own additions are MIT-licensed; see [LICENSE](LICENSE).

### Credits and legal

The Elder Scrolls, Skyrim, Bethesda and all related names and assets belong to
their respective owners. This project is not affiliated with or endorsed by
them. Do not redistribute anything produced from the game or game files.

The released APK embeds the PE image generated from the maintainer's own copy
of the game, including the code section, whose jump tables the port reads as
data. It is meant to run a copy you own, not to replace one.

The original port and third-party code keep their own terms.
