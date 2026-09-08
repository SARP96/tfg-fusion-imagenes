# Fórmulas y convenciones

## Velocidad

Para cada variable de decisión `j`:

```text
v_i,j(t+1) = chi * [w*v_i,j(t)
                    + c1*r1*(pbest_i,j - x_i,j(t))
                    + c2*r2*(leader_i,j - x_i,j(t))]

delta_j = (x_j_max - x_j_min) / 2
-delta_j <= v_j <= delta_j
```

- `x_i,j`: posición de la partícula `i` en la variable `j`.
- `pbest_i,j`: mejor posición personal recordada por la partícula.
- `leader_i,j`: líder seleccionado del archivo externo.
- `c1`, `c2`: aceleraciones cognitiva y social, muestreadas en `[1.5, 2.5)`.
- `r1`, `r2`: números uniformes independientes en `[0, 1)`.
- `w`: peso de inercia constante, igual a `0.1`.
- `chi`: factor de constricción positivo.
- `x_j_min` y `x_j_max`: límites de la variable.
- `delta_j`: magnitud máxima permitida para la velocidad.
- `v_j`: desplazamiento de la partícula en esa dimensión.

PT-CLAHE respalda los rangos de `c1`, `c2`, `r1`, `r2` y la definición de
`delta_j`. El valor constante `w=0.1` corresponde a la configuración de
referencia de SMPSO utilizada en jMetal; PT-CLAHE menciona `w`, pero no publica
un valor numérico para ese parámetro.

## Factor de constricción

Con `phi = c1 + c2`:

```text
chi = 1                                      si phi <= 4
chi = 2 / |2 - phi - sqrt(phi^2 - 4*phi)|   si phi > 4
```

El valor absoluto evita que `chi` sea negativo cuando `phi > 4`. De esta forma,
la constricción reduce la magnitud de la velocidad sin invertir artificialmente
la dirección calculada por los términos cognitivo y social.

## Mutación polinomial

Se aplica a las partículas con índices `0, 6, 12, ...` en cada generación. Cada
variable se muta con probabilidad:

```text
p_m = 1 / número_de_variables = 1 / 2 = 0.5
```

El índice de distribución es `eta_m = 20`. La operación respeta los límites de
`r` y `m`; `r` permanece continuo durante el movimiento y se redondea únicamente
al evaluar la transformada Top-Hat.

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
