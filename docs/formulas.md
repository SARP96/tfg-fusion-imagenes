# Fórmulas y convenciones

## Velocidad

Para cada variable de decisión `j`:

```text
delta_j = (x_j_max - x_j_min) / 2
-delta_j <= v_j <= delta_j
```

- `x_j_min` y `x_j_max`: límites de la variable.
- `delta_j`: magnitud máxima permitida para la velocidad.
- `v_j`: desplazamiento de la partícula en esa dimensión.

La versión actual obtiene aleatoriamente `c1` y `c2` en `[1.5, 2.5)` y `w` en
`[0.1, 0.5)`. Estos valores deben confirmarse en el protocolo definitivo.

## Dominancia

Una solución `a` domina a `b` cuando no es peor en ningún objetivo y mejora al
menos uno. Como entropía y SSIM se maximizan:

```text
a domina b si:
  a.entropía >= b.entropía
  a.ssim     >= b.ssim
y al menos una desigualdad es estricta.
```

## Indicadores planificados

- `HV`: hipervolumen; mayor es mejor.
- `IGD`: distancia generacional invertida; menor es mejor.
- `Spacing`: uniformidad de las distancias vecinas; menor es mejor.
- `GD`: distancia desde el frente obtenido al de referencia; menor es mejor.

HV requiere un punto de referencia fijo. IGD y GD requieren un frente de
referencia común. Las distancias deben calcularse con objetivos normalizados.
