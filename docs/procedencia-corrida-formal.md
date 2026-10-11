# Procedencia de la corrida formal CT-MRI

Esta guía fija la relación entre código, datos, entorno, semillas y resultados.
La corrida `results/runs/20260923-210058` es exploratoria: se ejecutó con cambios
sin confirmar y su `metadata.txt` solo identifica el commit base `e99992c`.
El commit posterior no reconstruye retroactivamente el árbol de trabajo usado.

## Ejecución recomendada

Después de confirmar los cambios y revisar una muestra de pares registrados:

```bash
cd /Users/sebastianrojas/Documents/Tesis/tfg-fusion-imagenes
./scripts/run-formal.sh --check
THREADS=8 ./scripts/run-formal.sh
```

El script automatiza las comprobaciones, la compilación, las huellas de los
insumos, el batch y la validación de salida descritos abajo. Se puede cambiar
`THREADS`, `IJ_JAR` u `OUT_DIR` como parámetros del equipo. No acepta filtros
de pares ni límites de corridas; genera semillas nuevas para la serie formal.
Si falla o se interrumpe, conserva el registro previo en `results/tmp` y no
declara válida una salida parcial.

Las secciones siguientes permiten auditar o reproducir manualmente cada paso.

## 1. Cerrar y versionar los insumos

1. Revisar título, alcance CT-MRI, preprocesamiento, ecuaciones y configuración.
   No cambiar estos elementos durante la serie formal.
2. Confirmar en Git todos los cambios de código, documentación, manifiesto y
   configuración. No incluir `data/raw` ni `results/runs` en el commit.
3. Validar los archivos del manifiesto y correr las pruebas:

```bash
cd /Users/sebastianrojas/Documents/Tesis/tfg-fusion-imagenes
./scripts/validate-manifest.sh experiments/manifests/ct-mri-184-pairs.csv
mvn clean verify
git diff --check
git status --porcelain
```

La última orden debe quedar vacía. El validador comprueba rutas, pero no
registro espacial ni correspondencia anatómica; revisar visualmente una muestra
de pares antes de la corrida y documentar cualquier exclusión.

## 2. Registrar una huella previa

Ejecutar desde la raíz del repositorio. La carpeta `results/tmp` está excluida
de Git; se copiará al directorio de la corrida terminada.

```bash
PREP="results/tmp/formal-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$PREP"
git rev-parse HEAD > "$PREP/commit.txt"
git status --porcelain > "$PREP/git-status.txt"
{
  date -u '+UTC: %Y-%m-%dT%H:%M:%SZ'
  uname -a
  java -version 2>&1
  mvn -version
  sysctl -n machdep.cpu.brand_string 2>/dev/null || sysctl -n hw.model
  sysctl -n hw.memsize
} > "$PREP/environment.txt"
IJ_JAR="$HOME/.m2/repository/net/imagej/ij/1.54p/ij-1.54p.jar"
shasum -a 256 target/SMPSO_TopHat_Fusion.jar "$IJ_JAR" \
  experiments/configs/full-factorial.json \
  experiments/manifests/ct-mri-184-pairs.csv \
  > "$PREP/input-sha256.txt"
find data/raw/ct data/raw/mri -type f -name '*.png' -print0 \
  | xargs -0 shasum -a 256 \
  | LC_ALL=C sort -k2 > "$PREP/images-sha256.txt"
wc -l "$PREP/images-sha256.txt"
```

Se esperan **368 imágenes**: 184 CT y 184 MRI. Conservar la lista de hashes,
no solo un hash agregado. Si se reemplaza o reprocesa una imagen, crear una
nueva versión del dataset y repetir la serie correspondiente.

## 3. Ejecutar la serie completa

Usar el JAR construido en el paso 1. No pasar `--pairs` ni `--max-runs`:

```bash
set -o pipefail
java -cp "target/SMPSO_TopHat_Fusion.jar:$IJ_JAR" \
  py.fpuna.fusion.plugin.BatchRunner \
  --config experiments/configs/full-factorial.json \
  --manifest experiments/manifests/ct-mri-184-pairs.csv \
  --out results/runs \
  --threads 8 2>&1 | tee "$PREP/console.log"
RUN_EXIT=$?
printf '%s\n' "$RUN_EXIT" > "$PREP/exit-code.txt"
test "$RUN_EXIT" -eq 0
```

Sin `--seeds`, esta ejecución genera semillas aleatorias y las guarda en
`seeds.csv`. Registrar el código de salida del proceso. Si se interrumpe, no
tratar los archivos parciales como una serie formal: el batch no reanuda.

## 4. Validar y preservar la salida

Usar la carpeta concreta mostrada al finalizar, no asumir que la más reciente
pertenece a la corrida deseada. Sustituir la ruta de ejemplo:

```bash
RUN_DIR="results/runs/AAAAMMDD-HHMMSS"
cp "$PREP"/* "$RUN_DIR"/
wc -l "$RUN_DIR/runs.csv" "$RUN_DIR/metrics.csv" "$RUN_DIR/seeds.csv"
awk -F, 'NR>1 { n++; if ($8 != "OK") bad++ } END { print "corridas=" n, "fallidas=" bad }' \
  "$RUN_DIR/runs.csv"
rg '^git_commit:' "$RUN_DIR/metadata.txt"
shasum -a 256 "$RUN_DIR"/*.csv "$RUN_DIR/metadata.txt" \
  "$RUN_DIR/console.log" > "$RUN_DIR/output-sha256.txt"
```

Se esperan 138.000 filas de corridas, métricas y semillas, más su encabezado;
`fallidas=0`; `exit-code.txt=0`; y el mismo commit en `commit.txt` y
`metadata.txt`. Comprobar
también que existen `fronts.csv`, `normalization.csv`, `reference_fronts.csv`
y `consolidated_fronts.csv`, y que sus valores numéricos son finitos.

Guardar una copia adicional del directorio completo y de las imágenes fuente
en almacenamiento confiable. Los resultados masivos están excluidos de Git.
Para repetir las soluciones, pasar `--seeds "$RUN_DIR/seeds.csv"` con el
mismo commit, configuración, manifiesto e imágenes. Tiempos y marcas de fecha
pueden diferir aunque los frentes se reproduzcan.
