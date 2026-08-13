# Experimentos

## Configuraciones

Los archivos JSON registran propuestas reproducibles, pero el plugin interactivo
actual todavía no los consume. `pilot.json` representa una prueba técnica corta;
`full-factorial.json` documenta el diseño solicitado para la etapa formal.

## Manifiestos

Cada fila representa un par registrado. Las rutas son relativas a la raíz del
repositorio para evitar direcciones personales.

## Semillas

Las semillas formales se generarán una sola vez y se conservarán con las claves
`pair_id`, `particles`, `generations` y `run`. La tabla de ejemplo no es una
tabla definitiva de experimentos.

## Regla importante

No se debe cambiar una configuración después de iniciar una serie de 30 corridas.
Si se corrige un parámetro, se crea una configuración nueva y se registra el
commit correspondiente.
