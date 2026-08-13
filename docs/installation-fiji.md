# Instalación en Fiji

## Con Maven

```bash
mvn clean verify
export FIJI_DIR="/ruta/a/Fiji.app"
./scripts/install-fiji.sh
```

## Sin Maven

El script `build.sh` intenta localizar el JDK y `ij-*.jar` dentro de Fiji:

```bash
export FIJI_DIR="/ruta/a/Fiji.app"
./build.sh
./scripts/install-fiji.sh
```

Si la detección automática falla:

```bash
export JAVA_HOME_FIJI="/ruta/al/jdk/Contents/Home"
export IJ_JAR="/ruta/a/Fiji.app/jars/ij-version.jar"
./build.sh
```

Después de instalar el JAR, reinicie Fiji. El comando debe aparecer en:

```text
Plugins > SMPSO TopHat Fusion > SMPSO Top-Hat Fusion
```

Para una prueba mínima, abra dos imágenes registradas del mismo tamaño y ejecute
una configuración pequeña. No use esa prueba como resultado experimental.
