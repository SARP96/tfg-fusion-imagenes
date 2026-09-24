# Registro de decisiones

Utilice este archivo para documentar decisiones aprobadas antes de ejecutar los
experimentos definitivos.

| Fecha | Decisión | Justificación | Aprobada por | Commit |
|---|---|---|---|---|
| 2026-09-23 | Dataset CT-MRI principal, 184 pares | Dataset acordado para la evaluación | Tutoría | Pendiente |
| 2026-09-23 | Imagen base promedio CT-MRI | Tratamiento fijo y simétrico de modalidades | Tutoría | Pendiente |
| 2026-09-23 | SSIM global promedio | Definición acordada con el tutor | Tutoría | Pendiente |
| 2026-09-23 | Entropía normalizada por 8 | Escala interpretable `[0,1]` | Tutoría | Pendiente |
| 2026-09-23 | Pesos `mWTH,mBTH` independientes en `[0.35,2]` | Optimizar por separado detalles claros y oscuros | Tutoría | Pendiente |
| 2026-09-23 | Comparación por generaciones | Diseño factorial solicitado | Tutoría | Pendiente |
| 2026-09-23 | HV e IGD; sin Spacing, MI ni `Q^{AB/F}` | Alcance actual de evaluación | Tutoría | Pendiente |
| 2026-09-23 | Normalización ideal-nadir empírica por par | Escala común sin cambiar SMPSO | Implementación acordada | Pendiente |
| 2026-09-23 | HV con referencia `(1.1,1.1)` | Margen de 10% en espacio normalizado | Implementación acordada | Pendiente |
| 2026-09-23 | `P*` como unión no dominada por par | Referencia común para IGD | Implementación acordada | Pendiente |

Las decisiones técnicas menores pueden registrarse en el pull request. Las que
afecten la validez del experimento deben quedar también en esta tabla.
