#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

if command -v mvn >/dev/null 2>&1; then
  exec mvn -f "$ROOT/pom.xml" clean package
fi

: "${FIJI_DIR:?Maven no esta instalado. Defina FIJI_DIR con la ruta de Fiji.app}"

JAVA_HOME_FIJI="${JAVA_HOME_FIJI:-}"
if [[ -z "$JAVA_HOME_FIJI" ]]; then
  JAVA_HOME_FIJI="$(find "$FIJI_DIR/java" -type d -path '*/Contents/Home' -print -quit 2>/dev/null || true)"
fi

if [[ -z "$JAVA_HOME_FIJI" || ! -x "$JAVA_HOME_FIJI/bin/javac" ]]; then
  echo "No se encontro javac. Defina JAVA_HOME_FIJI con el JDK utilizado por Fiji." >&2
  exit 1
fi

IJ_JAR="${IJ_JAR:-$(find "$FIJI_DIR/jars" -maxdepth 1 -name 'ij-*.jar' -print -quit)}"
if [[ ! -f "$IJ_JAR" ]]; then
  echo "No se encontro ij-*.jar. Defina IJ_JAR con la ruta correcta." >&2
  exit 1
fi

CLASSES="$ROOT/target/classes"
JAR_FILE="$ROOT/target/SMPSO_TopHat_Fusion.jar"

rm -rf "$ROOT/target"
mkdir -p "$CLASSES"
"$JAVA_HOME_FIJI/bin/javac" -encoding UTF-8 -source 8 -target 8 \
  -cp "$IJ_JAR" -d "$CLASSES" \
  "$ROOT/src/main/java/py/fpuna/fusion/plugin/SMPSO_TopHat_Fusion.java"
cp "$ROOT/src/main/resources/plugins.config" "$CLASSES/plugins.config"
"$JAVA_HOME_FIJI/bin/jar" cf "$JAR_FILE" -C "$CLASSES" .

echo "Compilado: $JAR_FILE"
