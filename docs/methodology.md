# Metodología

## Problema

Se optimizan dos variables:

- `r`: radio entero del elemento estructurante.
- `m`: peso real aplicado a los detalles de contraste.

Para cada modalidad se calcula Top-Hat blanco y negro:

```text
WTH(I) = I - apertura(I)
BTH(I) = cierre(I) - I
```

La fusión actual es:

```text
Ifus = Ib + m * max(WTH_IR, WTH_VIS) - m * max(BTH_IR, BTH_VIS)
```

`Ib` puede ser el promedio VIS/IR, VIS o IR. Esta elección debe permanecer fija
en todos los tratamientos que se comparen.

## Objetivos actuales

```text
f1 = Entropía de Shannon de Ifus
f2 = promedio de SSIM(Ifus,VIS) y SSIM(Ifus,IR)
```

Ambos objetivos se maximizan. La versión inicial calcula entropía sin normalizar
en un histograma de 256 niveles y SSIM global. El protocolo definitivo debe
decidir si se reemplazará por entropía normalizada y SSIM local antes de generar
resultados formales.

## SMPSO implementado

El núcleo utiliza actualización síncrona, archivo externo acotado por crowding,
torneo binario para seleccionar líderes, velocidad máxima igual a la mitad del
rango de cada variable y mutación polinomial. Las velocidades comienzan en cero;
al alcanzar un límite, la posición se repara y la velocidad se refleja.

La configuración y sus fuentes se detallan en
`docs/correcciones-smpso-referencia.md`. Estas correcciones no cambian la
ecuación de fusión ni la definición actual de los objetivos.

## Supuestos de entrada

- Las imágenes representan el mismo campo de visión.
- VIS e IR están registradas espacialmente.
- Tienen las mismas dimensiones.
- Se convierten de manera controlada a escala de grises de 8 bits.
- No se modifica el preprocesamiento entre configuraciones.

## Alcance

El experimento principal debe utilizar pares visibles e infrarrojos. Los pares
CT-MRI pueden utilizarse como validación complementaria de fusión multimodal,
pero no sustituyen la evaluación VIS-IR indicada en el objetivo de la tesis.

## Trazabilidad

Toda modificación de fórmula, objetivo, ventana SSIM, normalización o parámetros
SMPSO debe quedar registrada antes de ejecutar una nueva serie de pruebas.
