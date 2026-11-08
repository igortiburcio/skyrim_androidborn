#!/bin/sh
# Builds and publishes a release. It refuses to run when the changelog section
# is missing or when a bullet does not follow the project format:
#
#   - [Technical change — Positive impact] Details.
#
# Usage: tools/release.sh X.Y.Z
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
V=${1:-}
if [ -z "$V" ]; then
    echo "usage: tools/release.sh X.Y.Z" >&2
    exit 2
fi
case "$V" in
    *[!0-9.]*) echo "invalid version: $V" >&2; exit 2 ;;
esac

CHANGELOG="$ROOT/CHANGELOG.md"
[ -f "$CHANGELOG" ] || { echo "missing $CHANGELOG" >&2; exit 1; }

NOTES=$(mktemp)
trap 'rm -f "$NOTES"' EXIT INT TERM
awk -v v="$V" '
    $0 ~ ("^## " v "([ —-]|$)") { on = 1; print; next }
    on && /^## / { exit }
    on { print }
' "$CHANGELOG" > "$NOTES"

if [ ! -s "$NOTES" ]; then
    echo "CHANGELOG.md has no '## $V' section; add patch notes first." >&2
    exit 1
fi

bullets=$(grep -c '^- ' "$NOTES" || true)
good=$(grep -cE '^- \[[^]]+ (—|-) [^]]+\]' "$NOTES" || true)
if [ "$bullets" != "$good" ]; then
    echo "every changelog bullet must use '- [Technical change — Positive impact] ...'." >&2
    echo "offending lines:" >&2
    grep -E '^- ' "$NOTES" | grep -vE '^- \[[^]]+ (—|-) [^]]+\]' >&2 || true
    exit 1
fi

if ! grep -q "\"$V\"" "$ROOT/android/app/build.gradle"; then
    echo "warning: versionName in android/app/build.gradle does not look like $V" >&2
fi

echo "running tests"
"$ROOT/tools/test.sh"

APK="$ROOT/Skyrim-Androidborn-$V.apk"
"$ROOT/tools/build_release.sh" "$APK"

if ! command -v gh >/dev/null 2>&1; then
    echo "GitHub CLI (gh) not found; release APK is at $APK" >&2
    echo "Install gh (https://cli.github.com), authenticate, and rerun this script." >&2
    echo "The patch notes it would publish:" >&2
    cat "$NOTES" >&2
    exit 1
fi

if gh release view "v$V" >/dev/null 2>&1; then
    gh release edit "v$V" --title "Skyrim Androidborn $V" --notes-file "$NOTES"
    gh release upload "v$V" "$APK" --clobber
else
    gh release create "v$V" "$APK" --title "Skyrim Androidborn $V" --notes-file "$NOTES"
fi

echo "release: $(gh release view "v$V" --json url -q .url)"
