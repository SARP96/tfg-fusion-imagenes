# Scripts

- `install-fiji.sh`: compila si es necesario e instala el JAR en Fiji.
- `validate-manifest.sh`: comprueba el encabezado y la existencia de las imágenes.
- `project-info.sh`: muestra la versión del proyecto y el commit actual.
- `run-formal.sh`: valida, compila y ejecuta el factorial CT-MRI completo desde
  un commit limpio; conserva hashes, entorno, log, semillas y salida validada.

Después de confirmar los cambios en Git, usar `./scripts/run-formal.sh --check`
para verificar los requisitos sin correr el batch. La ejecución formal se
inicia con `THREADS=8 ./scripts/run-formal.sh`. Se pueden definir también
`IJ_JAR` y `OUT_DIR` para adaptar rutas del equipo; la configuración experimental
y el manifiesto permanecen fijos.

El ejecutor batch es la clase
`py.fpuna.fusion.plugin.BatchRunner`; sus opciones y archivos de salida se
documentan en el README principal y en `docs/optimizacion-batch.md`.
