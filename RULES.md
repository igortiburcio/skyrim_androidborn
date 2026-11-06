# Project rules

These rules apply to every change in this repository. They exist so the
project is readable by players and by people who were not part of the
development conversation.

## 1. Every release has patch notes

A release is only complete when it has all three:

1. a `CHANGELOG.md` section for the version;
2. a GitHub Release whose body is that section;
3. the release APK attached to it.

A release with an empty body is not allowed. The notes must be generated from
the changelog, not written ad hoc in the GitHub web UI.

Use `tools/release.sh X.Y.Z`. It refuses to continue when the changelog
section is missing or when a bullet does not follow the format in rule 2.

## 2. README and CHANGELOG entries use `[Technical change — Positive impact]`

Every bullet that announces a change in `README.md` or `CHANGELOG.md` must
start with the technical change and its positive impact:

```
- [Technical change — Positive impact] Details in plain language.
```

Portuguese equivalent: `- [Mudança técnica — Impacto positivo] Detalhes em
linguagem simples.`

- **Technical change** says exactly what changed: a feature, an option, a
  fix, a performance pass.
- **Positive impact** says what the person playing gets: more fps, less heat,
  no crackle, no black bars, fewer clicks, a cooler device.
- Keep it short. One sentence is usually enough.
- Write for someone who has never read the source code.

### Required

```
- [Sampling profiler is now opt-in — lower CPU use and less heat] It only
  runs when you create save/prof.txt.
- [xWMA buffers are decoded before the mixer — music and voices without crackle]
  The mix path only copies finished frames.
- [Android 15 insets controller — no black bars and true fullscreen] The
  game now draws edge to edge.
```

### Not allowed

```
- Fixed the bug from our first attempt.
- Matches the reference build's internal implementation.
- The profiler used to run by default and cost CPU on the game threads.
```

Those lines contain implementation detail, failed-attempt history, or context
that only the developers have. They belong in commit messages or code
comments, not in the changelog.

## 3. Release checklist

1. Bump `versionName` and `versionCode` in `android/app/build.gradle`.
2. Add a `## X.Y.Z — YYYY-MM-DD` section to `CHANGELOG.md` with
   `[Technical change — Positive impact]` bullets.
3. Update the `Current version` line in `README.md`.
4. Run `tools/release.sh X.Y.Z` (builds the release APK, checks the notes and
   creates the GitHub Release with the APK attached).
5. Install the release APK over the previous build and check the launcher, a
   save load, 30 fps, audio and fullscreen.
6. Only then announce the release.

## 4. Our source is the source of truth

Development happens against this repository. Third-party or reference
binaries are not an input and are not part of the development loop. Features
are designed, measured and tested on our own code and on the device.
