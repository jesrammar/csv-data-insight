# Capacidades por roles y planes (EnterpriseIQ)

Este documento resume qué puede hacer cada **rol** y qué se habilita por **plan** (por empresa gestionada).

Documento relacionado:

- Ver [adr-002-pricing-por-madurez-operativa.md](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/docs/adr-002-pricing-por-madurez-operativa.md) para la decisión de pricing orientado a madurez operativa.
- Ver [auditoria-modelo-consultora.md](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/docs/auditoria-modelo-consultora.md) para la lectura conceptual de “consultora dueña, consultor operador y cliente final de lectura”.

## Roles (permisos funcionales)

| Rol | Objetivo | Puede ver | Puede operar |
|---|---|---|---|
| **CLIENTE** | Consultar y decidir con contexto | Resumen, Caja, Alertas, Informes, Ayuda | No sube datos ni ejecuta automatizaciones |
| **CONSULTOR** | Operar una cartera de empresas | Todo lo anterior + módulos de consultoría (según plan) | Importaciones, Tribunal, Universal, Automatización, Recomendaciones |
| **ADMIN** | Gobernar la operación de la consultora | Todo | Gestión de empresas gestionadas + permisos totales |

Notas:
- El **plan** aplica por **empresa gestionada**. Un consultor puede tener empresas en BRONZE y otras en GOLD/PLATINUM.
- La **exportación** (CSV de transacciones y ZIP Power BI con detalle) está en **PLATINUM** y es para **CONSULTOR/ADMIN**.

## Planes (capacidad por empresa gestionada)

| Capacidad | BRONZE | GOLD | PLATINUM |
|---|---:|---:|---:|
| KPIs de caja (in/out/net/saldo) | ✅ | ✅ | ✅ |
| Histórico recomendado | 6 meses | 12 meses | 24 meses |
| Alertas | ✅ | ✅ | ✅ |
| Informes mensuales (HTML) | ✅ | ✅ | ✅ (consultivo) |
| Tribunal (cumplimiento) | ❌ | ✅ | ✅ |
| Drill-down transacciones + analytics | ❌ | ✅ | ✅ |
| **Export transacciones (CSV)** | ❌ | ❌ | ✅ |
| **Export Power BI (ZIP) con detalle** | ❌ | ❌ | ✅ |
| Universal: análisis + preview XLSX | ✅ | ✅ | ✅ |
| Universal: correlaciones | ❌ | ✅ | ✅ |
| Universal: CSV normalizado + preview filas | ❌ | ❌ | ✅ |
| Assistant (reglas · chat) | ❌ | ❌ | ✅ |

## Endpoints relevantes (referencia rápida)

- Caja/Transacciones:
  - `GET /api/companies/{companyId}/transactions` (drill-down) → **GOLD+**
  - `GET /api/companies/{companyId}/transactions/export.csv` → **PLATINUM**
- Universal:
  - `GET /api/companies/{companyId}/universal/summary` → **BRONZE+**
  - `POST /api/companies/{companyId}/universal/xlsx/preview` → **BRONZE+** (consultor/admin)
  - `GET /api/companies/{companyId}/universal/imports/latest/normalized.csv` → **PLATINUM**
  - `GET /api/companies/{companyId}/universal/imports/latest/rows` → **PLATINUM**
- Power BI export:
  - `GET /api/companies/{companyId}/powerbi/export.zip?from=YYYY-MM&to=YYYY-MM`
    - Incluye `fact_transactions.csv` solo en **PLATINUM**
    - Acceso: **CONSULTOR/ADMIN**
