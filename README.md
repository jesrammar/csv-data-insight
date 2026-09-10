# EnterpriseIQ

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-111827?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3-111827?style=for-the-badge&logo=springboot&logoColor=6DB33F" alt="Spring Boot 3" />
  <img src="https://img.shields.io/badge/PostgreSQL-16-111827?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL 16" />
  <img src="https://img.shields.io/badge/React-TypeScript-111827?style=for-the-badge&logo=react&logoColor=61DAFB" alt="React TypeScript" />
  <img src="https://img.shields.io/badge/Docker-Compose-111827?style=for-the-badge&logo=docker&logoColor=2496ED" alt="Docker Compose" />
  <img src="https://img.shields.io/badge/Observability-Prometheus%20%2B%20Grafana-111827?style=for-the-badge&logo=prometheus&logoColor=E6522C" alt="Observability" />
</p>

Plataforma orientada a consultoria y analitica operativa con **backend en Spring Boot**, **frontend en React**, **PostgreSQL + Flyway**, **JWT con refresh token**, **ingesta CSV/XLSX**, **reportes PDF/HTML** y un stack de **observabilidad y operacion** preparado para despliegue.

<p align="center">
  <img src="docs/architecture/enterpriseiq-overview.svg" alt="Arquitectura implementada de EnterpriseIQ" width="100%" />
</p>

Todos los datos incluidos en pruebas y plantillas descargables son **sinteticos**. Los datos de clientes, organismos o fuentes oficiales no deben versionarse.

## Que resuelve

- Centraliza datos y operacion para entornos multiempresa con distintos roles de acceso.
- Convierte ficheros CSV/XLSX en informacion util: KPIs, alertas, analisis tabular, presupuestos y entregables.
- Anade una base operable para despliegue real: autenticacion, auditoria, observabilidad, backups y restore.

## Capacidades principales

- Multiempresa con roles `ADMIN`, `CONSULTOR` y `CLIENTE`.
- Ingesta de `CSV` y `XLSX` con procesamiento por dominio.
- Modulo **Caja** para KPIs, tendencias y alertas.
- Modulo **Universal** para analisis tabular y deteccion de problemas e insights.
- Modulo **Presupuesto** con normalizacion a formato largo e insights accionables.
- Entregables en **HTML** y **PDF**.
- Auditoria de acciones relevantes y automatizacion de tareas programadas.

## Stack

- Backend: Java 21 + Spring Boot 3 (Maven)
- DB: PostgreSQL + Flyway
- Frontend: React + TypeScript (Vite) + React Router + TanStack Query
- Auth: JWT (access token + refresh token con rotacion) + cartera consultor-empresas gestionadas
- Observabilidad: Spring Actuator + Prometheus + Grafana + Alertmanager
- Infra: Docker + docker compose

## Arquitectura (resumen)

- Backend REST con permisos por rol (`ADMIN`, `CONSULTOR`, `CLIENTE`) y acceso por empresa gestionada.
- Storage en filesystem (volumen `backend-storage`) para imports, Universal y reportes.
- Automatizacion con jobs programados (KPIs, informes, snapshots) y reintentos.
- Auditoria de acciones relevantes, incluyendo administracion de usuarios y eventos de autenticacion.

Decision de dominio:

- Ver [docs/adr-001-modelo-dominio-consultora.md](docs/adr-001-modelo-dominio-consultora.md) para la decision de producto y arquitectura que fija la app como plataforma para consultoras.

## Arquitectura y stack tecnico

### Backend

- `Java 21`
- `Spring Boot 3`
- `Spring Web`
- `Spring Security`
- `Spring Data JPA`
- `Spring Validation`
- `PostgreSQL`
- `Flyway`
- `JWT` (access token + refresh token)
- `Spring Actuator`
- `Micrometer + Prometheus`

### Frontend

- `React 18`
- `TypeScript`
- `Vite`
- `React Router`
- `TanStack Query`
- `ECharts`

### Infra y operacion

- `Docker` + `docker compose`
- `Grafana`
- `Prometheus`
- `Alertmanager`
- Storage persistente en volumen `backend-storage`
- Despliegue productivo con imagenes `GHCR`

## Que demuestra tecnicamente

- Diseno de una aplicacion full-stack con backend principal en **Spring Boot**.
- Modelo de seguridad con autenticacion JWT, refresh token y control por rol y empresa.
- Persistencia relacional con migraciones versionadas mediante Flyway.
- Operacion mas alla del CRUD: observabilidad, alertas, runbooks, backups y restore.
- Procesamiento de datos y generacion de entregables como parte del dominio del producto.

## Estructura del repositorio

- `backend/`: API, seguridad, persistencia, logica de negocio y metricas.
- `frontend/`: interfaz React + TypeScript.
- `docs/`: runbooks y documentacion operativa.
- `ops/`: configuracion de Prometheus, Grafana, Alertmanager y backups.
- `scripts/`: controles automatizados de calidad y limpieza del repositorio.
- `backend/src/test/resources/fixtures/`: fixtures sinteticos usados exclusivamente por las pruebas.

## Arranque en local

```bash
docker compose up --build
```

Servicios principales en desarrollo:

- Backend: `http://localhost:8081`
- Frontend: `http://localhost:5174`
- PostgreSQL: `localhost:5433`
- Metricas backend: `http://localhost:8082/actuator/prometheus`

## Credenciales seed de desarrollo

Solo para entorno `dev` definido en `docker-compose.yml`:

- `admin@asecon.local` / `password`
- `consultor@asecon.local` / `password`
- `cliente@acme.local` / `password`

En produccion no se cargan seeds automaticamente.

## Calidad y seguridad del repositorio

Antes de abrir una pull request:

```bash
node scripts/check-repository-hygiene.mjs
node scripts/check-mojibake.mjs
node scripts/check-inline-styles.mjs
cd backend && mvn test
cd ../frontend && npm ci && npm run build
```

- La politica de datos esta en [docs/data-handling.md](docs/data-handling.md).
- Las pautas de contribucion estan en [CONTRIBUTING.md](CONTRIBUTING.md).
- Los avisos de seguridad se gestionan segun [SECURITY.md](SECURITY.md).

## Produccion y operacion

La configuracion de produccion usa `docker-compose.prod.yml` con:

- imagenes `GHCR` para backend y frontend
- PostgreSQL persistente
- backend con puerto privado
- Prometheus, Grafana y Alertmanager
- secretos y parametros controlados mediante variables de entorno

Documentacion operativa disponible en:

- [Runbook de observabilidad](docs/runbook-observabilidad.md)
- [Backups y restore](docs/backup-restore.md)

## Observabilidad

Stack operativo incluido en el repositorio:

- Backend Prometheus: `backend:8082/actuator/prometheus`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`
- Alertmanager: `http://localhost:9093`

El proyecto incluye dashboards, alertas y guias de actuacion para incidencias y restore.

## Formato minimo de importacion CSV

Columnas obligatorias para transacciones de caja:

- `txn_date` (`YYYY-MM-DD`)
- `amount` (decimal; positivo = entrada, negativo = salida)

Columnas opcionales:

- `description`
- `counterparty`
- `balance_end`

## Contexto

`EnterpriseIQ` forma parte de mi portfolio como proyecto que refuerza backend con **Spring Boot**, persistencia relacional, seguridad, observabilidad, despliegue y operacion realista de producto.
