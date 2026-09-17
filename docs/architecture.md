# Arquitectura

## Estado actual

La implementación interactiva se conserva en una sola clase para mantener el
comportamiento ya probado en Fiji:

```text
SMPSO_TopHat_Fusion
├── interfaz y validación de entradas
├── FusionCache (morfología Top-Hat precalculada por par, ver docs/optimizacion-batch.md)
├── Optimizer
├── Particle
├── Solution
├── dominancia y crowding
├── morfología Top-Hat
└── entropía y SSIM global
```

La clase se ubica en el paquete `py.fpuna.fusion.plugin` y se registra mediante
`src/main/resources/plugins.config`.

`BatchRunner`, en el mismo paquete, es el primer paso hacia la separación
descrita más abajo: reutiliza `Optimizer`/`FusionCache` para ejecutar el
diseño factorial fuera de Fiji, sin tocar la definición de los objetivos ni
del algoritmo. Ver `docs/optimizacion-batch.md`.

## Arquitectura objetivo

La automatización experimental requerirá separar responsabilidades:

```text
plugin/       Adaptador gráfico de Fiji
smpso/        Movimiento, mutación, archivo y selección de líderes
fusion/       Morfología y ecuación de fusión
metrics/      Objetivos e indicadores de desempeño
experiment/   Ejecución batch, semillas, persistencia y reanudación
model/        Configuraciones, pares y soluciones
```

El núcleo no debe depender de ventanas de Fiji. De esa forma, una misma
implementación podrá ejecutarse desde el plugin interactivo, las pruebas y el
procesador batch.

## Regla de evolución

La separación se realizará gradualmente y con pruebas de regresión. No debe
modificarse simultáneamente la arquitectura y la definición matemática de los
objetivos durante una serie experimental formal.
