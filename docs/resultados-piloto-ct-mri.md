# Resultados: piloto batch sobre CT-MRI (184 pares)

> **Registro histórico.** Esta corrida se ejecutó con la formulación anterior:
> un solo peso `m` en `[0.1,2]`, entropía sin normalizar y sin HV/IGD. Sus cifras
> validan el rendimiento del batch, pero no son resultados comparables con la
> formulación actual de tres variables. CT-MRI fue adoptado posteriormente como
> dataset principal.

## Qué es esto y qué NO es

Esta es la primera ejecución completa de extremo a extremo de `BatchRunner`
sobre un dataset real, para validar que la automatización batch (`FusionCache`
+ ejecución paralela) funciona correctamente a la escala del diseño factorial
completo. **No es el resultado formal de la tesis.**

Es una prueba histórica de ingeniería para comprobar que el batch soportaba el
volumen completo; no debe mezclarse con la futura serie formal.

## Configuración de la corrida

| Campo | Valor |
|---|---|
| Dataset | `CT-MRI.zip` (fuente externa al repositorio), 184 pares, emparejados por nombre de archivo |
| Ubicación local | `data/raw/ct/`, `data/raw/mri/` (excluido de Git) |
| Manifiesto | Versión histórica de [experiments/manifests/ct-mri-184-pairs.csv](../experiments/manifests/ct-mri-184-pairs.csv), entonces con `dataset=ct-mri-complementario`; la etiqueta actual cambió al adoptar CT-MRI como alcance exclusivo |
| Configuración | `experiments/configs/full-factorial.json` (particulas × generaciones completo, 25 configuraciones) |
| Corridas por configuración | 30 (5.520 corridas por configuración, 138.000 en total) |
| Semillas | Derivadas automáticamente (`seed_source=derived`), **no** pre-registradas — válido para un piloto, no para una serie formal |
| Base image (`Ib`) | `to-be-confirmed` en el config histórico: se usó el promedio de CT y MRI por defecto, con aviso impreso; la decisión aún no estaba fijada en esa corrida |
| Commit base | `c710f4ae8a4265b13bba79200b9a7fbb975ca34c`; `BatchRunner` y `FusionCache` tenían cambios sin confirmar. Un commit posterior no identifica retroactivamente el código exacto de esta corrida |
| Entorno | 8 hilos, 8 núcleos disponibles, ejecutado desde IntelliJ IDEA 2025.3.2 (JDK 18.0.2.1 en runtime; bytecode compilado a Java 8) |
| Rango de `r` / `m` | `[1,15]` / `[0.1,2.0]` |

## Resultado: 138.000/138.000 corridas, 0 fallidas

```text
corridas completadas: 138.000
corridas fallidas:     0
evaluaciones totales: 198.057.600
```

No hubo pares que fallaran al cargar ni corridas con excepciones — el
`FusionCache` compartido entre 8 hilos y 25×30 corridas por par se sostuvo
sin errores durante toda la ejecución. Los datos no tienen valores `NaN` ni
infinitos; entropía observada en `[1,8958, 5,4670]` (por debajo del máximo
teórico de 8, esperable en imágenes médicas con rango dinámico limitado) y
SSIM en `[0,5551, 0,8702]`.

## Tiempo real vs. proyectado

| | Proyectado (benchmark aislado) | Real (corrida completa) |
|---|---:|---:|
| Tiempo total (8 hilos) | ~6,8 h | **17,15 h** |
| ms/evaluación implícito | 0,989 ms | ~2,49 ms |

El tiempo real fue ~2,5× el proyectado. El análisis de `runs.csv` mostró:

1. **59 corridas (0,04 %) con estancamientos anómalos** (duración > 1 minuto,
   hasta 41,4 minutos, cuando la duración normal más alta observada es de
   pocos segundos). La corrida atravesó la madrugada (09:35 a 02:44 del día
   siguiente). La causa no quedó registrada: podrían influir el reposo del
   equipo, una carga externa, pausas del runtime u otros factores. Sin esos
   59 casos, el total estimado habría sido ~14,96 h en vez de 17,15 h.
2. **Incluso descontando esos casos, el paralelismo real logrado fue ~3,6×**
   sobre 8 hilos (no 8×). `fuse()`, la entropía y el SSIM son bucles simples
   sobre arreglos `float[]` — trabajo limitado por ancho de banda de memoria,
   no por cómputo puro. Ocho hilos leyendo/escribiendo arreglos en paralelo
   compiten por el mismo bus de memoria y no escalan linealmente en hardware
   real, más el hecho de que la máquina se usó con normalidad (IDE, otros
   programas) durante esas horas.

**Implicación práctica histórica**: ~2,5 ms por evaluación fue la estimación
observada para aquel equipo y condiciones de ejecución. No debe extrapolarse a
otra computadora sin medir una corrida piloto.
`docs/optimizacion-batch.md` tiene el detalle del benchmark aislado original;
este documento son los números de una corrida real y sostenida.

## Resultados agregados por configuración

Tabla completa (todas las 5.520 corridas de cada una de las 25
configuraciones, sin selección manual) en
[results/summaries/ct-mri-piloto-20260916-por-config.csv](../results/summaries/ct-mri-piloto-20260916-por-config.csv).
Resumen:

| Partículas | Generaciones | Evaluaciones/corrida | Duración promedio | Tamaño de frente promedio |
|---:|---:|---:|---:|---:|
| 4 | 10 | 44 | 0,10 s | 13,4 |
| 4 | 150 | 604 | 3,11 s* | 27,4 |
| 20 | 50 | 1.020 | 2,22 s | 28,1 |
| 30 | 100 | 3.030 | 10,73 s* | 28,4 |
| 40 | 150 | 6.040 | 16,39 s* | 28,5 |

*Promedio afectado por los estancamientos anómalos descritos arriba; ver
mínimos/máximos en el CSV completo para la distribución real.

Patrón esperado y consistente: a mayor presupuesto de evaluaciones
(partículas × generaciones), el frente no dominado se acerca más al límite
del archivo externo (`archiveSize=30`), reflejando mejor cobertura del
espacio de búsqueda. Con `P4_G10` (presupuesto mínimo) el frente promedio es
notablemente más chico (13,4).

## Dónde están los datos completos

`results/runs/20260916-053543/` contiene `runs.csv` (138.000 filas, 20 MB) y
`fronts.csv` (3.669.688 filas de soluciones no dominadas, 269 MB) —
**quedan solo en la máquina local**, excluidos de Git por
`results/runs/` en `.gitignore`, tal como indica `results/README.md`. Lo que
se sube al repositorio es este documento más el resumen agregado en
`results/summaries/`.

## Uso actual de estas cifras

La formulación actual genera semillas nuevas por corrida y las guarda en
`seeds.csv` para repetir una ejecución. Esta corrida histórica conserva sus
semillas derivadas originales en `runs.csv`.
