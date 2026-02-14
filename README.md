# EnterpriseIQ (TFG Demo)

Plataforma demo para ASECON con multiempresa, importaci�n CSV, KPIs mensuales y reportes HTML.

## Stack
- Backend: Java 21 + Spring Boot 3 (Maven), Spring Web/Security/Data JPA/Validation
- DB: PostgreSQL
- Migraciones: Flyway
- Frontend: React + TypeScript (Vite) + React Router + TanStack Query
- Auth: JWT (access token + refresh token con rotacion)
- Infra: Docker + docker-compose

## Arquitectura (resumen)
- Backend Spring Boot expone API REST con JWT y autorizaci�n por rol.
- PostgreSQL almacena usuarios, empresas, imports, staging, transacciones, KPIs, alertas y reportes.
- Flyway crea esquema y carga datos seed (ADMIN, CONSULTOR, CLIENTE y 2 empresas).
- Importaciones CSV se suben por API (multipart) y se guardan en filesystem.
- Scheduler procesa imports PENDING, valida, carga staging y normaliza transacciones.
- Servicio de KPIs recalcula m�tricas mensuales y dispara alertas si net_flow < umbral.
- Reportes se generan como HTML y se guardan en filesystem con metadata en DB.
- Frontend React consume API, permite login, selecci�n de empresa, dashboard, imports y reportes.
- Multi-tenant l�gico: cada tabla clave tiene `company_id` y acceso se valida por rol.
- CORS configurado para frontend local.

## CSV esperado
Columnas obligatorias:
- `txn_date` (YYYY-MM-DD)
- `amount` (decimal; positivo=entrada, negativo=salida)

Columnas opcionales:
- `description` (string)
- `counterparty` (string)
- `balance_end` (decimal)

Se permiten columnas extra y se ignoran.

Reglas de validaci�n:
- Falta `txn_date` o `amount` -> ERROR
- Fecha inv�lida -> WARNING (fila se salta)
- amount no num�rico -> WARNING (fila se salta)

## Credenciales seed
- ADMIN: `admin@asecon.local` / `password`
- CONSULTOR: `consultor@asecon.local` / `password`
- CLIENTE: `cliente@acme.local` / `password`

## Levantar con Docker
1. `docker-compose up --build`
2. Backend en `http://localhost:8080`
3. Frontend en `http://localhost:5173`

## Ejemplos CSV
- `samples/sample-ok.csv`
- `samples/sample-warnings.csv`

## API futuro
La integraci�n con Cegid API no est� implementada; se considera conector futuro.

## TODO PDF
El reporte se genera en HTML. Para PDF, se propone usar OpenHTMLtoPDF y exponer descarga.

## Autenticacion (JWT)
- Login: POST /api/auth/login devuelve ccessToken, efreshToken, ole, userId
- Refresh: POST /api/auth/refresh rota el refresh token y entrega nuevos tokens
- Logout: POST /api/auth/logout revoca access y refresh tokens

El frontend guarda ambos tokens en localStorage y renueva el ccessToken automaticamente si el backend responde 401.