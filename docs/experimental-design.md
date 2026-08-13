# Diseño experimental planificado

Este documento describe el protocolo propuesto. Las decisiones marcadas como
pendientes deben aprobarse antes de producir resultados definitivos.

## Factores

- Partículas: `4, 10, 20, 30, 40`.
- Generaciones: `10, 30, 50, 100, 150`.
- Réplicas independientes: 30 por par y configuración.
- Unidad de aplicación: un par VIS-IR registrado.

El diseño factorial completo contiene 25 configuraciones y 750 corridas por par.
Al variar partículas y generaciones también cambia el número de evaluaciones;
por tanto, debe registrarse tanto calidad como costo computacional.

## Salida de cada corrida

- Identificador del par, configuración y corrida.
- Semilla `long` exacta.
- Hash del commit de Git.
- Parámetros completos del algoritmo.
- Cantidad de evaluaciones y duración.
- Frente no dominado individual con `r`, `m`, entropía y SSIM.
- HV, IGD y Spacing cuando estén implementados.

## Frente consolidado

Para cada par y configuración se unificarán los 30 frentes y se eliminarán las
soluciones dominadas y duplicadas. El consolidado podrá mostrarse en pantalla,
pero los frentes individuales se conservarán para medir variabilidad.

## Frente de referencia

IGD no puede calcularse contra el frente consolidado particular de cada
configuración. Se requiere un frente común por par, obtenido mediante una de
estas alternativas:

1. Exploración densa del espacio `(r,m)`.
2. Unión no dominada de todas las configuraciones y corridas.

La alternativa definitiva y la normalización deben acordarse antes del análisis.

## Análisis estadístico propuesto

Para cada par y configuración se resumirán las 30 corridas con mediana, rango
intercuartílico, media y desviación estándar. La comparación general podrá usar
Friedman y comparaciones pareadas posteriores con Wilcoxon signed-rank y
corrección de Holm.

## Decisiones pendientes

- Dataset VIS-IR principal y subconjunto piloto.
- SSIM local o global y parámetros de ventana.
- Entropía normalizada o sin normalizar.
- Imagen base `Ib` fija.
- Rango definitivo de `r` y `m`.
- Tamaño fijo del archivo externo.
- Parámetros `c1`, `c2`, `w`, constricción, `Vmax` y mutación.
- Construcción del frente de referencia y punto de referencia de HV.
- Comparación por generaciones o por presupuesto fijo de evaluaciones.
