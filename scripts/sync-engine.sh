#!/usr/bin/env bash
# Sync the Qt-free engine packages from the desktop project into the Android app.
# Source of truth: ~/Projects/albion-crafter. Excluded: ui/, main.py (PySide6).
set -euo pipefail

SRC="${ALBION_CRAFTER_SRC:-/home/dok/Projects/albion-crafter/src/albion_crafter}"
DST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/python/albion_crafter"

if [ ! -d "$SRC" ]; then
  echo "Source not found: $SRC" >&2
  exit 1
fi

mkdir -p "$DST"
rsync -a --delete --delete-excluded \
  --exclude 'ui/' \
  --exclude 'main.py' \
  --exclude '__pycache__/' \
  --exclude '*.pyc' \
  "$SRC/" "$DST/"

echo "Engine synced: $DST ($(find "$DST" -name '*.py' | wc -l) files)"
