# Fórmulas y convenciones

## Fusión Top-Hat

```text
Ib   = (ICT + IMRI) / 2
Ifus = Ib + mWTH * max(WTH_CT, WTH_MRI)
          - mBTH * max(BTH_CT, BTH_MRI)
```

`r` es el radio entero del elemento estructurante. `mWTH` amplifica los detalles
claros y `mBTH` los oscuros; ambos pesos se optimizan en `[0.35,2]`.

## Objetivos

```text
f1 = H(Ifus) / 8
f2 = (SSIM_global(Ifus, CT) + SSIM_global(Ifus, MRI)) / 2
```

Los dos objetivos se maximizan. `H` es la entropía de Shannon calculada sobre
256 niveles; dividir por `log2(256)=8` la normaliza a `[0,1]`.

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
- `c1`, `c2`: aceleraciones muestreadas en `[1.5,2.5)`.
- `r1`, `r2`: números uniformes en `[0,1)`.
- `w`: peso de inercia constante, igual a `0.1`.
- `chi`: factor de constricción positivo.
- `delta_j`: magnitud máxima de la velocidad en la dimensión `j`.

## Factor de constricción

Con `phi = c1 + c2`:

```text
chi = 1                                      si phi <= 4
chi = 2 / |2 - phi - sqrt(phi^2 - 4*phi)|   si phi > 4
```

El valor absoluto evita una constricción negativa para `phi>4`.

## Mutación polinomial

Se aplica a las partículas con índices `0,6,12,...` en cada generación. Cada
variable se muta con probabilidad:

```text
p_m = 1 / número_de_variables = 1 / 3
```

El índice de distribución es `eta_m=20`. La operación respeta los límites de
`r`, `mWTH` y `mBTH`; `r` se redondea solamente al evaluar la fusión.

## Dominancia

Una solución `a` domina a `b` cuando no es peor en ningún objetivo y mejora al
menos uno. Como ambos objetivos se maximizan:

```text
a domina b si a.entropía >= b.entropía y a.ssim >= b.ssim,
con al menos una desigualdad estricta.
```

## Normalización de indicadores

Para cada par CT-MRI se reúnen los frentes de todas las configuraciones y
corridas. Para cada objetivo de maximización `f_j`:

```text
ideal_j = max(f_j)
nadir_j = min(f_j)
z_j     = (ideal_j - f_j) / (ideal_j - nadir_j)
```

`z_j=0` representa el mejor valor observado y `z_j=1` el peor. Si el rango es
cero, la coordenada se fija en cero. La misma escala se usa en todo el par.

## Indicadores

- `HV`: hipervolumen bidimensional exacto; mayor es mejor. Usa `(1.1,1.1)` como
  referencia fija en el espacio normalizado de minimización.
- `IGD`: promedio de la distancia euclidiana desde cada punto de `P*` al punto
  más cercano del frente evaluado; menor es mejor.

`P*` es la unión no dominada de todos los frentes del mismo par. No se calculan
Spacing, GD, MI ni `Q^{AB/F}`.
