# Auditoría (Features + Estilos) — EnterpriseIQ

Fecha: 2026-04-14

## Resumen ejecutivo

La base del producto ya está bien encaminada para una consultoría de pymes: **multiempresa + roles + imports CSV/XLSX + dashboards + entregables (HTML/PDF) + Universal (análisis tabular)**. La propuesta de valor “automatizar a quienes pican Excel” está parcialmente resuelta en **ingesta**, **normalización**, **KPIs**, **drill‑down** y **reporting**, pero para llegar al nivel “Excel-killer” faltan tres cosas: (1) **calidad de dato y confianza** (reglas, reconciliación, trazabilidad), (2) **automatización operativa** (pipelines y tareas recurrentes) y (3) **narrativa / explicación** (gráficos que se explican solos, insights accionables con evidencias).

En estilo/UX hay una identidad visual sólida (dark, glass, tokens, componentes), pero se recomienda consolidar un **sistema tipográfico** y un **layout responsive** más consistente, y reforzar **accesibilidad** (tamaños mínimos, contraste, focus y motion).

---

## 1) Auditoría de features (producto)

### 1.1. Roles, permisos y planes
- Roles: **ADMIN / CONSULTOR / CLIENTE** con navegación y acceso condicionados.
- Plan por empresa: **BRONZE / GOLD / PLATINUM** con gating visible en UI (pricing + mensajes).

**Riesgos / mejoras**
- Gating correcto, pero conviene reforzar “por qué no lo veo” con CTAs coherentes (ej.: ver demo/upgrade) y ejemplos de impacto (qué ahorra).
- “Cliente” debería tener una experiencia aún más guiada (menos navegación, más “siguiente paso”).

### 1.2. Onboarding y activación
- Hay un “Empezar (2 minutos)” que guía a importar y ver dashboards.
- Plantillas descargables para distintos módulos.

**Mejoras recomendadas**
- Checklist persistente por empresa (estado: importado, validado, entregable generado, enviado).
- “Modo asistido” para el primer import: validar columnas + previsualización + reglas mínimas.
- “Demo dataset” (cargar ejemplo con un click) para que el producto se entienda sin datos reales.

### 1.3. Ingesta y gestión de imports (CSV/XLSX)
Lo que ya existe:
- Upload “smart” (auto/transactions/universal) y **batch** (inferencia de periodo por filename).
- Preview/validación y “quality” del import (suficiente para P0).
- Modo guiado para XLSX (hoja + fila cabecera).
- Reintentos de imports.

Gaps para “automatizar Excel”
- **Reglas de calidad configurables**: tipos, nulos, rangos, duplicados, coherencia por periodo.
- **Reconciliación**: saldo inicial/final, conteo esperado, checksums, diferencias vs mes anterior.
- **Mapeo persistente avanzado**: normalización de categorías (proveedores, contrapartidas, cuentas).
- **Lineage**: de qué fichero proviene cada métrica/insight (auditable).

### 1.4. Caja / KPIs / drill‑down de transacciones
Lo que ya existe:
- KPIs mensuales (in/out/net/saldo), evolución y lectura rápida.
- Drill‑down de transacciones con filtros, analítica (top contrapartes, categorías, anomalías) y export (según plan).
- Export Power BI (ZIP) como puente a analítica externa (PLATINUM).

Mejoras recomendadas (alto impacto)
- **Etiquetado y reglas** (categorías/contrapartidas) con “aprender” (semi‑automático).
- **Explainability**: “por qué” el neto cambia (drivers: top 5 transacciones, top 3 categorías).
- **Acciones**: crear tareas (“llamar cliente X”, “reclamar factura”, “renegociar proveedor”) desde el insight.
- **Comparativas**: vs presupuesto, vs mismo mes año anterior, vs media 3/6 meses.

### 1.5. Universal (análisis tabular)
Lo que ya existe:
- Subir cualquier tabla, resumen + insights, builder de gráficos (varios tipos) y guardado de dashboards.
- Listado de dashboards (“Mis dashboards”) con enlace copiable.
- Sugerencias automáticas y export de problemas (CSV).
- Assistant en PLATINUM (orientado a reglas/plan 30/60/90).

Gaps para “consultoría productizada”
- **Plantillas de análisis** (por vertical: retail, servicios, restauración) con KPIs predefinidos.
- **Biblioteca de vistas**: versionado, permisos (solo consultor vs compartible a cliente), caducidad.
- **Narrativa**: cada gráfico debería tener “qué veo / por qué importa / qué haría”.
- **Guardrails de sampling**: límites de puntos, agregación automática y “error budgets” visuales.

### 1.6. Presupuesto
Lo que ya existe:
- Normalización “long”, insights y export (CSV/PDF), lectura de drivers.

Mejoras recomendadas
- Import dedicado de presupuesto con mapeo guiado (menos fricción que pasar por Universal).
- Comparativa **real vs presupuesto** en Caja y en Presupuesto (coste de oportunidad).

### 1.7. Entregables (reporting HTML/PDF)
Lo que ya existe:
- Generación de reportes, vista HTML en iframe, descarga a PDF, histórico por periodo.

Mejoras recomendadas
- **Plantillas por tipo de cliente** (light/exec/operativo) y “branding” por consultoría.
- “Paquete de entrega”: PDF + CSVs + imágenes de gráficas + resumen ejecutivo en 1 página.
- Programación (mensual) + notificación + “aprobación” antes de enviar.

### 1.8. Alertas
Lo que ya existe:
- Alertas por periodo y visualización en módulos (p. ej., Universal).

Mejoras recomendadas
- Alertas “accionables”: botón “crear tarea” + asignación + seguimiento.
- Prioridad y fatiga: deduplicación, agrupación por causa raíz.

### 1.9. Automatización (ops consultoría)
Lo que ya existe:
- Página/módulo de automatización y jobs (según backend).

Mejoras recomendadas (clave para Excel automation)
- Automatizaciones “no-code” por empresa:
  - “Cada mes, cuando suba el CSV, recalcula KPIs + genera PDF + comparte link”.
  - “Si saldo < 0 o runway < X, crea alerta + email”.
  - “Si faltan columnas obligatorias, bloquear y guiar”.

---

## 2) Auditoría de estilos y UX (frontend)

### 2.1. Sistema visual (estado actual)
Fortalezas:
- Identidad coherente dark (glass + gradients), tokens CSS y componentes UI.
- Estados: skeletons, toasts, empty/error states, focus visible y prefers-reduced-motion (en animaciones recientes).
- Charts con “theme” base consistente.

Debilidades / deuda
- `styles.css` concentra casi todo: difícil de mantener, propenso a inconsistencias.
- Falta una **escala tipográfica** explícita (variables tipo `--fs-...`), y mínimos por accesibilidad.
- Varias zonas con “texto auxiliar” muy dependiente de `.upload-hint`, que se usa para cosas distintas (hint, metadata, help text).

### 2.2. Tipografía y legibilidad
Recomendaciones:
- Definir escala en `:root` (ej.: 12/14/16/18/22/28) y mapear:
  - metadata/hints: >= 12px con line-height >= 1.4
  - navegación: >= 13px
  - tablas: 14px body / 12px headers ok si contraste alto
- Usar pesos consistentemente (600/700/800) y limitar “uppercase” para no fatigar.

### 2.3. Layout & responsive
Recomendaciones:
- Establecer breakpoints principales (ej.: 1440 / 1280 / 1024 / 768).
- Side nav colapsable en pantallas medianas, con “drawer”.
- Evitar que la topbar “salte” por wrap: mover acciones secundarias a menú.

### 2.4. Animación y microinteracciones
Estado:
- Transición de ruta y reveals (buen efecto “wow”).

Recomendaciones:
- Respetar `prefers-reduced-motion` (ya se hace) y mantener duraciones coherentes.
- Evitar que animaciones oculten información crítica (p. ej. tablas largas).

### 2.5. Accesibilidad
Checklist recomendado:
- Contraste (especialmente `rgba(...)` en texto “soft”).
- Tamaños táctiles (>= 40px alto efectivo en botones/inputs).
- Lectura con teclado: orden de tab, `:focus-visible`, `aria-label` en botones icon-only.
- Tablas: encabezados y scroll horizontal con indicadores.

### 2.6. Consistencia de componentes
Recomendaciones:
- Consolidar variantes (Card soft/default, Button variants) y documentarlas.
- Evitar estilos ad-hoc en páginas; preferir componentes y utilities.
- Añadir “Design Tokens” para:
  - bordes (`--border-...`), sombras (`--shadow-...`), radios (`--radius-...`) y tipografías.

---

## 3) Prioridades (backlog sugerido)

### P0 (1–2 semanas) — confianza y uso diario
- Reglas mínimas de **calidad de dato** por import (obligatorias + coherencia de fechas/importes).
- Explicaciones “driver-based” en Caja: top cambios vs mes anterior (acciones recomendadas).
- Plantillas y onboarding reforzados: demo dataset + checklist persistente.
- Tipografía: escala + mínimos de legibilidad (hints, nav, tablas).

### P1 (3–6 semanas) — automatización real de consultoría
- Automatizaciones por empresa (pipelines): “import → recalcular → informe → compartir”.
- Rules engine para categorías/contrapartidas (y reaprovechar en transacciones + universal).
- Entregables por plantilla con branding y paquete de entrega.
- Biblioteca de dashboards Universal con permisos y versionado.

### P2 (6–12 semanas) — producto escalable y diferencial
- Integraciones (bancos, Google Sheets/Drive, S3) y conectores recurrentes.
- Colaboración (tareas, comentarios, aprobaciones) y auditoría de cambios.
- Benchmarking por sector + recomendaciones basadas en cohortes (con guardrails).

---

## 4) Métricas (para demostrar valor a pymes)
- Tiempo medio de “import → insight útil” (minutos).
- % de imports con errores (y tipos de error).
- # alertas accionables creadas vs ignoradas (fatiga).
- Tiempo de generación de entregable y tasa de reutilización de plantillas.
- Ahorro estimado vs “Excel manual” (inputs: horas/mes).

