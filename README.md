# EnterpriseIQ

Plataforma para consultoras (p. ej. ASECON) con multiempresa, ingesta CSV/XLSX, KPIs/alertas, presupuestos, Universal (análisis tabular) y entregables en PDF/HTML.

> Nota: la app antigua está en `legacy/csv-data-insight/`. La app actual es `backend/` + `frontend/`.

## Stack
- Backend: Java 21 + Spring Boot 3 (Maven)
- DB: PostgreSQL + Flyway
- Frontend: React + TypeScript (Vite) + React Router + TanStack Query
- Auth: JWT (access token + refresh token con rotación) + cartera consultor↔clientes
- Observabilidad: Spring Actuator + Prometheus + Grafana + Alertmanager
- Infra: Docker + docker compose

## Arquitectura (resumen)
- Backend REST con permisos por rol (ADMIN/CONSULTOR/CLIENTE) y acceso por empresa.
- Storage en filesystem (volumen `backend-storage`) para imports, universal y reportes.
- Automatización: jobs programados (KPIs/informes/snapshots) y reintentos.
- Auditoría: trazado de acciones relevantes (incluye admin de usuarios, cambios de enabled/empresas y acciones de auth).

## CSV esperado (Caja / transacciones)
Columnas obligatorias:
- `txn_date` (YYYY-MM-DD)
- `amount` (decimal; positivo=entrada, negativo=salida)

Columnas opcionales:
- `description` (string)
- `counterparty` (string)
- `balance_end` (decimal)

## Módulos
- **Caja**: KPIs, tendencias y alertas con import por periodo.
- **Universal**: sube cualquier CSV/XLSX (modo guiado para XLSX) y obtén resumen, problemas, insights y vistas.
- **Presupuesto**: normalización a formato largo + insights accionables + PDF.
- **Entregables**: reportes HTML + descarga PDF.

## Credenciales seed
- Solo **desarrollo** (perfil `dev`, `docker-compose.yml`): 
  - ADMIN: `admin@asecon.local` / `password`
  - CONSULTOR: `consultor@asecon.local` / `password`
  - CLIENTE: `cliente@acme.local` / `password`

En producción (`docker-compose.prod.yml`) no se cargan seeds: crea el primer ADMIN de forma controlada (p. ej. con una migración privada, un script de bootstrap o desde la DB).

## Levantar en local
1. `docker compose up --build`
2. Backend: `http://localhost:8081`
3. Frontend: `http://localhost:5174`
4. Postgres: `localhost:5433` (solo dev)

## Deploy (producción)
- VPS: usar `docker-compose.prod.yml` (imágenes GHCR `enterpriseiq-backend` y `enterpriseiq-frontend`).

## Operación (VPS)
- Runbook observabilidad: `docs/runbook-observabilidad.md`
- Backups/restore: `docs/backup-restore.md`

## Observabilidad
- Actuator (Prometheus): `backend:8082/actuator/prometheus`
- Alertas (ejemplos): p95 import/universal, errores/timeouts, golden signals API, saturación Tomcat/Hikari.
- Umbrales ajustables: `ops/prometheus/alerts.local.yml`
