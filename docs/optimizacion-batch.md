# Optimización de la ejecución batch

## Objetivo y alcance

Este documento explica cómo `BatchRunner` reduce el costo computacional de
ejecutar el diseño factorial completo (25 configuraciones × 30 corridas por
par). No cambia la ecuación de fusión, los objetivos ni los operadores SMPSO
descritos en `docs/formulas.md` y `docs/correcciones-smpso-referencia.md`:
únicamente reorganiza cuándo se calcula cada cosa y qué tan paralelo se
ejecuta.

## Diagnóstico: qué domina el costo

Cada evaluación de SMPSO (`fuse` + entropía + SSIM) recalculaba, antes de esta
optimización, la apertura y el cierre morfológico (Top-Hat blanco y negro,
para VIS e IR) en cada llamada. Medido sobre el código real del proyecto:

| Resolución | ms/evaluación (sin cache) |
|---|---:|
| 320×240 | 19,6 ms |
| 640×480 | 77,6 ms |
| 1024×768 | 215,3 ms |

Con 25 configuraciones × 30 corridas, el número de evaluaciones por par es:

```text
evaluaciones/corrida = particulas * (generaciones + 1)
evaluaciones/par = sum(particulas * (generaciones + 1) sobre las 25 configs) * 30 corridas
                  = 1.076.400 evaluaciones/par (con los factores actuales de
                    experiments/configs/full-factorial.json)
```

Sin optimizar, eso implica entre ~6 y ~64 horas por par en un solo núcleo,
según la resolución.

## Optimización 1: cache de morfología por `r`

`r` es entero y acotado (por defecto 1–15): solo existen `rMax-rMin+1`
combinaciones posibles de Top-Hat blanco/negro para VIS e IR, y esa
morfología no depende de `m` ni de la corrida o configuración en curso —
depende únicamente del par de imágenes y de `r`.

`SMPSO_TopHat_Fusion.FusionCache` precalcula los cuatro mapas (`WTH_IR`,
`WTH_VIS`, `BTH_IR`, `BTH_VIS`) para cada `r` factible **una sola vez por
par**, en el constructor. `Optimizer.fuse()` y `Optimizer.evaluate()` ya no
recalculan morfología: solo hacen la combinación lineal (que sí depende de
`m`) más entropía y SSIM.

Medido con el mismo código:

| Resolución | Precálculo (una vez por par) | ms/evaluación con cache | Mejora |
|---|---:|---:|---:|
| 320×240 | 425 ms | 2,13 ms | ×9,2 |
| 640×480 | 1.183 ms | 9,41 ms | ×8,2 |
| 1024×768 | 3.435 ms | 21,59 ms | ×10,0 |

El precálculo se amortiza sobre las 1.076.400 evaluaciones del par: es
insignificante frente al ahorro total.

Una vez construida, `FusionCache` es inmutable (todos sus campos son
`final`, poblados solo en el constructor) y de solo lectura: puede
compartirse de forma segura entre varios `Optimizer` ejecutándose en hilos
distintos, sin sincronización adicional. `SMPSOTopHatFusionTest` incluye una
prueba de regresión (`sharedFusionCacheMatchesIndependentCachePerRun`) que
verifica que compartir la cache entre corridas produce exactamente el mismo
frente que calcularla de forma independiente por corrida.

## Optimización 2: paralelismo entre corridas independientes

Las corridas de SMPSO (para una misma configuración o para configuraciones
distintas del mismo par) no comparten estado entre sí — cada una tiene su
propio generador aleatorio, su propio enjambre y su propio archivo externo.
`BatchRunner` construye una `FusionCache` por par y la reutiliza en un pool
de hilos (`--threads`, por defecto la cantidad de núcleos disponibles) que
ejecuta muchas corridas en paralelo.

Combinando ambas optimizaciones (cache + 8 núcleos), el tiempo por par baja
de horas a minutos:

| Resolución | Original (1 núcleo, sin cache) | Cache + 8 núcleos | Mejora total |
|---|---:|---:|---:|
| 320×240 | 5,85 h/par | 4,8 min/par | ×73,5 |
| 640×480 | 23,2 h/par | 21,1 min/par | ×65,9 |
| 1024×768 | 64,4 h/par | 48,5 min/par | ×79,7 |

## Riesgo evitado: hilos internos de `RankFilters`

`ij.plugin.filter.RankFilters` (usado por `whiteTopHat`/`blackTopHat`) trae
su propio mecanismo de hilos internos (contadores atómicos pensados para ser
accionados desde `PlugInFilterRunner`, el flujo normal de un plugin de
Fiji). Invocarlo directamente —como hace este proyecto, fuera de ese flujo—
con más de un hilo (`ij.Prefs.getThreads() > 1`) puede bloquear el proceso o
dejar hilos activos indefinidamente: se comprobó empíricamente al forzar
`Prefs.setThreads()` a varios núcleos antes de una llamada directa a
`rank()`.

Por eso `FusionCache` fija `Prefs.setThreads(1)` antes de precalcular la
morfología. Todo el paralelismo se hace en la capa externa (entre corridas
independientes en `BatchRunner`), nunca dentro de una misma llamada a
`RankFilters`.

## Qué no cambia

- La ecuación de fusión, los objetivos (entropía, SSIM global) y los
  operadores SMPSO siguen siendo los de `docs/formulas.md`.
- El plugin interactivo de Fiji no cambia su comportamiento ni su interfaz;
  usa el mismo `Optimizer` con el constructor de conveniencia que crea su
  propia `FusionCache` para un único par.
- Los indicadores HV, IGD y Spacing siguen sin implementarse; `runs.csv` y
  `fronts.csv` no los incluyen todavía.

## Validación empírica a escala real

Los números de las secciones anteriores vienen de un benchmark aislado (una
sola corrida corta, JIT ya caliente, sin otros hilos compitiendo). La primera
corrida real y sostenida del diseño factorial completo (184 pares CT-MRI,
138.000 corridas, 17,15 h con 8 hilos) mostró un costo real por evaluación de
~2,49 ms — no los 0,989 ms del benchmark aislado. El detalle completo,
incluida la causa más probable de la diferencia (contención de ancho de
banda de memoria entre hilos + algunos estancamientos puntuales de hasta 41
minutos durante una corrida nocturna desatendida), está en
`docs/resultados-piloto-ct-mri.md`. Para planificar tiempos de corridas
futuras, usar esa cifra real en vez de la del benchmark aislado.

## Otras palancas consideradas (no implementadas aquí)

- **Presupuesto fijo de evaluaciones en vez de factorial completo en
  partículas × generaciones**: decisión pendiente en
  `docs/experimental-design.md`, no una optimización de ingeniería.
- **Distribución entre varias máquinas**: el mismo paralelismo por corridas
  se puede repartir entre PCs si el dataset final lo justifica; no fue
  necesario para los tiempos actuales.
