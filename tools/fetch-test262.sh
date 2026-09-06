#!/usr/bin/env bash
# Fetches the test262 suite at the commit upstream Rhino pins, into reference/test262.
#
# reference/ is gitignored: the suite is 50k files and belongs to tc39, not here. The pin is what
# makes the parity run meaningful, so it is checked rather than assumed.
set -euo pipefail

PIN="3fd4ec27f1798ebecafc73b354a45dcdda9bde29"
REPO="https://github.com/tc39/test262.git"
DEST="$(cd "$(dirname "$0")/.." && pwd)/reference/test262"

if [ -d "$DEST/.git" ]; then
  have="$(git -C "$DEST" rev-parse HEAD 2>/dev/null || echo none)"
  if [ "$have" = "$PIN" ]; then
    echo "test262 already at $PIN"
    exit 0
  fi
  echo "test262 is at $have, wanted $PIN; refetching"
  rm -rf "$DEST"
fi

mkdir -p "$DEST"
git -C "$DEST" init -q
git -C "$DEST" remote add origin "$REPO"
# One commit, no history: the suite is large and nothing here reads its log.
git -C "$DEST" fetch -q --depth 1 origin "$PIN"
git -C "$DEST" checkout -q FETCH_HEAD

got="$(git -C "$DEST" rev-parse HEAD)"
if [ "$got" != "$PIN" ]; then
  echo "checked out $got, expected $PIN" >&2
  exit 1
fi
echo "test262 at $PIN in $DEST"
