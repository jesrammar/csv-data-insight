# Despliegue en VPS (Docker + CI/CD)

## 1) Requisitos en el VPS

```bash
sudo mkdir -p /opt/csv-data-insight
sudo chown $USER /opt/csv-data-insight
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
```

## 2) Secrets en GitHub

En **Settings → Secrets and variables → Actions**:

- `VPS_HOST`
- `VPS_USER`
- `VPS_SSH_KEY`
- `VPS_PORT` (opcional, por defecto 22)
- `GHCR_PAT` (token para leer paquetes)
- `CEGID_SUBSCRIPTION_KEY`
- `CEGID_MOCK_ENABLED` (`true` o `false`)
- `CEGID_BASE_URL` (opcional)

## 3) Despliegue automático

Cada push a `main`:

1. Construye el JAR.
2. Publica la imagen en GHCR.
3. Actualiza el VPS con `docker compose up -d`.

## 4) Acceso

```
http://<tu-vps>:8080/demo
```
