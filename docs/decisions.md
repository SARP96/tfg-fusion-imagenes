# Registro de decisiones

Utilice este archivo para documentar decisiones aprobadas antes de ejecutar los
experimentos definitivos.

| Fecha | Decisión | Justificación | Aprobada por | Commit |
|---|---|---|---|---|
| 2026-09-23 | Dataset CT-MRI principal, 184 pares | Dataset acordado para la evaluación | Tutoría | e767607 |
| 2026-09-23 | Imagen base promedio CT-MRI | Tratamiento fijo y simétrico de modalidades | Tutoría | e767607 |
| 2026-09-23 | SSIM global promedio | Definición acordada con el tutor | Tutoría | e767607 |
| 2026-09-23 | Entropía normalizada por 8 | Escala interpretable `[0,1]` | Tutoría | e767607 |
| 2026-09-23 | Pesos `mWTH,mBTH` independientes en `[0.35,2]` | Optimizar por separado detalles claros y oscuros | Tutoría | e767607 |
| 2026-09-23 | Comparación por generaciones | Diseño factorial solicitado | Tutoría | e767607 |
| 2026-09-23 | HV e IGD; sin Spacing, MI ni `Q^{AB/F}` | Alcance actual de evaluación | Tutoría | e767607 |
| 2026-09-23 | Normalización ideal-nadir empírica por par | Escala común sin cambiar SMPSO | Implementación acordada | e767607 |
| 2026-09-23 | HV con referencia `(1.1,1.1)` | Margen de 10% en espacio normalizado | Implementación acordada | e767607 |
| 2026-09-23 | `P*` como unión no dominada por par | Referencia común para IGD | Implementación acordada | e767607 |
| 2026-10-11 | Alcance exclusivo CT-MRI y título de tesis actualizado | La evaluación formal se centrará en tomografía computarizada y resonancia magnética | Tutoría | Pendiente de commit |

Las decisiones técnicas menores pueden registrarse en el pull request. Las que
afecten la validez del experimento deben quedar también en esta tabla.
