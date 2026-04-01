# Known issues (TFG)

## NPM audit (frontend)

Estado a **2026-03-31**:

- Quedan **2 vulnerabilidades moderadas** en `vite`/`esbuild` que requieren actualizar a `vite@6` (breaking) para quedar en verde con `npm audit`.
- Impacto: afecta al **servidor de desarrollo** de Vite (no al bundle de producción), por lo que para el TFG se deja como “known issue”.

Decisión:

- Se ha aplicado fix **sin riesgo** y se ha actualizado `jspdf` para eliminar la vulnerabilidad **crítica**.
- La migración a `vite@6` se difiere para no introducir cambios breaking fuera del alcance del TFG.

