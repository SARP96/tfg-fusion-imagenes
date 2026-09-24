# Metodología

## Problema

El experimento principal utiliza pares CT-MRI registrados. Se optimizan tres
variables:

- `r`: radio entero del elemento estructurante, configurado inicialmente en `[1,15]`.
- `mWTH`: peso real de los detalles claros, en `[0.35,2]`.
- `mBTH`: peso real de los detalles oscuros, en `[0.35,2]`.

Para cada modalidad se calcula:

```text
WTH(I) = I - apertura(I)
BTH(I) = cierre(I) - I
```

La imagen base y la fusión son:

```text
Ib   = (ICT + IMRI) / 2
Ifus = Ib + mWTH * max(WTH_CT, WTH_MRI)
          - mBTH * max(BTH_CT, BTH_MRI)
```

## Objetivos

```text
f1 = Entropía de Shannon de Ifus / 8
f2 = promedio de SSIM_global(Ifus,CT) y SSIM_global(Ifus,MRI)
```

Ambos se maximizan. La entropía usa 256 niveles y queda en `[0,1]`. SSIM usa
una sola media, varianza y covarianza por imagen, es decir, la variante global.

## SMPSO

El núcleo utiliza actualización síncrona, archivo externo acotado por crowding,
torneo binario de líderes con desempate aleatorio, velocidad máxima igual a la
mitad del rango de cada variable y mutación polinomial. Las velocidades empiezan
en cero y se reflejan al alcanzar un límite. La probabilidad de mutación por
variable es `1/3` porque el vector de decisión tiene tres dimensiones.

La configuración de referencia se detalla en
`docs/correcciones-smpso-referencia.md`.

## Evaluación de frentes

Al terminar todas las corridas de un par se construye `P*` con la unión no
dominada de todos sus frentes. El ideal y nadir experimentales de esa misma
unión normalizan los objetivos y los transforman a minimización. Cada corrida
se evalúa mediante HV con referencia `(1.1,1.1)` e IGD convencional contra
`P*`. Este postprocesamiento no interviene en el movimiento del SMPSO.

## Supuestos de entrada

- CT y MRI representan el mismo campo de visión y están registradas.
- Tienen las mismas dimensiones.
- Se convierten de manera controlada a escala de grises de 8 bits.
- No se modifica el preprocesamiento entre configuraciones.
- Los pares se declaran en `experiments/manifests/ct-mri-184-pairs.csv`.

## Trazabilidad

Cada corrida registra par, configuración, semilla, tiempo, evaluaciones, commit
y frente individual. Las semillas se generan de nuevo por ejecución y se
guardan en `seeds.csv` para repetirla exactamente con `--seeds`. Toda
modificación de fórmula, objetivo, preprocesamiento o parámetro debe versionarse
antes de iniciar una serie experimental formal.
