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

### Cambiado

- Factor de constricción corregido para conservar una magnitud positiva.
- Ciclo de SMPSO actualizado de forma síncrona.
- Velocidades iniciales fijadas en cero e inercia constante en `0.1`.
- Posiciones reparadas en los límites con reflexión de la velocidad.
- Mutación personalizada reemplazada por mutación polinomial de referencia.
- Selección de líderes reemplazada por torneo binario de crowding con empate
  aleatorio reproducible.
- Actualización de la memoria personal alineada con el esquema de referencia.

## [0.1.0] - pendiente

- Primera versión reproducible que será etiquetada después de validar el plugin.
