# EnterpriseIQ

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-111827?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3-111827?style=for-the-badge&logo=springboot&logoColor=6DB33F" alt="Spring Boot 3" />
  <img src="https://img.shields.io/badge/PostgreSQL-16-111827?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL 16" />
  <img src="https://img.shields.io/badge/React-TypeScript-111827?style=for-the-badge&logo=react&logoColor=61DAFB" alt="React TypeScript" />
  <img src="https://img.shields.io/badge/Docker-Compose-111827?style=for-the-badge&logo=docker&logoColor=2496ED" alt="Docker Compose" />
  <img src="https://img.shields.io/badge/Observability-Prometheus%20%2B%20Grafana-111827?style=for-the-badge&logo=prometheus&logoColor=E6522C" alt="Observability" />
</p>

Plataforma orientada a consultoría y analítica operativa con **backend en Spring Boot**, **frontend en React**, **PostgreSQL + Flyway**, **JWT con refresh token**, **ingesta CSV/XLSX**, **reportes PDF/HTML** y un stack de **observabilidad y operación** preparado para despliegue.

> Nota: la versión antigua permanece en `legacy/csv-data-insight/`. La aplicación actual está en `backend/` + `frontend/`.

## Qué resuelve

- Centraliza datos y operación para entornos multiempresa con distintos roles de acceso.
- Convierte ficheros CSV/XLSX en información útil: KPIs, alertas, análisis tabular, presupuestos y entregables.
- Añade una base operable para despliegue real: autenticación, auditoría, observabilidad, backups y restore.

## Capacidades principales

- Multiempresa con roles `ADMIN`, `CONSULTOR` y `CLIENTE`.
- Ingesta de `CSV` y `XLSX` con procesamiento por dominio.
- Módulo **Caja** para KPIs, tendencias y alertas.
- Módulo **Universal** para análisis tabular y detección de problemas/insights.
- Módulo **Presupuesto** con normalización a formato largo e insights accionables.
- Entregables en **HTML** y **PDF**.
- Auditoría de acciones relevantes y automatización de tareas programadas.

## Arquitectura y stack

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

### Infra y operación

- `Docker` + `docker compose`
- `Grafana`
- `Prometheus`
- `Alertmanager`
- Storage persistente en volumen `backend-storage`
- Despliegue productivo con imágenes `GHCR`

## Qué demuestra técnicamente

- Diseño de una aplicación full-stack con backend principal en **Spring Boot**.
- Modelo de seguridad con autenticación JWT, refresh token y control por rol/empresa.
- Persistencia relacional con migraciones versionadas mediante Flyway.
- Operación más allá del CRUD: observabilidad, alertas, runbooks, backups y restore.
- Procesamiento de datos y generación de entregables como parte del dominio del producto.

## Estructura del repositorio

- `backend/`: API, seguridad, persistencia, lógica de negocio y métricas.
- `frontend/`: interfaz React + TypeScript.
- `docs/`: runbooks y documentación operativa.
- `ops/`: configuración de Prometheus, Grafana, Alertmanager y backups.
- `legacy/`: versión previa del proyecto.

## Arranque en local

```bash
docker compose up --build
```

Servicios principales en desarrollo:

- Backend: `http://localhost:8081`
- Frontend: `http://localhost:5174`
- PostgreSQL: `localhost:5433`
- Métricas backend: `http://localhost:8082/actuator/prometheus`

## Credenciales seed de desarrollo

Solo para entorno `dev` definido en `docker-compose.yml`:

- `admin@asecon.local` / `password`
- `consultor@asecon.local` / `password`
- `cliente@acme.local` / `password`

En producción no se cargan seeds automáticamente.

## Producción y operación

La configuración de producción usa `docker-compose.prod.yml` con:

- imágenes `GHCR` para backend y frontend,
- PostgreSQL persistente,
- backend con puerto privado,
- Prometheus, Grafana y Alertmanager,
- secretos y parámetros controlados mediante variables de entorno.

Documentación operativa disponible en:

- [Runbook de observabilidad](docs/runbook-observabilidad.md)
- [Backups y restore](docs/backup-restore.md)

## Observabilidad

Stack operativo incluido en el repositorio:

- Backend Prometheus: `backend:8082/actuator/prometheus`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`
- Alertmanager: `http://localhost:9093`

El proyecto incluye dashboards, alertas y guías de actuación para incidencias y restore.

## Formato mínimo de importación CSV

Columnas obligatorias para transacciones de caja:

- `txn_date` (`YYYY-MM-DD`)
- `amount` (decimal; positivo = entrada, negativo = salida)

Columnas opcionales:

- `description`
- `counterparty`
- `balance_end`

## Contexto

`EnterpriseIQ` forma parte de mi portfolio como proyecto que refuerza backend con **Spring Boot**, persistencia relacional, seguridad, observabilidad, despliegue y operación realista de producto.
