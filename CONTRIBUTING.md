# Colaboración

## Flujo de trabajo

1. Crear una rama desde `main` con un nombre descriptivo.
2. Realizar cambios pequeños y acompañarlos con pruebas o documentación.
3. Ejecutar `mvn clean verify` antes de solicitar revisión.
4. Abrir un pull request y explicar el efecto metodológico del cambio.
5. No incorporar resultados formales si existen cambios sin revisar en métricas,
   semillas, normalización o parámetros de SMPSO.

Ejemplos de ramas:

```text
feature/local-ssim
feature/batch-execution
feature/performance-indicators
fix/velocity-limit
docs/experimental-protocol
```

Ejemplos de commits:

```text
feat: add local SSIM objective
test: verify deterministic run with fixed seed
fix: clamp velocity for each decision variable
docs: define empirical reference Pareto front
```

## Revisión científica

Todo cambio que afecte la función de fusión, los objetivos, los rangos, el
presupuesto de evaluaciones o el análisis estadístico debe actualizar también la
documentación metodológica. Los experimentos deben conservar el hash del commit
que produjo sus resultados.

## Datos

No se deben subir datasets, imágenes clínicas, credenciales, rutas personales ni
resultados masivos. Use manifiestos con rutas relativas y respete las licencias.
