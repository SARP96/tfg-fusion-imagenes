# Registro de cambios

Todos los cambios relevantes del proyecto se documentarán en este archivo.

## [No publicado]

### Agregado

- Estructura Maven portable para el plugin de Fiji/ImageJ.
- Copia inicial de la implementación interactiva de SMPSO Top-Hat.
- Registro del plugin mediante `plugins.config`.
- Pruebas unitarias iniciales.
- Documentación de arquitectura, metodología y diseño experimental.
- Plantillas para pares de imágenes, configuraciones y semillas.
- Pruebas de constricción, mutación, selección de líderes, límites,
  reproducibilidad y presupuesto de evaluaciones.
- Documento técnico sobre la alineación con SMPSO de referencia y PT-CLAHE.
- `FusionCache`: precálculo de la morfología Top-Hat por cada `r` factible,
  reutilizable entre corridas y configuraciones del mismo par (~8-10× menos
  costo por evaluación).
- `BatchRunner`: ejecución batch del diseño factorial (`experiments/configs`
  + `experiments/manifests` + `experiments/seeds`), en paralelo entre
  corridas independientes, con salida en `results/runs/<fecha>/` (`runs.csv`,
  `fronts.csv`, `metadata.txt`).
- Pruebas de regresión que verifican que compartir `FusionCache` entre
  corridas produce el mismo resultado que calcularla de forma independiente.
- Documento técnico sobre la optimización de la ejecución batch
  (`docs/optimizacion-batch.md`).
- Manifiesto `experiments/manifests/ct-mri-184-pairs.csv` (184 pares CT-MRI,
  dataset complementario, no sustituye la evaluación VIS-IR).
- Primera corrida batch completa de extremo a extremo: 184 pares × 25
  configuraciones × 30 corridas (138.000 corridas, 0 fallidas). Resultados y
  tiempos reales en `docs/resultados-piloto-ct-mri.md` y
  `results/summaries/ct-mri-piloto-20260916-por-config.csv`.

### Cambiado

- Factor de constricción corregido para conservar una magnitud positiva.
- Ciclo de SMPSO actualizado de forma síncrona.
- Velocidades iniciales fijadas en cero e inercia constante en `0.1`.
- Posiciones reparadas en los límites con reflexión de la velocidad.
- Mutación personalizada reemplazada por mutación polinomial de referencia.
- Selección de líderes reemplazada por torneo binario de crowding con empate
  aleatorio reproducible.
- Actualización de la memoria personal alineada con el esquema de referencia.
- `Optimizer` ya no recalcula la morfología Top-Hat en cada evaluación: la
  obtiene de una `FusionCache` precalculada por par de imágenes.

## [0.1.0] - pendiente

- Primera versión reproducible que será etiquetada después de validar el plugin.
