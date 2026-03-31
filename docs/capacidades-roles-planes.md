# Capacidades por roles y planes (EnterpriseIQ)

Este documento resume qué puede hacer cada **rol** y qué se habilita por **plan** (por empresa).

## Roles (permisos funcionales)

| Rol | Objetivo | Puede ver | Puede operar |
|---|---|---|---|
| **CLIENTE** | Entender y decidir rápido | Resumen, Caja, Alertas, Informes, Ayuda | No sube datos ni ejecuta automatizaciones |
| **CONSULTOR** | Operar para varias empresas | Todo lo anterior + módulos de consultoría (según plan) | Importaciones, Tribunal, Universal, Automatización, Recomendaciones |
| **ADMIN** | Administrar plataforma | Todo | Gestión de empresas + permisos totales |

Notas:
- El **plan** aplica por **empresa**. Un consultor puede tener empresas en BRONZE y otras en GOLD/PLATINUM.
- La **exportación** (CSV de transacciones y ZIP Power BI con detalle) está en **PLATINUM** y es para **CONSULTOR/ADMIN**.

## Planes (capacidad por empresa)

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
| Asistente (chat) | ❌ | ❌ | ✅ |

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
