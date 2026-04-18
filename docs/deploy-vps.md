# Despliegue en VPS (EnterpriseIQ)

Este despliegue usa las imágenes publicadas en GHCR:
- `enterpriseiq-backend`
- `enterpriseiq-frontend` (Nginx + SPA + proxy `/api` → backend)

## 1) Requisitos en el VPS

```bash
sudo mkdir -p /opt/enterpriseiq
sudo chown $USER /opt/enterpriseiq
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
```

## 2) Variables/secretos

En GitHub Actions (Settings → Secrets and variables → Actions):
- `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`, `GHCR_PAT`
- `JWT_SECRET` (obligatorio, largo y aleatorio)
- `POSTGRES_PASSWORD` (obligatorio)
- `GRAFANA_ADMIN_PASSWORD` (si usas Grafana)

En el VPS (opcional si no está en Actions):
- `.env` (recomendado) junto a `docker-compose.prod.yml` (puedes partir de `.env.prod.example`)
- `CORS_ALLOWED_ORIGINS` (solo si consumes el backend cross-origin; si usas el frontend Nginx con proxy `/api`, no suele hacer falta)
- `APP_COOKIES_SAME_SITE` (recomendado: `Strict`)

## 3) Despliegue automático

Cada push a `main`:
1. Build + push de `backend/` y `frontend/` a GHCR.
2. Copia `docker-compose.prod.yml` a `/opt/enterpriseiq`.
3. `docker compose pull` + `docker compose up -d`.

## 4) Acceso

- Frontend: `http://<tu-vps>/`
- Backend (debug local): `http://127.0.0.1:8081/api/health`

## Checklist de hardening (prod)

- [ ] Copiar `.env.prod.example` a `.env` y rellenar secretos (`JWT_SECRET`, `POSTGRES_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`).
- [ ] Confirmar que **solo** el frontend está expuesto públicamente (en `docker-compose.prod.yml` backend/postgres están bound a `127.0.0.1`).
- [ ] Si no necesitas API cross-origin, dejar `CORS_ALLOWED_ORIGINS` vacío.
- [ ] Usar HTTPS en el VPS (Caddy/Nginx del host) y activar HSTS en el terminador TLS (no dentro del contenedor).
- [ ] Programar `ops/backup/backup.sh` y probar `ops/backup/restore.sh`.
- [ ] Checklist ampliado: `docs/prod-hardening-checklist.md`.
