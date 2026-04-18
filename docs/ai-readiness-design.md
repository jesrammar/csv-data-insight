# Diseño “AI-ready”: trazabilidad, coste y privacidad

Este documento define el diseño mínimo para integrar IA generativa **sin deuda**.

## Objetivo

Permitir añadir un proveedor LLM (OpenAI/otro) manteniendo:
- auditoría completa,
- control de coste,
- minimización de datos y cumplimiento.

## Arquitectura recomendada

### 1) Abstracción de proveedor (no acoplarse al LLM)

- `AiProvider` (interfaz): `chatCompletion(request, context) -> response`
- Implementación:
  - `RulesProvider` (default, sin red)
  - `LlmProvider` (futuro, opt-in)

El controlador no debe saber “cómo” se genera la respuesta, solo qué feature la pidió.

### 2) Pipeline de redacción (privacy by design)

Antes de enviar texto a un LLM:
- `RedactionPolicy` por feature (chat vs informe vs resumen).
- Redactar:
  - emails,
  - IBAN/cuentas,
  - identificadores fiscales si no son necesarios,
  - nombres de personas (si no aportan valor).
- Sustituir por placeholders (`[EMAIL]`, `[IBAN]`, …) y guardar un “redaction summary”.

Nunca enviar:
- CSV completo,
- transacciones raw,
- campos “description” si pueden contener PII, salvo necesidad clara.

Preferir:
- KPIs mensuales,
- top N categorías,
- cuantiles,
- conteos y tasas,
- insights ya calculados por el backend.

### 3) Registro de llamadas (traceability)

Registrar por llamada:
- `trace_id` (correlación con request + UI),
- `company_id`, `user_id`,
- `feature` (`assistant_chat`, `assistant_report`, …),
- `provider`, `model`,
- `input_chars`, `output_chars` (o tokens si disponible),
- `duration_ms`,
- `status` (`ok`, `timeout`, `budget_exceeded`, `redacted`, `error`),
- `prompt_version`,
- `prompt_hash` (SHA-256 del prompt ya redactado),
- `response_hash`,
- `cost_estimate` (si el proveedor lo da).

Recomendación: **guardar hashes** y no prompts completos salvo depuración con opt-in.

### 4) Presupuesto y límites (cost control)

Políticas mínimas:
- `monthly_budget_eur` por empresa
- `max_cost_per_call_eur` por feature
- `max_calls_per_day` por empresa
- “degradación” a `RULES` si:
  - no hay presupuesto,
  - hay error del proveedor,
  - la redacción deja el input vacío,
  - timeout.

### 5) Consentimiento / UI

Si se activa `AI`:
- aviso explícito: qué se envía y qué NO,
- configuración por empresa (opt-in),
- toggle por feature (chat, informe, etc.),
- enlace a política de datos.

## Checklist de implementación (mínimo)

- [ ] Añadir `AiProvider` + `RedactionPolicy`.
- [ ] Añadir tabla/log para llamadas a IA (hashes + métricas).
- [ ] Añadir presupuesto por empresa y “fail closed”.
- [ ] Añadir toggles por empresa/feature (opt-in).
- [ ] Añadir tests de redacción + budget.

