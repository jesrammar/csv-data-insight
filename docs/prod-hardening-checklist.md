# Checklist de hardening (producción)

## Secretos y configuración

- [ ] Crear `.env` a partir de `.env.prod.example` (no commitear `.env`).
- [ ] `JWT_SECRET` largo/aleatorio (rotarlo invalida sesiones).
- [ ] `POSTGRES_PASSWORD` largo/aleatorio.
- [ ] (Opcional) `GRAFANA_ADMIN_PASSWORD` fuerte si expones Grafana (idealmente no público).

## Red y exposición

- [ ] Exponer solo el `frontend` públicamente.
- [ ] `backend`, `postgres`, `prometheus`, `alertmanager`, `grafana` bound a `127.0.0.1` (como en `docker-compose.prod.yml`).
- [ ] Si no necesitas consumo cross-origin, dejar `CORS_ALLOWED_ORIGINS` vacío.
- [ ] Si necesitas CORS, usar allowlist estricta (sin `*`) y validar dominios exactos.

## Cookies / sesiones

- [ ] En HTTPS: `APP_COOKIES_SECURE=true`.
- [ ] Recomendado: `APP_COOKIES_SAME_SITE=Strict` (si no usas flujos cross-site).

## HTTPS + headers

- [ ] Terminar TLS en el host (Caddy/Nginx) y reenviar al contenedor `frontend`.
- [ ] Activar HSTS en el terminador TLS (solo si todo el dominio va por HTTPS).
- [ ] Verificar headers en respuestas: `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`.
- [ ] CSP: en `frontend/nginx.conf` hay una CSP “compatible” (permite `style-src 'unsafe-inline'`). Cuando el frontend llegue a 0 inline styles, activar la CSP “estricta” y validarlo con `node scripts/check-inline-styles.mjs --max 0`.

## Backups y operación

- [ ] Programar `ops/backup/backup.sh` (cron) y probar `ops/backup/restore.sh`.
- [ ] Revisar espacio en disco de volúmenes Docker (pgdata, storage, prometheus, grafana).
- [ ] Verificar que `/api/health` y `actuator` (prometheus) responden como esperas.
