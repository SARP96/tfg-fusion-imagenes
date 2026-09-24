# Correcciones del SMPSO de referencia

## Objetivo y alcance

Este documento explica las correcciones aplicadas al núcleo SMPSO. Después de
esa etapa, el proyecto amplió el vector de decisión a `(r,mWTH,mBTH)` e incorporó
la ejecución batch, la entropía normalizada, HV e IGD. Esos cambios posteriores
se describen en `docs/methodology.md` y no alteran los operadores aquí detallados.

## Fuentes utilizadas

1. L. G. Moré, M. A. Brizuela, H. Legal Ayala, D. P. Pinto-Roa y J. L. Vázquez
   Noguera, *Parameter Tuning of CLAHE Based on Multi-Objective Optimization to
   Achieve Different Contrast Levels in Medical Images*, 2016. En este proyecto
   se identifica como PT-CLAHE.
2. A. J. Nebro et al., *SMPSO: A New PSO-based Metaheuristic for
   Multi-objective Optimization*, IEEE MCDM 2009,
   DOI: <https://doi.org/10.1109/MCDM.2009.4938830>.
3. Implementación y documentación de jMetal:
   <https://github.com/jMetal/jMetal> y
   <https://jmetal.readthedocs.io/en/latest/experimentation.html>.

La implementación es propia y no incorpora jMetal como dependencia. Se
reproducen sus decisiones algorítmicas relevantes para mantener el plugin
liviano y compatible con Fiji/ImageJ y Java 8.

## Adaptaciones deliberadas

La alineación con la referencia no es una copia literal de una versión histórica:

- La constricción usa el valor absoluto del denominador para garantizar `chi>0`.
  Algunas transcripciones de SMPSO muestran el denominador sin valor absoluto,
  lo que produce un coeficiente negativo cuando `phi>4`.
- El torneo de líderes resuelve aleatoriamente los empates de crowding. Esta
  decisión evita introducir una preferencia por la primera posición de la lista.
- `r` es una variable entera del problema Top-Hat. La partícula se desplaza en
  continuo, pero `r` se redondea al evaluar y al almacenar una solución.

Estas decisiones están cubiertas por pruebas y deben mencionarse si se compara
línea por línea con otra versión de jMetal.

## Parámetros adoptados

| Parámetro | Valor implementado | Fuente y justificación |
|---|---:|---|
| `c1` | Uniforme en `[1.5,2.5)` | Tabla 1 de PT-CLAHE y SMPSO de referencia |
| `c2` | Uniforme en `[1.5,2.5)` | Tabla 1 de PT-CLAHE y SMPSO de referencia |
| `r1` | Uniforme en `[0,1)` | Tabla 1 de PT-CLAHE |
| `r2` | Uniforme en `[0,1)` | Tabla 1 de PT-CLAHE |
| `w` | `0.1` constante | Configuración de referencia de jMetal |
| `Vmax_j` | `(xmax_j-xmin_j)/2` | Ecuaciones 7 y 8 de PT-CLAHE |
| Cambio de velocidad en límites | `-1.0` | Configuración SMPSO de referencia |
| Frecuencia de mutación | Índices divisibles por `6` | SMPSO de referencia |
| Probabilidad de mutación | `1/n=1/3` por variable | Tres variables de decisión |
| Índice de distribución | `20` | Mutación polinomial usada con SMPSO en jMetal |

El paper PT-CLAHE usa 100 partículas y 100 iteraciones para su experimento de
CLAHE, pero esos valores no se convierten en constantes del plugin. En esta
tesis, partículas y generaciones son factores experimentales que posteriormente
tomarán los valores solicitados por el tutor.

PT-CLAHE incluye `w` en la ecuación de velocidad, pero no informa su valor
numérico en la Tabla 1. Por esa razón, no debe afirmarse en la defensa que
`w=0.1` proviene de PT-CLAHE: se adopta de la configuración de referencia de
SMPSO en jMetal.

## Ciclo de ejecución corregido

La implementación anterior actualizaba una partícula y el archivo antes de
procesar la siguiente. Ahora se utiliza un ciclo síncrono:

```text
Inicializar posiciones aleatorias
Inicializar velocidades en cero
Evaluar todo el enjambre
Inicializar pbest y archivo externo

Para cada generación:
    calcular crowding del archivo
    seleccionar un líder para cada partícula
    actualizar todas las velocidades
    actualizar todas las posiciones
    reflejar velocidades que alcancen límites
    mutar las partículas 0, 6, 12, ...
    evaluar todo el enjambre
    actualizar el archivo con todo el enjambre
    actualizar todos los pbest

Devolver el archivo no dominado
```

Así, todas las partículas de una generación utilizan el mismo estado histórico
del archivo y el resultado no depende de que una partícula aparezca antes o
después dentro del bucle de actualización.

## Factor de constricción positivo

Se define `phi=c1+c2`. Para `phi<=4`, el factor es uno. Para `phi>4`:

```text
chi = 2 / |2 - phi - sqrt(phi^2 - 4*phi)|
```

Sin el valor absoluto, el denominador es negativo para `phi>4` y la velocidad
termina multiplicada por un número negativo. Eso invierte la dirección resultante
de los términos cognitivo y social. La corrección conserva una magnitud positiva
menor que uno y limita la velocidad sin invertirla artificialmente.

## Selección de líderes

El líder se elige mediante torneo binario:

1. Se toman dos soluciones aleatorias del archivo externo.
2. Se selecciona la de mayor distancia de crowding.
3. Si ambas tienen el mismo crowding, se elige una al azar.

El desempate aleatorio evita que el orden interno de la lista favorezca siempre
la misma solución. No perjudica la reproducibilidad porque utiliza el mismo
generador controlado por la semilla de la corrida.

## Mutación polinomial

En cada generación se seleccionan por índice las partículas `0, 6, 12, ...`.
Para cada una de sus tres variables se aplica mutación con probabilidad `1/3`.

El índice de distribución `eta=20` hace frecuentes los cambios pequeños y deja
una probabilidad menor para desplazamientos grandes. El operador considera la
distancia a los límites y repara el resultado al intervalo permitido. Esto
reemplaza la perturbación anterior, que modificaba todas las partículas cada
seis generaciones con una intensidad decreciente propia del proyecto.

## Límites y memoria personal

Cada componente de la velocidad se restringe a:

```text
[-(xmax-xmin)/2, +(xmax-xmin)/2]
```

Si la nueva posición excede un límite, la posición se fija en ese límite y la
velocidad cambia de signo. Este rebote permite que la partícula vuelva al espacio
factible en el movimiento siguiente.

La solución actual reemplaza a `pbest` siempre que el `pbest` anterior no la
domine. Por tanto, se acepta una solución dominante o incomparable, siguiendo
el comportamiento de referencia y eliminando el desempate aleatorio anterior.

## Pruebas incorporadas

La suite automatizada verifica:

- Entropía, SSIM, dominancia y crowding ya existentes.
- Constricción positiva para `phi>4`.
- Selección de partículas con frecuencia seis.
- Mutación polinomial en ambas direcciones y dentro de límites.
- Preferencia por mayor crowding.
- Empate aleatorio y reproducible de líderes.
- Reflexión de velocidad en los límites.
- Restricción de velocidad a la mitad del rango.
- Frente final factible y no dominado.
- Reproducibilidad completa con la misma semilla.
- Número de evaluaciones igual a `P*(G+1)`.

## Argumentos breves para la defensa

**¿Por qué tomar PT-CLAHE como referencia?** Porque también optimiza parámetros
de procesamiento de imágenes con SMPSO y utiliza entropía y SSIM como objetivos.
Sus parámetros genéricos de movimiento son transferibles; sus límites de CLAHE
no lo son, porque este proyecto optimiza Top-Hat.

**¿Por qué no usar exactamente 100 partículas y 100 generaciones?** Esos fueron
los valores experimentales de PT-CLAHE. En esta tesis su efecto será estudiado
como parte del diseño factorial solicitado por el tutor.

**¿Por qué usar jMetal?** PT-CLAHE remite a jMetal para la implementación de
SMPSO. Adoptar sus operadores proporciona una referencia pública, conocida y
defendible, sin obligar a incorporar todo el framework al plugin.

**¿Las correcciones garantizan mejores imágenes?** No por sí solas. Garantizan
una implementación más consistente y reproducible. La calidad y estabilidad de
los frentes se evalúan mediante corridas independientes, HV e IGD.
