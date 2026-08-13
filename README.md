# SMPSO Top-Hat Image Fusion

Plugin experimental para Fiji/ImageJ destinado a optimizar mediante SMPSO los
parámetros de la transformada Top-Hat empleados en la fusión de imágenes
visibles e infrarrojas registradas.

## Formulación actual

Las variables de decisión son el radio entero `r` del elemento estructurante y
el peso real de contraste `m`. La imagen fusionada se calcula como:

```text
Ifus = Ib + m * max(WTH_IR, WTH_VIS) - m * max(BTH_IR, BTH_VIS)
```

Los dos objetivos se maximizan simultáneamente:

```text
f1 = Entropía(Ifus)
f2 = (SSIM(Ifus, VIS) + SSIM(Ifus, IR)) / 2
```

## Estado

La versión `0.1.0-SNAPSHOT` incluye:

- Ejecución interactiva como plugin de Fiji/ImageJ.
- Fusión Top-Hat con imagen base promedio, visible o infrarroja.
- Optimización aproximada mediante una variante de SMPSO.
- Archivo externo no dominado y distancia de crowding.
- Entropía de Shannon y SSIM global.
- Semilla configurable para reproducir una corrida.
- Tabla, gráfico y reconstrucción opcional de soluciones Pareto.
- Pruebas unitarias iniciales de métricas y dominancia.

Todavía no están implementados el SSIM local, la ejecución batch de 30 corridas,
el frente consolidado ni los indicadores HV, IGD y Spacing. Esos elementos se
encuentran documentados como trabajo planificado y no deben presentarse como
funcionalidades terminadas.

## Requisitos

- Fiji/ImageJ instalado para utilizar el plugin.
- JDK 8 o posterior.
- Maven 3, recomendado para compilar y ejecutar las pruebas.

## Compilación

Con Maven:

```bash
mvn clean verify
```

El JAR se genera en:

```text
target/SMPSO_TopHat_Fusion.jar
```

Sin Maven, se puede utilizar el JDK incluido con Fiji:

```bash
export FIJI_DIR="/ruta/a/Fiji.app"
./build.sh
```

## Instalación en Fiji

Después de compilar:

```bash
export FIJI_DIR="/ruta/a/Fiji.app"
./scripts/install-fiji.sh
```

Reinicie Fiji y seleccione:

```text
Plugins > SMPSO TopHat Fusion > SMPSO Top-Hat Fusion
```

Abra previamente una imagen visible y su correspondiente imagen infrarroja.
Ambas deben estar registradas y tener el mismo ancho y alto.

## Organización del proyecto

- `src/main/java`: código fuente del plugin.
- `src/test/java`: pruebas automatizadas.
- `experiments/configs`: configuraciones versionadas de experimentos.
- `experiments/manifests`: descripción de los pares, sin incluir el dataset.
- `experiments/seeds`: semillas planificadas o utilizadas.
- `docs`: metodología, arquitectura e instalación detallada.
- `data`: instrucciones para preparar datos locales ignorados por Git.
- `results`: resúmenes y figuras publicables; las corridas masivas se ignoran.

## Reproducibilidad

Cada corrida formal deberá registrar como mínimo el par, la configuración, el
número de corrida, la semilla, el tiempo, las evaluaciones y el identificador del
commit de Git. Consulte `docs/experimental-design.md`.

## Datos y privacidad

Este repositorio no redistribuye datasets. Antes de publicar imágenes se deben
verificar la licencia, los términos de uso, la anonimización y las restricciones
institucionales. Consulte `data/README.md`.

## Licencia y cita

La licencia definitiva debe acordarse con los autores, tutores y la institución.
Mientras eso no ocurra, no se concede permiso de redistribución. Los metadatos
provisionales de cita están en `CITATION.cff`.
