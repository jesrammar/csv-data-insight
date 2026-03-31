# EnterpriseIQ (TFG Demo)

Plataforma demo para ASECON con multiempresa, importación CSV/XLSX, KPIs mensuales, alertas, automatización y reportes HTML.

## Stack
- Backend: Java 21 + Spring Boot 3 (Maven), Spring Web/Security/Data JPA/Validation
- DB: PostgreSQL
- Migraciones: Flyway
- Frontend: React + TypeScript (Vite) + React Router + TanStack Query
- Auth: JWT (access token + refresh token con rotación)
- Infra: Docker + docker-compose

## Arquitectura (resumen)
- Backend Spring Boot expone API REST con JWT y autorización por rol.
- PostgreSQL almacena usuarios, empresas, imports, staging, transacciones, KPIs, alertas, reportes y automatización.
- Flyway crea esquema y carga datos seed (ADMIN, CONSULTOR, CLIENTE y 2 empresas).
- Importaciones CSV se suben por API (multipart) y se guardan en filesystem.
- Scheduler procesa imports PENDING, valida, carga staging y normaliza transacciones.
- Servicio de KPIs recalcula métricas mensuales y dispara alertas si `net_flow` < umbral.
- Reportes se generan como HTML y se guardan en filesystem con metadata en DB.
- Automatización: jobs programados + cola con reintentos (KPIs / informes / recomendaciones).
- Recomendaciones: snapshot guardado para que el cliente final vea acciones en su vista ejecutiva.

## CSV esperado (transacciones)
Columnas obligatorias:
- `txn_date` (YYYY-MM-DD)
- `amount` (decimal; positivo=entrada, negativo=salida)

Columnas opcionales:
- `description` (string)
- `counterparty` (string)
- `balance_end` (decimal)

Reglas de validación:
- Falta `txn_date` o `amount` -> ERROR
- Fecha inválida -> WARNING (fila se salta)
- Amount no numérico -> WARNING (fila se salta)

## Credenciales seed
- ADMIN: `admin@asecon.local` / `password`
- CONSULTOR: `consultor@asecon.local` / `password`
- CLIENTE: `cliente@acme.local` / `password`

## Levantar con Docker
1. `docker-compose up --build`
2. Backend en `http://localhost:8081`
3. Frontend en `http://localhost:5174`
4. Postgres en `localhost:5433` (solo dev)

## Ingesta automática (sin API)
Si tu ERP no tiene API o no tienes credenciales, puedes automatizar por “carpeta vigilada”.

- Ruta dentro del contenedor: `./storage/inbox/<companyId>/`
- Deja ahí ficheros `*.csv` cuyo nombre incluya un periodo `YYYY-MM` (por ejemplo `transactions-2026-03.csv`).
- El scheduler detecta nuevos ficheros, crea un import y los mueve a `processed/` (o `errors/` si no detecta periodo).

## Automatización (run now)
Endpoints para consultor/admin:
- `POST /api/companies/{companyId}/automation/kpis/recompute?monthsBack=2`
- `POST /api/companies/{companyId}/automation/reports/monthly?period=2026-03`
- `POST /api/companies/{companyId}/automation/recommendations/snapshot?period=2026-03`
- `GET  /api/companies/{companyId}/automation/jobs`

El cliente final puede leer las recomendaciones:
- `GET /api/companies/{companyId}/recommendations/latest`

## API futuro
La integración con Cegid API no está implementada; se considera conector futuro.

## TODO PDF
El reporte se genera en HTML. Para PDF, se propone usar OpenHTMLtoPDF y exponer descarga.

## Known issues (TFG)
- Ver `docs/known-issues.md`.

## Autenticación (JWT)
- Login: `POST /api/auth/login` devuelve `accessToken`, `refreshToken`, `role`, `userId`
- Refresh: `POST /api/auth/refresh` rota el refresh token y entrega nuevos tokens
- Logout: `POST /api/auth/logout` revoca access y refresh tokens

El frontend guarda ambos tokens en localStorage y renueva el access token automáticamente si el backend responde 401.

