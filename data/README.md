# Datos

Las carpetas `raw` y `processed` son locales y su contenido está excluido de Git.

Estructura sugerida:

```text
data/
├── raw/
│   ├── visible/
│   └── infrared/
└── processed/
    ├── visible/
    └── infrared/
```

No modifique las imágenes originales en `raw`. Documente en `processed` todo
registro, recorte, conversión a escala de grises o normalización.

Los pares utilizados se declaran mediante un CSV en `experiments/manifests`.
Antes de redistribuir datos, verifique licencia, atribución, anonimización y
restricciones institucionales.
