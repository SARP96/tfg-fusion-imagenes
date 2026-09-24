# Diseño experimental

## Factores

- Partículas: `4,10,20,30,40`.
- Generaciones: `10,30,50,100,150`.
- Réplicas independientes: 30 por par y configuración.
- Unidad de aplicación: un par CT-MRI registrado.

El diseño factorial contiene 25 configuraciones y 750 corridas por par. Con 184
pares son 138.000 corridas. La comparación es por generaciones; también se
registran evaluaciones y tiempo porque el costo cambia con ambos factores.

## Salida de cada corrida

- Identificador de par, configuración y corrida.
- Semilla `long` generada aleatoriamente por corrida, guardada en `seeds.csv`,
  origen de la semilla y hash del commit. `--seeds` permite repetirla.
- Cantidad de evaluaciones, duración y estado.
- Frente individual con `r`, `mWTH`, `mBTH`, entropía normalizada y SSIM global.
- HV e IGD calculados con una escala común por par.

## Frentes

Para cada par y configuración se unifican sus corridas y se eliminan soluciones
dominadas o duplicadas para obtener el frente consolidado. Los frentes
individuales se conservan para analizar variabilidad.

El frente de referencia común `P*` de cada par es la unión no dominada de todas
las configuraciones y corridas. El ideal y nadir experimentales se calculan
sobre esa unión. HV usa `(1.1,1.1)` como referencia en el espacio normalizado de
minimización; IGD compara cada frente individual con `P*`.

## Decisiones adoptadas

- Dataset principal CT-MRI con 184 pares manifestados.
- Base promedio fija.
- Pesos independientes `mWTH,mBTH` en `[0.35,2]`.
- Entropía normalizada y SSIM global promedio.
- Comparación por generaciones.
- Indicadores HV, IGD y tiempo; sin Spacing, MI ni `Q^{AB/F}`.

El rango de radio se mantiene en `[1,15]` en las configuraciones versionadas.
Un cambio posterior requiere una configuración nueva y un commit identificable.

## Análisis estadístico posterior

Para cada par y configuración se resumirán las 30 corridas con mediana, rango
intercuartílico, media y desviación estándar. La comparación estadística entre
configuraciones queda fuera del ejecutor: se realizará sobre `metrics.csv` una
vez finalizada la serie completa.
