# Experimentos

## Configuraciones

Los archivos JSON registran propuestas reproducibles. `BatchRunner`
(`py.fpuna.fusion.plugin.BatchRunner`, ver README.md raíz) ya los consume
para ejecutar el diseño factorial batch; el plugin interactivo de Fiji sigue
usando sus propios campos del diálogo. `pilot.json` representa una prueba
técnica corta; `full-factorial.json` documenta el diseño solicitado para la
etapa formal.

## Manifiestos

Cada fila representa un par CT-MRI registrado. Las columnas principales son
`pair_id`, `ct_path` y `mri_path`; las rutas son relativas a la raíz del
repositorio para evitar direcciones personales. `BatchRunner --manifest` lee
este formato directamente y mantiene compatibilidad con las antiguas columnas
`visible_path` e `infrared_path`.

## Semillas

Al omitir `--seeds`, cada ejecución genera semillas aleatorias nuevas y las
guarda en `results/runs/<fecha-hora>/seeds.csv`. Cada fila identifica
`pair_id`, `particles`, `generations` y `run`; el mismo archivo puede pasarse
después mediante `--seeds` para repetir el experimento. Si alguna combinación
requerida falta en el archivo, `BatchRunner` se detiene. La tabla de ejemplo
ilustra el formato, pero no cubre el diseño factorial completo.

## Regla importante

No se debe cambiar una configuración después de iniciar una serie de 30 corridas.
Si se corrige un parámetro, se crea una configuración nueva y se registra el
commit correspondiente.
