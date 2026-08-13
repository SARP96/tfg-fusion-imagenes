#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${FIJI_DIR:?Defina FIJI_DIR con la ruta de Fiji.app}"

JAR_FILE="$ROOT/target/SMPSO_TopHat_Fusion.jar"
if [[ ! -f "$JAR_FILE" ]]; then
  "$ROOT/build.sh"
fi

mkdir -p "$FIJI_DIR/plugins/SMPSO_TopHat_Fusion"
cp "$JAR_FILE" "$FIJI_DIR/plugins/SMPSO_TopHat_Fusion/"
echo "Instalado en $FIJI_DIR/plugins/SMPSO_TopHat_Fusion/"
