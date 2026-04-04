# Backups y restore (VPS)

Objetivo: poder recuperar **PostgreSQL + storage** (imports/reportes/universal) en minutos.

## Qué se backuppea

1) **Base de datos** (Postgres): usuarios, empresas, permisos, imports, KPIs, auditoría…
2) **Storage** (volumen `backend-storage`): ficheros subidos, normalizados, reportes y artefactos.

Recomendación adicional:
- Exporta también `grafana-data` si quieres conservar dashboards manuales (si todo está provisionado desde `ops/`, puedes prescindir).

## Backup (script)

En el VPS, dentro de `/opt/enterpriseiq`:

```bash
chmod +x ops/backup/backup.sh
./ops/backup/backup.sh
```

Genera:
- `backups/db/enterpriseiq-YYYYMMDD-HHMMSS.sql.gz`
- `backups/storage/backend-storage-YYYYMMDD-HHMMSS.tar.gz`
- (opcional) `backups/pgdata/pgdata-YYYYMMDD-HHMMSS.tar.gz` (si activas `INCLUDE_PGDATA_VOLUME=1`)

### ¿Cuándo backuppear `pgdata`?

- Recomendado por defecto: **pg_dump** (más portable y seguro entre versiones).
- `pgdata` sirve si quieres **restore ultrarrápido** y tu Postgres/volumen están “tal cual” (misma major version, mismo entorno). Es más pesado y menos portable.

### Programar con cron

Ejemplo (diario a las 03:15):

```bash
crontab -e
```

```cron
15 3 * * * cd /opt/enterpriseiq && ./ops/backup/backup.sh >> backups/backup.log 2>&1
```

## Restore (procedimiento)

1) **Parar app** (evita escrituras durante restore):

```bash
cd /opt/enterpriseiq
docker compose -f docker-compose.prod.yml stop backend
```

2) (Opcional) **Restore rápido por volumen `pgdata`** (si tienes `backups/pgdata/...`):

```bash
docker compose -f docker-compose.prod.yml stop postgres
./ops/backup/restore.sh --pgdata backups/pgdata/pgdata-YYYYMMDD-HHMMSS.tar.gz
docker compose -f docker-compose.prod.yml up -d postgres
```

Si haces esto, **no hace falta** `--db` (ya estás restaurando el data dir completo).

2) **Restaurar DB** (esto borra y recrea la DB):

```bash
./ops/backup/restore.sh --db backups/db/enterpriseiq-YYYYMMDD-HHMMSS.sql.gz
```

3) **Restaurar storage**:

```bash
./ops/backup/restore.sh --storage backups/storage/backend-storage-YYYYMMDD-HHMMSS.tar.gz
```

4) **Levantar backend**:

```bash
docker compose -f docker-compose.prod.yml up -d backend
```

5) Checks rápidos:

```bash
docker compose -f docker-compose.prod.yml logs --tail=200 backend
docker compose -f docker-compose.prod.yml exec backend curl -s http://localhost:8082/actuator/health
```

## Notas

- El restore **rota sesiones** si cambias contraseñas (por diseño).
- Si cambias versión de schema (Flyway), el restore trae el schema del backup. Si el código es más nuevo, Flyway aplicará migraciones pendientes al arrancar.
- Para generar backup de `pgdata` además de `pg_dump`:

```bash
INCLUDE_PGDATA_VOLUME=1 ./ops/backup/backup.sh
```
