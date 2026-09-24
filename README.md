# SMPSO Top-Hat Image Fusion

Plugin experimental para Fiji/ImageJ destinado a optimizar mediante SMPSO los
parámetros de la transformada Top-Hat empleados en la fusión multimodal de
imágenes CT y MRI registradas.

## Formulación actual

Las variables de decisión son el radio entero `r` del elemento estructurante,
el peso real `mWTH` para los detalles claros y el peso real `mBTH` para los
detalles oscuros. Ambos pesos pertenecen a `[0.35, 2]`. La imagen base es
siempre el promedio de CT y MRI:

```text
Ib   = (ICT + IMRI) / 2
Ifus = Ib + mWTH * max(WTH_CT, WTH_MRI)
          - mBTH * max(BTH_CT, BTH_MRI)
```

Los dos objetivos se maximizan simultáneamente:

```text
f1 = Entropía_Shannon_8_bits(Ifus) / 8
f2 = (SSIM_global(Ifus, CT) + SSIM_global(Ifus, MRI)) / 2
```

La entropía queda en `[0,1]`. Para comparar corridas, ambos objetivos de
maximización se normalizan por par usando el ideal y nadir experimentales de la
unión completa y se transforman a minimización. El punto de referencia de HV es
`(1.1,1.1)` y el frente de referencia de IGD es `P*`, la unión no dominada de
todas las configuraciones y corridas del mismo par.

## Estado

La versión `0.1.0-SNAPSHOT` incluye:

- Ejecución interactiva como plugin de Fiji/ImageJ.
- Fusión Top-Hat CT-MRI con imagen base promedio fija y pesos WTH/BTH independientes.
- SMPSO alineado con los operadores de referencia: actualización síncrona,
  velocidad restringida, torneo de líderes y mutación polinomial.
- Archivo externo no dominado y distancia de crowding.
- Entropía de Shannon normalizada y SSIM global promedio CT-MRI.
- Semilla configurable para reproducir una corrida.
- Tabla, gráfico y reconstrucción opcional de soluciones Pareto.
- Ejecución batch paralela con caché morfológica compartida por par.
- Frentes individuales, frente consolidado por configuración y `P*` por par.
- Cálculo de HV bidimensional e IGD convencional con normalización común.
- Pruebas unitarias del núcleo, reproducibilidad, operadores SMPSO e indicadores.

No se implementan SSIM local, Spacing, MI ni `Q^{AB/F}` en la formulación actual.

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

Abra previamente una imagen CT y su correspondiente imagen MRI.
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
    --manifest experiments/manifests/ct-mri-184-pairs.csv \
    --pairs ct_16003 \
    --out results/runs \
    --threads 8
```

Genera, dentro de `results/runs/<fecha-hora>/` (excluido de Git):

- `runs.csv`: una fila por corrida, con semilla, evaluaciones, duración,
  tamaño del frente, hash del commit y estado (`OK`/`FAILED`).
- `seeds.csv`: semillas exactas de todas las corridas programadas, listas para
  reutilizar con `--seeds`.
- `fronts.csv`: frentes individuales con `r`, `mWTH`, `mBTH`, entropía y SSIM.
- `metrics.csv`: HV e IGD de cada corrida.
- `normalization.csv`: ideal, nadir y punto de referencia usados por par.
- `reference_fronts.csv`: frente empírico común `P*`, crudo y normalizado.
- `consolidated_fronts.csv`: unión no dominada de las corridas por configuración.
- `metadata.txt`: configuración, manifiesto, semillas, commit e hilos usados.

Sin `--seeds`, cada lanzamiento genera semillas aleatorias nuevas; `runs.csv`
marca su origen como `generated` y `seeds.csv` permite repetir la ejecución.
Para repetirla, pase `--seeds results/runs/<fecha-hora>/seeds.csv` con la misma
configuración y el mismo manifiesto. Si falta una semilla requerida, el batch
se detiene en vez de cambiarla silenciosamente.

Opciones útiles para pruebas rápidas: `--pairs id1,id2` (filtra pares) y
`--max-runs N` (limita el total de corridas).

## Organización del proyecto

- `src/main/java`: código fuente del plugin.
- `src/test/java`: pruebas automatizadas.
- `experiments/configs`: configuraciones versionadas de experimentos.
- `experiments/manifests`: descripción de los pares, sin incluir el dataset.
- `experiments/seeds`: ejemplos o archivos de semillas elegidos para conservar
  en Git; cada ejecución también genera su propio `seeds.csv` en resultados.
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
