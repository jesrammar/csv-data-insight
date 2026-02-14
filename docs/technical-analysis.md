# Analisis Tecnico - EnterpriseIQ

Fecha: 2026-02-14

## Resumen Ejecutivo
- Aplicacion demo TFG para multiempresa con importacion CSV, KPIs mensuales y reportes HTML.
- Backend Spring Boot 3 + JPA/Flyway + PostgreSQL; Frontend React + TypeScript + Vite.
- Seguridad JWT con access + refresh token (rotacion, revocacion y logout).
- CI/CD: build + push GHCR en main; deploy condicionado a secretos (VPS + Cegid).

## Arquitectura
- API REST en backend con control por rol y validacion por empresa.
- PostgreSQL como fuente de datos (usuarios, empresas, importaciones, KPIs, reportes).
- Scheduler para procesar importaciones y limpieza de tokens.
- Almacenamiento de archivos en filesystem (imports y reportes).

## Componentes Principales
- Auth:
  - AuthController expone login/refresh/logout.
  - JwtService emite access tokens con jti, issuer y claims de rol.
  - TokenService crea refresh tokens, rota, revoca y blacklist de access tokens.
  - JwtAuthFilter valida token, cuenta activa y revocacion.
- Empresas y acceso:
  - CompanyController y AccessService gestionan acceso por rol/empresa.
- Importaciones:
  - ImportController + ImportService (subida CSV, staging y normalizacion).
  - ImportScheduler procesa importaciones pendientes.
- Dashboards y KPIs:
  - DashboardController consulta KPIs por rango mensual.
- Reportes:
  - ReportController genera HTML y lo persiste con metadata.

## Modelo de Datos (alto nivel)
- companies, users, user_companies
- imports, staging_transactions, 	ransactions
- kpi_monthly, lert_rules, lerts
- eports
- efresh_tokens, evoked_tokens

## Seguridad
- JWT stateless con rotacion de refresh tokens.
- Revocacion de access tokens via evoked_tokens (jti + expiracion).
- Endpoints protegidos por rol y control de empresa.
- CORS habilitado para frontend local.

## CI/CD
- ci-cd.yml construye y publica imagen en GHCR.
- Deploy condicionado a VPS_HOST y CEGID_SUBSCRIPTION_KEY.

## Observaciones Tecnicas
Fortalezas:
- Arquitectura coherente y modular.
- Buen aislamiento de seguridad y roles.
- Migrations con Flyway y datos seed claros.
- Tests de flujo de auth agregados.

Riesgos:
- README.md no reflejaba el refresh token (ya actualizado).
- docker-compose.yml no contempla secretos Cegid (solo local). OK para demo.
- Tests no se ejecutan en CI (se usa -DskipTests).
- Almacenamiento local de archivos sin politica de limpieza.

## Recomendaciones
Corto plazo:
- Activar tests en CI o al menos en PRs.
- Documentar variables sensibles y flujos de autenticacion.
- Añadir politica de limpieza de archivos en storage.

Medio plazo:
- Observabilidad: logs estructurados y metricas.
- Rate limit y hardening adicional.
- Exportacion PDF real del reporte.

## Estado Actual
- Aplicacion funcional para demo.
- Seguridad JWT y refresh tokens operativos.
- Deploy desactivado si no hay claves VPS/Cegid.