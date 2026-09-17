# SMPSO Top-Hat Image Fusion

Plugin experimental para Fiji/ImageJ destinado a optimizar mediante SMPSO los
parámetros de la transformada Top-Hat empleados en la fusión de imágenes
visibles e infrarrojas registradas.

## Formulación actual

Las variables de decisión son el radio entero `r` del elemento estructurante y
el peso real de contraste `m`. La imagen fusionada se calcula como:

```text
Ifus = Ib + m * max(WTH_IR, WTH_VIS) - m * max(BTH_IR, BTH_VIS)
```

Los dos objetivos se maximizan simultáneamente:

```text
f1 = Entropía(Ifus)
f2 = (SSIM(Ifus, VIS) + SSIM(Ifus, IR)) / 2
```

## Estado

La versión `0.1.0-SNAPSHOT` incluye:

- Ejecución interactiva como plugin de Fiji/ImageJ.
- Fusión Top-Hat con imagen base promedio, visible o infrarroja.
- SMPSO alineado con los operadores de referencia: actualización síncrona,
  velocidad restringida, torneo de líderes y mutación polinomial.
- Archivo externo no dominado y distancia de crowding.
- Entropía de Shannon y SSIM global.
- Semilla configurable para reproducir una corrida.
- Tabla, gráfico y reconstrucción opcional de soluciones Pareto.
- Pruebas unitarias del núcleo, reproducibilidad y operadores SMPSO.

Todavía no están implementados el SSIM local, la ejecución batch de 30 corridas,
el frente consolidado ni los indicadores HV, IGD y Spacing. Esos elementos se
encuentran documentados como trabajo planificado y no deben presentarse como
funcionalidades terminadas.

Las correcciones y parámetros adoptados para el algoritmo se explican en
`docs/correcciones-smpso-referencia.md`.

## Requisitos

- Fiji/ImageJ instalado para utilizar el plugin.
- JDK 8 o posterior.
- Maven 3, recomendado para compilar y ejecutar las pruebas.

## Compilación

Con Maven:

```bash
mvn clean verify
```

El JAR se genera en:

```text
target/SMPSO_TopHat_Fusion.jar
```

Sin Maven, se puede utilizar el JDK incluido con Fiji:

```bash
export FIJI_DIR="/ruta/a/Fiji.app"
./build.sh
```

## Instalación en Fiji

Después de compilar:

```bash
export FIJI_DIR="/ruta/a/Fiji.app"
./scripts/install-fiji.sh
```

Reinicie Fiji y seleccione:

```text
Plugins > SMPSO TopHat Fusion > SMPSO Top-Hat Fusion
```

Abra previamente una imagen visible y su correspondiente imagen infrarroja.
Ambas deben estar registradas y tener el mismo ancho y alto.

## Ejecución batch (sin Fiji)

`BatchRunner` ejecuta el diseño factorial completo (todas las combinaciones
de partículas/generaciones, con sus corridas) fuera de Fiji, en paralelo, y
consume directamente los archivos de `experiments/configs` y
`experiments/manifests`. Por cada par de imágenes calcula la morfología
Top-Hat una sola vez (para cada `r` factible) y la reutiliza en todas las
configuraciones y corridas de ese par; ver `docs/optimizacion-batch.md` para
el detalle de esta optimización y de la paralelización.

```bash
mvn -q clean compile
java -cp target/classes:<ruta-a-ij-1.54p.jar> py.fpuna.fusion.plugin.BatchRunner \
    --config experiments/configs/pilot.json \
    --manifest experiments/manifests/image-pairs-example.csv \
    --seeds experiments/seeds/seeds-example.csv \
    --out results/runs \
    --threads 8
```

Genera, dentro de `results/runs/<fecha-hora>/` (excluido de Git):

- `runs.csv`: una fila por corrida, con semilla, evaluaciones, duración,
  tamaño del frente, hash del commit y estado (`OK`/`FAILED`).
- `fronts.csv`: una fila por solución no dominada (`r`, `m`, entropía, SSIM).
- `metadata.txt`: configuración, manifiesto, semillas, commit e hilos usados.

Las semillas ausentes en el archivo indicado se derivan de forma
determinística y se marcan como `derived` (no `file`) en `runs.csv`: sirven
para corridas exploratorias, no para la serie formal de 30 corridas, que debe
usar semillas pre-registradas en `experiments/seeds`.

Opciones útiles para pruebas rápidas: `--pairs id1,id2` (filtra pares) y
`--max-runs N` (limita el total de corridas).

## Organización del proyecto

- `src/main/java`: código fuente del plugin.
- `src/test/java`: pruebas automatizadas.
- `experiments/configs`: configuraciones versionadas de experimentos.
- `experiments/manifests`: descripción de los pares, sin incluir el dataset.
- `experiments/seeds`: semillas planificadas o utilizadas.
- `docs`: metodología, arquitectura e instalación detallada.
- `data`: instrucciones para preparar datos locales ignorados por Git.
- `results`: resúmenes y figuras publicables; las corridas masivas se ignoran.

## Reproducibilidad

Cada corrida formal deberá registrar como mínimo el par, la configuración, el
número de corrida, la semilla, el tiempo, las evaluaciones y el identificador del
commit de Git. Consulte `docs/experimental-design.md`.

## Datos y privacidad

Este repositorio no redistribuye datasets. Antes de publicar imágenes se deben
verificar la licencia, los términos de uso, la anonimización y las restricciones
institucionales. Consulte `data/README.md`.

## Licencia y cita

La licencia definitiva debe acordarse con los autores, tutores y la institución.
Mientras eso no ocurra, no se concede permiso de redistribución. Los metadatos
provisionales de cita están en `CITATION.cff`.
