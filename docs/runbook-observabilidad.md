# Runbook operativo · Observabilidad (Prometheus/Grafana/Alertmanager)

Stack:
- Backend métricas: `backend:8082/actuator/prometheus`
- Prometheus (localhost): `http://localhost:9090`
- Grafana (localhost): `http://localhost:3000`
- Alertmanager (localhost): `http://localhost:9093`

Acceso recomendado desde tu máquina (túnel SSH):

```bash
ssh -L 3000:localhost:3000 -L 9090:localhost:9090 -L 9093:localhost:9093 <user>@<tu-vps>
```

## Checklist rápido (antes de profundizar)

1) ¿Está el sistema vivo?

```bash
cd /opt/enterpriseiq
docker compose ps
docker compose logs --tail=200 backend
docker compose logs --tail=200 prometheus
docker compose logs --tail=200 alertmanager
```

## Restore (checklist) + objetivos (RTO/RPO)

Define “SLA interno” para que un VPS sea operable:
- **RPO objetivo** (pérdida máxima aceptable): `24h` (backup diario) o `1h` (si haces más frecuencia).
- **RTO objetivo** (tiempo máximo de recuperación): `15–30 min` con `pg_dump` + restore storage. `5–10 min` si restauras por volumen `pgdata` (opcional).

Checklist (orden recomendado):

1) Parar escritura:

```bash
cd /opt/enterpriseiq
docker compose -f docker-compose.prod.yml stop backend
```

2) Restaurar DB (elige una opción):

- Opción A (portable): `pg_dump` → `psql`:

```bash
./ops/backup/restore.sh --db backups/db/enterpriseiq-YYYYMMDD-HHMMSS.sql.gz
```

- Opción B (rápida, menos portable): volumen `pgdata`:

```bash
docker compose -f docker-compose.prod.yml stop postgres
./ops/backup/restore.sh --pgdata backups/pgdata/pgdata-YYYYMMDD-HHMMSS.tar.gz
docker compose -f docker-compose.prod.yml up -d postgres
```

3) Restaurar storage:

```bash
./ops/backup/restore.sh --storage backups/storage/backend-storage-YYYYMMDD-HHMMSS.tar.gz
```

4) Arrancar y validar:

```bash
docker compose -f docker-compose.prod.yml up -d backend
docker compose -f docker-compose.prod.yml exec backend curl -s http://localhost:8082/actuator/health
docker compose -f docker-compose.prod.yml exec backend curl -s http://localhost:8082/actuator/prometheus | head
```

2) ¿Hay saturación evidente (CPU/Mem/GC)?
- Grafana → **EnterpriseIQ · JVM / Backend**
- Prometheus quick checks:

```promql
rate(process_cpu_usage[5m])
sum(jvm_memory_used_bytes{area="heap"})
sum(rate(jvm_gc_pause_seconds_sum[5m]))
```

3) ¿Ha cambiado el patrón de carga?
- Grafana → **EnterpriseIQ · Ingestion Overview** (throughput + volumen)

## Alertas y qué hacer

### `ImportProcessingP95High` (warning) / `ImportProcessingP95VeryHigh` (critical)
Significado: el p95 del procesamiento de import está alto (10m).

Qué mirar (orden recomendado):
1) Impacto / volumen:

```promql
sum(rate(ingestion_import_process_count_total[5m]))
sum(rate(ingestion_import_process_bytes_sum[5m]))
histogram_quantile(0.95, sum(rate(ingestion_import_process_duration_seconds_bucket[5m])) by (le))
```

2) Fallos y causas (si hay):

```promql
sum(rate(ingestion_import_process_count_total{result="failed"}[5m]))
topk(10, sum(increase(ingestion_import_process_count_total{result="failed"}[1h])) by (error))
```

3) Logs backend alrededor del pico (buscar “METRIC ingestion.import”):

```bash
docker compose logs --since=30m backend | Select-String -Pattern "METRIC ingestion\\.import|import worker failed|ERROR|Exception"
```

Acciones típicas:
- Si sube el volumen (bytes/s) pero no suben fallos: subir CPU/RAM o limitar concurrencia (1 worker) y/o recomendar importar por periodos.
- Si los fallos dominan: revisar `topk(... by (error))`, arreglar input (CSV/XLSX raro) o aumentar timeouts/límites si procede.

### Ajuste de umbrales (carga real)

Los umbrales (8s/12s…) están pensados como base. Para afinar con tu VPS:

1) Mira el p95 “real” en horas de carga:

```promql
enterpriseiq:import_process_p95_seconds
enterpriseiq:universal_analyze_p95_seconds
enterpriseiq:api_latency_p95_seconds
```

2) Regla práctica:
- `warning` ≈ p95 base + 30–50%
- `critical` ≈ p95 base + 100% (o cuando ya afecta a UX/SLA)

3) Ajusta umbrales sin tocar el fichero base:
- Edita `ops/prometheus/alerts.local.yml` (umbral en segundos / cardinalidad) y reinicia Prometheus:

```bash
docker compose -f docker-compose.prod.yml restart prometheus
```

### `UniversalAnalyzeP95High` (warning) / `UniversalAnalyzeP95VeryHigh` (critical)
Significado: el p95 del análisis universal está alto (10m).

Qué mirar:
1) p95 + throughput:

```promql
sum(rate(ingestion_universal_analyze_count_total[5m]))
histogram_quantile(0.95, sum(rate(ingestion_universal_analyze_duration_seconds_bucket[5m])) by (le))
sum(rate(ingestion_universal_analyze_bytes_sum[5m]))
```

2) ¿Se está muestreando? (sirve para saber si el sistema “se protege”):

```promql
sum(rate(ingestion_universal_analyze_count_total{sampled="true"}[10m])) / clamp_min(sum(rate(ingestion_universal_analyze_count_total[10m])), 1)
```

3) Fallos y causas:

```promql
sum(rate(ingestion_universal_analyze_count_total{result="failed"}[5m]))
topk(10, sum(increase(ingestion_universal_analyze_count_total{result="failed"}[1h])) by (error))
```

4) Logs backend (buscar “METRIC ingestion.universal”):

```bash
docker compose logs --since=30m backend | Select-String -Pattern "METRIC ingestion\\.universal|universal|ERROR|Exception"
```

Acciones típicas:
- Si sube `sampled="true"` y aun así p95 alto: revisar CPU/GC; el dataset puede ser “ancho” (muchas columnas) o “sucio”.
- Si hay muchos `http_408` (timeouts): recomendar recortar columnas/filas o subir límites de análisis (con cuidado).

### `IngestionFailuresSpike` (warning) / `IngestionFailuresAny` (info)
Significado: se detectan fallos de import, fallos de universal o timeouts de XLSX.

Qué mirar:
1) ¿Qué tipo de fallo domina?

```promql
sum(increase(ingestion_import_process_count_total{result="failed"}[10m]))
sum(increase(ingestion_universal_analyze_count_total{result="failed"}[10m]))
sum(increase(ingestion_xlsx_timeout_count_total[10m]))
```

2) Causas (por `error`):

```promql
topk(10, sum(increase(ingestion_import_process_count_total{result="failed"}[1h])) by (error))
topk(10, sum(increase(ingestion_universal_analyze_count_total{result="failed"}[1h])) by (error))
```

3) Logs backend (última hora):

```bash
docker compose logs --since=60m backend | Select-String -Pattern "METRIC ingestion\\.(import|universal|xlsx)|PAYLOAD_TOO_LARGE|REQUEST_TIMEOUT|ERROR|Exception"
```

Acciones típicas:
- `http_413`/`PAYLOAD_TOO_LARGE`: el fichero excede límite → pedir dividir por periodos / export más pequeño.
- `http_408`/timeouts: carga alta o fichero demasiado grande/ancho → escalar recursos o ajustar límites con cautela.
- Excepciones recurrentes: reproducir con el fichero, mejorar parser/UX y añadir test/regresión.

### `IngestionErrorTagCardinalityHigh` (info)
Significado: el tag `error` está generando demasiados valores distintos (riesgo de cardinalidad en Prometheus).

Qué mirar:

```promql
enterpriseiq:import_error_series
enterpriseiq:universal_error_series
topk(20, sum(increase(ingestion_universal_analyze_count_total{result="failed"}[1h])) by (error))
topk(20, sum(increase(ingestion_import_process_count_total{result="failed"}[1h])) by (error))
```

Acciones típicas:
- Si aparece `other`: significa que el backend está capando tags por seguridad; revisa logs del backend para la causa real.
- Si aparecen muchos `http_4xx`: el problema es input/usuario (plantillas raras, tamaños, cabeceras).

## Silences (mantenimiento)

- UI: `http://localhost:9093` → **Silences** → crear silence por `alertname` o por `severity`.
- Ejemplo: silenciar todo `severity="info"` durante una ventana de import masiva.
