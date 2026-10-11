#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Uso: ./scripts/run-formal.sh [--check]

Ejecuta el diseño factorial formal CT-MRI completo. --check valida el
repositorio, las entradas, las pruebas y sus hashes, sin iniciar el batch.

Variables opcionales del equipo:
  THREADS  Número de corridas paralelas (por defecto: 8)
  IJ_JAR   Ruta al JAR de ImageJ (por defecto: Maven local, versión 1.54p)
  OUT_DIR  Directorio padre de resultados (por defecto: results/runs)

La configuración full-factorial.json y el manifiesto CT-MRI están fijados.
No se aplican filtros de pares, límites de corridas ni semillas prefijadas.
EOF
}

mode="${1:-run}"
case "$mode" in
  run|--check) ;;
  --help|-h) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac
if (( $# > 1 )); then usage >&2; exit 2; fi

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

THREADS="${THREADS:-8}"
IJ_JAR="${IJ_JAR:-$HOME/.m2/repository/net/imagej/ij/1.54p/ij-1.54p.jar}"
OUT_DIR="${OUT_DIR:-results/runs}"
CONFIG="experiments/configs/full-factorial.json"
MANIFEST="experiments/manifests/ct-mri-184-pairs.csv"
JAR="target/SMPSO_TopHat_Fusion.jar"

[[ "$THREADS" =~ ^[1-9][0-9]*$ ]] || fail 'THREADS debe ser un entero positivo.'
for command_name in git mvn java shasum awk find xargs tee; do
  command -v "$command_name" >/dev/null 2>&1 || fail "Falta el comando $command_name."
done

[[ -z "$(git status --porcelain)" ]] || fail \
  'El repositorio tiene cambios sin confirmar. Haga commit antes de la corrida formal.'

./scripts/validate-manifest.sh "$MANIFEST"
pair_count="$(awk 'END {print NR-1}' "$MANIFEST")"
[[ "$pair_count" -eq 184 ]] || fail "Se esperaban 184 pares; hay $pair_count."
for modality in ct mri; do
  image_count="$(find "data/raw/$modality" -type f -name '*.png' | wc -l | tr -d ' ')"
  [[ "$image_count" -eq 184 ]] || fail \
    "Se esperaban 184 imágenes $modality; hay $image_count."
done

printf 'Compilando y ejecutando pruebas...\n'
mvn -q clean verify
[[ -f "$JAR" ]] || fail "No se generó $JAR."
[[ -f "$IJ_JAR" ]] || fail "No existe IJ_JAR: $IJ_JAR."
[[ -z "$(git status --porcelain)" ]] || fail \
  'La compilación modificó archivos versionados; revise el repositorio.'

commit="$(git rev-parse HEAD)"
printf 'Commit: %s\nConfig: %s\nManifiesto: %s\nHilos: %s\nSalida: %s\n' \
  "$commit" "$CONFIG" "$MANIFEST" "$THREADS" "$OUT_DIR"

mkdir -p results/tmp "$OUT_DIR"
prep="$(mktemp -d "results/tmp/formal-$(date +%Y%m%d-%H%M%S).XXXXXX")"
printf '%s\n' "$commit" > "$prep/commit.txt"
git status --porcelain > "$prep/git-status.txt"
{
  printf 'threads=%s\nout_dir=%s\nij_jar=%s\n' "$THREADS" "$OUT_DIR" "$IJ_JAR"
  date -u '+UTC: %Y-%m-%dT%H:%M:%SZ'
  uname -a
  java -version 2>&1
  mvn -version
  sysctl -n machdep.cpu.brand_string 2>/dev/null || sysctl -n hw.model 2>/dev/null || uname -m
  sysctl -n hw.memsize 2>/dev/null || true
} > "$prep/environment.txt"
shasum -a 256 "$JAR" "$IJ_JAR" "$CONFIG" "$MANIFEST" > "$prep/input-sha256.txt"
find data/raw/ct data/raw/mri -type f -name '*.png' -print0 \
  | xargs -0 shasum -a 256 \
  | LC_ALL=C sort -k2 > "$prep/images-sha256.txt"
[[ "$(wc -l < "$prep/images-sha256.txt" | tr -d ' ')" -eq 368 ]] || fail \
  "La lista de hashes de imágenes no tiene 368 entradas; revise $prep."
if [[ "$mode" == --check ]]; then
  printf 'Comprobación terminada. Huellas en %s. No se inició el batch.\n' "$prep"
  exit 0
fi

printf 'Iniciando corrida formal. Registro previo: %s\n' "$prep"
if java -cp "$JAR:$IJ_JAR" py.fpuna.fusion.plugin.BatchRunner \
    --config "$CONFIG" --manifest "$MANIFEST" \
    --out "$OUT_DIR" --threads "$THREADS" 2>&1 | tee "$prep/console.log"; then
  run_exit=0
else
  run_exit=$?
fi
printf '%s\n' "$run_exit" > "$prep/exit-code.txt"
[[ "$run_exit" -eq 0 ]] || fail \
  "El batch terminó con código $run_exit. Se conserva el registro en $prep."

run_dir="$(sed -n 's/^BatchRunner finalizado:.*Salida en: //p' "$prep/console.log" | tail -n 1)"
[[ -n "$run_dir" && -d "$run_dir" ]] || fail \
  "No se identificó la carpeta de salida; revise $prep/console.log."
cp "$prep"/* "$run_dir/"

for name in runs.csv seeds.csv fronts.csv metrics.csv normalization.csv \
    reference_fronts.csv consolidated_fronts.csv metadata.txt; do
  [[ -s "$run_dir/$name" ]] || fail "Falta o está vacío: $run_dir/$name."
done
for name in runs.csv seeds.csv metrics.csv; do
  rows="$(awk 'END {print NR-1}' "$run_dir/$name")"
  [[ "$rows" -eq 138000 ]] || fail "$name tiene $rows filas; se esperaban 138000."
done
failed="$(awk -F, 'NR>1 && $8 != "OK" {n++} END {print n+0}' "$run_dir/runs.csv")"
[[ "$failed" -eq 0 ]] || fail "Hay $failed corridas fallidas."
grep -Fqx "git_commit: $commit" "$run_dir/metadata.txt" || fail \
  'El commit del metadata no coincide con el código fijado.'
if grep -Eiq '(NaN|Infinity)' "$run_dir"/*.csv; then
  fail 'Se encontraron valores no finitos en los CSV.'
fi

find "$run_dir" -maxdepth 1 -type f ! -name output-sha256.txt -print0 \
  | xargs -0 shasum -a 256 \
  | LC_ALL=C sort -k2 > "$run_dir/output-sha256.txt"
printf 'Corrida formal validada: %s\n' "$run_dir"
printf 'Guarde una copia de esta carpeta y de las imágenes fuente.\n'
