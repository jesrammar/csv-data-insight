# Despliegue en VPS (Docker Compose · producción)

Este repo despliega **PostgreSQL + backend + frontend**. En prod, el frontend sirve el panel (puerto 80) y el backend queda en 8081 (opcional exponerlo).

## 1) Requisitos en el VPS

```bash
sudo mkdir -p /opt/enterpriseiq
sudo chown $USER /opt/enterpriseiq
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
```

## 2) Preparar `docker-compose.prod.yml`

- Copia `docker-compose.prod.yml` al VPS (p. ej. `/opt/enterpriseiq/docker-compose.yml`).
- Copia también la carpeta `ops/` al VPS (p. ej. `/opt/enterpriseiq/ops/`) para Prometheus/Grafana provisioning.
- Crea un `.env` en la misma carpeta con variables recomendadas:

```bash
# GitHub Container Registry
IMAGE_OWNER=tu-org-o-usuario

# Seguridad (OBLIGATORIO)
JWT_SECRET=pon-una-frase-larga-y-aleatoria

# CORS (frontend del VPS)
CORS_ALLOWED_ORIGINS=http://tu-dominio-o-ip

# Cookies (si sirves por HTTPS, mantener true)
APP_COOKIES_SECURE=true
APP_COOKIES_SAME_SITE=Lax

# Observabilidad (recomendado)
GRAFANA_ADMIN_PASSWORD=pon-una-password-fuerte
GRAFANA_ROOT_URL=http://localhost:3000
GRAFANA_DISABLE_LOGIN_FORM=false

# Prometheus retención (recomendado)
PROMETHEUS_RETENTION_TIME=15d
PROMETHEUS_RETENTION_SIZE=2GB

# Alertmanager (notificaciones por email; opcional)
SMTP_HOST=smtp.tudominio.com
SMTP_PORT=587
SMTP_FROM=alerts@tudominio.com
SMTP_USER=alerts@tudominio.com
SMTP_PASSWORD=pon-una-password
ALERT_EMAIL_TO=tu-email@tudominio.com
```

## 3) Levantar/actualizar

```bash
cd /opt/enterpriseiq
docker compose pull
docker compose up -d
docker compose ps
```

Datos persistentes:
- Postgres usa volumen `pgdata`.
- Ficheros (imports/reportes/universal/inbox) usan volumen `backend-storage`.

## 4) Acceso

- Frontend: `http://<tu-vps>/`
- Backend: `http://<tu-vps>:8081/` (si mantienes el puerto publicado)

## 4.1) Observabilidad (Actuator/Prometheus)

El backend expone **Actuator** en un **puerto separado** (`MANAGEMENT_SERVER_PORT`, por defecto `8082`) y **no se publica al host** en `docker-compose.prod.yml`, por lo que solo es accesible desde la **red interna de Docker**.

Además, `docker-compose.prod.yml` incluye:
- **Prometheus** escuchando solo en `127.0.0.1:${PROMETHEUS_PORT:-9090}` (no público).
- **Grafana** escuchando solo en `127.0.0.1:${GRAFANA_PORT:-3000}` (no público).

Ejemplos:

```bash
# health (desde dentro del contenedor)
docker compose exec backend curl -s http://localhost:8082/actuator/health

# métricas Prometheus (desde dentro del contenedor)
docker compose exec backend curl -s http://localhost:8082/actuator/prometheus | head
```

Acceso recomendado desde tu máquina (túnel SSH):

```bash
ssh -L 3000:localhost:3000 -L 9090:localhost:9090 -L 9093:localhost:9093 <user>@<tu-vps>
```

- Grafana: `http://localhost:3000` (user `admin` y password `GRAFANA_ADMIN_PASSWORD`)
- Prometheus: `http://localhost:9090`
- Alertmanager: `http://localhost:9093` (silences / estado de notificaciones)

Dashboards provisionados (carpeta **EnterpriseIQ**):
- `EnterpriseIQ · Ingestion Overview` (p95, throughput, errores, volumen)
- `EnterpriseIQ · Ingestion Errors / Causes` (top causas por `error` tag)
- `EnterpriseIQ · JVM / Backend` (heap/cpu/gc)
- `EnterpriseIQ · API Golden Signals` (RPS, p95, 4xx/5xx, Tomcat/Hikari)

Runbook operativo:
- `docs/runbook-observabilidad.md`

## 6) (Opcional) Exponer Grafana con HTTPS

Recomendación: **no publiques** Grafana/Prometheus/Alertmanager directamente. Déjalos en `127.0.0.1` y usa reverse proxy con HTTPS.

- Ejemplo Caddy: `ops/caddy/Caddyfile.example`

## 5) CI/CD (si lo usas)

En **Settings → Secrets and variables → Actions**:
- `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`, `VPS_PORT` (opcional)
- `GHCR_PAT` (token con permiso de lectura de paquetes)
