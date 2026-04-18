# Assistant (reglas) vs IA generativa

## Qué es “Assistant” hoy (estado actual)

En EnterpriseIQ, **Assistant** (a día de hoy) es un **motor de reglas/heurísticas**:

- Toma señales **agregadas** del producto (KPIs mensuales, insights calculados, alertas, categorías top, etc.).
- Aplica **plantillas + reglas** para generar:
  - un plan 30/60/90,
  - preguntas para afinar,
  - acciones con evidencias.
- Es **determinista** y **auditable**: mismo input → salida muy similar.

Lo importante: **no es IA generativa** y **no llama a ningún modelo externo**.

## Qué NO es

- No “razona” como un LLM.
- No aprende de tus datos.
- No tiene memoria oculta (solo usa datos que ya existen en el backend).
- No envía datasets/CSV a terceros.

## Por qué conviene llamarlo “Assistant (reglas)”

Porque evita confusiones con:
- promesas de “IA”,
- expectativas de lenguaje natural ilimitado,
- implicaciones de privacidad/coste (típicas de LLMs).

## Si queréis IA real (LLM): requisitos desde el día 0

Antes de integrar un LLM, definid estas tres cosas **como producto**:

### 1) Trazabilidad

Cada llamada debe tener un `trace_id` y quedar registrada con:
- `company_id`, `user_id`, endpoint/feature,
- modelo/proveedor,
- tiempo de respuesta, tamaño de entrada/salida,
- **por qué** se llamó (objetivo/acción),
- resultado (ok/error/timeout),
- versión de prompt / reglas.

### 2) Coste (control de gasto)

- Presupuesto por empresa (mensual) y por entorno (dev/stage/prod).
- Límite de tokens o euros por feature (chat, generación de informe, resumen, etc.).
- Mecanismo de “fail closed” si se supera presupuesto (degradar a reglas).
- Métricas: coste por compañía/periodo/feature.

### 3) Privacidad (minimización de datos)

Principios recomendados:
- **No enviar CSV crudo** ni PII salvo necesidad explícita.
- Enviar solo **features agregadas** (KPIs, top categorías, cuantiles, recuentos).
- Redacción automática: emails, IBAN, CIF/NIF si no aporta valor.
- Retención mínima de logs; y si guardas prompts/respuestas, guardarlos **redactados** o con hash.

## Recomendación práctica

Mantener 2 modos explícitos:

- `RULES` (por defecto): rápido, estable, auditable, sin dependencia externa.
- `AI` (opt-in): solo cuando aporta valor claro y con trazabilidad/coste/privacidad implementados.

