#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$ROOT/pom.xml" | sed -n '1p')"
COMMIT="uncommitted"

if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  COMMIT="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || printf 'no-commit')"
fi

printf 'project=smpso-tophat-image-fusion\n'
printf 'version=%s\n' "$VERSION"
printf 'commit=%s\n' "$COMMIT"
