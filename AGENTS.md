# Agent instructions

Read [RULES.md](RULES.md) before changing documentation or creating a
release. The rules are mandatory.

- Every release must have patch notes: a `CHANGELOG.md` section, a GitHub
  Release body generated from it, and the APK attached.
- Every README/CHANGELOG change bullet must use
  `[Technical change — Positive impact]` (`[Mudança técnica — Impacto
  positivo]`), written for players, not for developers.
- Do not use third-party or reference binaries as an input. Work from our
  source and our device measurements.
- The release process is `tools/release.sh X.Y.Z`; it enforces the changelog
  format and creates the GitHub Release with the APK.
