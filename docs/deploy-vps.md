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
- `JWT_SECRET` (obligatorio)

En el VPS (opcional si no está en Actions):
- `CORS_ALLOWED_ORIGINS` (solo si consumes el backend cross-origin; si usas el frontend Nginx con proxy `/api`, no suele hacer falta)

## 3) Despliegue automático

Cada push a `main`:
1. Build + push de `backend/` y `frontend/` a GHCR.
2. Copia `docker-compose.prod.yml` a `/opt/enterpriseiq`.
3. `docker compose pull` + `docker compose up -d`.

## 4) Acceso

- Frontend: `http://<tu-vps>/`
- Backend (debug): `http://<tu-vps>:8081/api/health`

