# Experimentos

## Configuraciones

Los archivos JSON registran propuestas reproducibles. `BatchRunner`
(`py.fpuna.fusion.plugin.BatchRunner`, ver README.md raíz) ya los consume
para ejecutar el diseño factorial batch; el plugin interactivo de Fiji sigue
usando sus propios campos del diálogo. `pilot.json` representa una prueba
técnica corta; `full-factorial.json` documenta el diseño solicitado para la
etapa formal.

## Manifiestos

Cada fila representa un par registrado. Las rutas son relativas a la raíz del
repositorio para evitar direcciones personales. `BatchRunner --manifest`
lee este formato directamente.

## Semillas

Las semillas formales se generarán una sola vez y se conservarán con las claves
`pair_id`, `particles`, `generations` y `run`. La tabla de ejemplo no es una
tabla definitiva de experimentos. `BatchRunner --seeds` las usa cuando están
presentes; las combinaciones ausentes se derivan de forma determinística y se
marcan como `derived` en `runs.csv` — no deben usarse en la serie formal de
30 corridas.

## Regla importante

No se debe cambiar una configuración después de iniciar una serie de 30 corridas.
Si se corrige un parámetro, se crea una configuración nueva y se registra el
commit correspondiente.
