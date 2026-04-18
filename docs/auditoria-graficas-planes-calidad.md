# Auditoría — Gráficas por plan + Calidad de dato

Fecha: 2026-04-15

## Objetivo
Revisar qué **gráficas** y **lecturas** recibe cada plan (BRONZE/GOLD/PLATINUM) y si la app transmite **confianza de dato** (calidad, problemas, trazabilidad), con recomendaciones para que “se entienda solo” en consultoría.

---

## 1) Qué ve cada plan (UX + gráficas)

### BRONZE (mínimo viable de lectura)
**Lo que debe conseguir**
- Entender “estado” (semáforo) y 2–3 métricas clave sin hablar de técnica.

**Gráficas/lecturas recomendadas**
- Caja: 1 gráfico principal + 4 KPIs + narrativa “Qué veo / Por qué / Qué haría”.
- Universal: solo “resumen de dataset” + 1 gráfico simple (series temporal o bar top‑N) con nombres humanizados.

**Riesgo actual**
- En Universal es fácil acabar con gráficos “técnicos” (heatmap/scatter) si el dataset no es obvio.

**Mejoras BRONZE**
- “Modo simple” (toggle): ocultar builders complejos y limitar a 2 tipos.
- Etiquetas y unidades obligatorias: “€” / “%” / “unidades”.
- Glosario inline: “qué es neto/saldo/runway”.

### GOLD (operativa consultoría sin export)
**Lo que debe conseguir**
- Diagnóstico táctico: drill‑down y comparativas, suficiente para justificar acciones.

**Gráficas/lecturas recomendadas**
- Caja: además del principal, añadir “drivers” (top categorías/contrapartes) *al menos en lectura*.
- Universal: correlaciones y sugerencias, pero con narrativa y “por qué este gráfico”.
- Tribunal / Presupuesto: gráficos con outliers y explicación.

**Riesgo actual**
- El salto BRONZE→GOLD se percibe más en “módulos disponibles” que en “calidad de explicación”.

**Mejoras GOLD**
- Drivers “lite” (sin export): top‑3 categorías/contrapartes del periodo, con CTA a PLATINUM para export.
- Comparativa: vs media 3m/6m y vs mes anterior (en narrativa, no solo en tooltip).

### PLATINUM (profundidad + entregable)
**Lo que debe conseguir**
- “Excel killer”: explicación + evidencia + export + repetibilidad.

**Gráficas/lecturas recomendadas**
- Caja: drivers reales por periodo (categorías/contrapartes) y anomalías accionables.
- Universal: dashboards guardables/compartibles con snapshots y explicación.
- Entregables: PDF con narrativa y anexos.

**Estado actual (frontend)**
- Narrativa interactiva por hover (gráfica → explicación cambia por periodo).
- Drivers reales en Caja cuando hay `transactions-analytics` por periodo (PLATINUM).

---

## 2) Auditoría por módulo (qué confunde y cómo corregir)

### 2.1. Caja (Dashboard)
**Qué suele confundir**
- “Salidas” negativas, neto vs saldo, y qué acción tomar.

**Qué ya ayuda**
- Narrativa “Qué veo / Por qué / Qué haría”.
- En PLATINUM: drivers por periodo (top contrapartes/categorías).

**Mejoras**
- Forzar consistencia de signo: mostrar “Salidas” como positivo en texto (aunque el gráfico use negativos).
- CTA contextual: “Ver top transacciones del periodo” (scroll a sección de transacciones ya filtrada).

### 2.2. Transacciones (drill‑down + analytics)
**Qué suele confundir**
- Agregados (top) sin contexto (¿por qué esa contrapartida es relevante?).

**Mejoras**
- En narrativa: “esto explica X% del neto” (share).
- Drivers listados como chips clicables que aplican filtros (contrapartida/categoría).

### 2.3. Universal (cualquier tabla)
**Qué suele confundir (tu caso típico)**
- Heatmap/scatter sin “por qué”; nombres de ejes genéricos (`x`, `y`, `value`) y falta de unidades.
- CSV de problemas (“universal-problemas-YYYY-MM-DD.csv”) sin guía de acción.

**Mejoras**
- Cada gráfico debe traer 3 cosas obligatorias:
  1) **Qué mide** (unidad y columna origen)
  2) **Qué patrón busca** (tendencia, concentración, outlier)
  3) **Qué hago** (acción de consultoría)
- Etiquetas de columnas: si el usuario no eligió `x/y/value`, pedirlo (o inferir y confirmar).
- “Problemas” con severidad + fix sugerido:
  - Ej.: “fecha inválida” → “normaliza a YYYY-MM-DD”; “nulos” → “rellenar o excluir”.

### 2.4. Presupuesto
**Qué suele confundir**
- El “long” y drivers si no se entiende el origen.

**Mejoras**
- Mostrar “de qué filas/columnas sale esto” (lineage ligero).
- Comparativa “real vs presupuesto” (cuando haya transacciones).

### 2.5. Entregables (Reports)
**Qué suele confundir**
- Qué incluye el PDF, y si está “listo para cliente”.

**Mejoras**
- Checklist: “datos OK / sin problemas críticos / narrativa OK / export listo”.
- Generación por plantilla (exec 1 página + anexo).

---

## 3) Calidad de dato (confianza)

### 3.1. Qué existe
- Imports: preview + calidad (errores de formato, columnas, etc.).
- Universal: export de “problemas” (CSV) con filas/razón.

### 3.2. Gaps típicos de confianza
- No se ve claramente: “este KPI viene del fichero X” + “qué transformaciones se hicieron”.
- Falta un “score” de calidad por dataset/periodo y un semáforo (OK / revisar / bloquear).

### 3.3. Recomendaciones P0 de calidad
- Score simple por dataset:
  - % fechas inválidas, % nulos en columnas clave, outliers (p99), duplicados.
- Semáforo de publicación:
  - Verde: se permite generar entregable
  - Amarillo: se avisa (requiere revisión)
  - Rojo: bloquea informe (si faltan columnas obligatorias o hay incoherencia fuerte)
- “Fix guidance” por problema (texto accionable).

---

## 4) Acciones inmediatas (para que se entienda siempre)

### P0 (rápido, 1–3 días)
- Universal: añadir narrativa obligatoria debajo de cada gráfico del builder (igual que Caja/KPI).
- Universal: sustituir `x/y/value` por nombres reales de columnas y unidad (si aplica).
- Universal: CTA “¿No entiendes este gráfico?” → “Cambiar tipo” + “Mostrar explicación”.

### P1 (1–2 semanas)
- Drivers clicables (chips) que filtran transacciones por contrapartida/categoría.
- “Dataset quality card” en Universal y Caja (score + top 3 issues + link a CSV).

