#!/bin/sh
# Runs the Python unit tests. Uses $PYTHON, then a local test venv, then the
# system python3. Skips (with a message) when capstone/pefile are missing.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ -n "${PYTHON:-}" ]; then
    PY=$PYTHON
elif [ -x "$HOME/.local/share/skyrim-androidborn/testenv/bin/python3" ]; then
    PY=$HOME/.local/share/skyrim-androidborn/testenv/bin/python3
else
    PY=python3
fi

if ! "$PY" -c 'import capstone, pefile' >/dev/null 2>&1; then
    echo "python tests: skipped (install capstone and pefile:"
    echo "  python3 -m pip install -r tests/python/requirements.txt)"
    exit 0
fi

cd "$ROOT"
exec "$PY" -m unittest discover -s tests/python -v
