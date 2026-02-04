package com.example.demo.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping
@ConditionalOnProperty(prefix = "cegid.mock", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MockCegidController {

    @GetMapping(value = "/tokenprovider/Token", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getTokenProvider() {
        String body = """
            {
              "accessToken": "mock-access-token",
              "expireIn": "3600",
              "tenantId": "99980016"
            }
            """;
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/storage/api/V1/storages/GetSASTokenLR", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getSasTokenLR() {
        String body = """
            {
              "blobServiceUri": "https://example.blob.core.windows.net/",
              "containerName": "99980016",
              "sasToken": "?sv=2019-07-07&sr=c&sig=mock&se=2030-01-01T00:00:00Z&sp=rl",
              "blobName": ""
            }
            """;
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/storage/api/V1/storages/GetSASTokenLRD", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getSasTokenLRD() {
        String body = """
            {
              "blobServiceUri": "https://example.blob.core.windows.net/",
              "containerName": "99980016",
              "sasToken": "?sv=2019-07-07&sr=c&sig=mock&se=2030-01-01T00:00:00Z&sp=rdl",
              "blobName": ""
            }
            """;
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/datasource/api/V2/datasources/tenant/{providerId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getDatasourcesByTenantAndProvider(@PathVariable String providerId) {
        String body = """
            {
              "continuationRequest": "",
              "data": [
                {
                  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "name": "Demo datasource",
                  "tenantId": "99980016",
                  "displayPath": "Demo/Path",
                  "providerId": "%s",
                  "queryLanguage": "SQL",
                  "providerConnectionString": "Server=demo;Database=demo"
                }
              ]
            }
            """.formatted(providerId);
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/datasource/api/V1/foldersCollections/tenant/{providerId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getFoldersCollectionsByTenant(@PathVariable String providerId) {
        String body = """
            [
              {
                "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "name": "Demo collection",
                "tenantId": "99980016",
                "dataSourceListId": [],
                "providerId": "%s"
              }
            ]
            """.formatted(providerId);
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/demo/flow", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> demoFlow() {
        String body = """
            {
              "tokenProvider": {
                "accessToken": "mock-access-token",
                "expireIn": "3600",
                "tenantId": "99980016"
              },
              "sasToken": {
                "blobServiceUri": "https://example.blob.core.windows.net/",
                "containerName": "99980016",
                "sasToken": "?sv=2019-07-07&sr=c&sig=mock&se=2030-01-01T00:00:00Z&sp=rl",
                "blobName": ""
              },
              "datasources": {
                "continuationRequest": "",
                "data": [
                  {
                    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                    "name": "Demo datasource",
                    "tenantId": "99980016",
                    "displayPath": "Demo/Path",
                    "providerId": "DEMO",
                    "queryLanguage": "SQL",
                    "providerConnectionString": "Server=demo;Database=demo"
                  }
                ]
              }
            }
            """;
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/demo/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> demoHealth() {
        String body = """
            {
              "status": "ok",
              "service": "csv-data-insight",
              "mode": "mock",
              "timestamp": "2026-02-04T16:40:00Z"
            }
            """;
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/demo/kpis", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> demoKpis() {
        String body = """
            {
              "period": "2026-01",
              "tenants": 12,
              "datasets_ingested": 84,
              "quality_score_avg": 96.4,
              "alerts": 2,
              "last_refresh": "2026-02-04T16:45:00Z"
            }
            """;
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/demo/customers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> demoCustomers() {
        String body = """
            {
              "data": [
                {
                  "id": "C-1001",
                  "name": "Distribuciones Atlas",
                  "segment": "Retail",
                  "country": "ES",
                  "status": "active",
                  "last_activity": "2026-02-03"
                },
                {
                  "id": "C-1002",
                  "name": "Grupo Nébula",
                  "segment": "Hospitality",
                  "country": "PT",
                  "status": "active",
                  "last_activity": "2026-02-02"
                },
                {
                  "id": "C-1003",
                  "name": "Logística Sur",
                  "segment": "Logistics",
                  "country": "ES",
                  "status": "pending",
                  "last_activity": "2026-01-30"
                }
              ]
            }
            """;
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/bi/customers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> biCustomers() {
        String body = """
            [
              { "customer_id": "C-1001", "name": "Distribuciones Atlas", "segment": "Retail", "country": "ES", "status": "active", "last_activity": "2026-02-03" },
              { "customer_id": "C-1002", "name": "Grupo Nébula", "segment": "Hospitality", "country": "PT", "status": "active", "last_activity": "2026-02-02" },
              { "customer_id": "C-1003", "name": "Logística Sur", "segment": "Logistics", "country": "ES", "status": "pending", "last_activity": "2026-01-30" }
            ]
            """;
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/bi/kpis", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> biKpis() {
        String body = """
            [
              { "period": "2025-08", "tenants": 9, "datasets_ingested": 52, "quality_score_avg": 92.1, "alerts": 5 },
              { "period": "2025-09", "tenants": 10, "datasets_ingested": 61, "quality_score_avg": 93.4, "alerts": 4 },
              { "period": "2025-10", "tenants": 10, "datasets_ingested": 68, "quality_score_avg": 94.2, "alerts": 3 },
              { "period": "2025-11", "tenants": 11, "datasets_ingested": 73, "quality_score_avg": 95.0, "alerts": 3 },
              { "period": "2025-12", "tenants": 12, "datasets_ingested": 79, "quality_score_avg": 95.7, "alerts": 2 },
              { "period": "2026-01", "tenants": 12, "datasets_ingested": 84, "quality_score_avg": 96.4, "alerts": 2 }
            ]
            """;
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/bi/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> biAlerts() {
        String body = """
            [
              { "alert_id": "A-9001", "severity": "medium", "type": "schema_drift", "dataset": "sales_q1.csv", "detected_at": "2026-02-03T09:12:00Z" },
              { "alert_id": "A-9002", "severity": "low", "type": "missing_values", "dataset": "inventory.csv", "detected_at": "2026-02-02T16:45:00Z" }
            ]
            """;
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/export/customers.csv", produces = "text/csv")
    public ResponseEntity<String> exportCustomersCsv() {
        String body = "\uFEFF" + """
            customer_id,name,segment,country,status,last_activity
            C-1001,Distribuciones Atlas,Retail,ES,active,2026-02-03
            C-1002,Grupo Nébula,Hospitality,PT,active,2026-02-02
            C-1003,Logística Sur,Logistics,ES,pending,2026-01-30
            """;
        return csvAttachment("customers.csv", body);
    }

    @GetMapping(value = "/export/kpis.csv", produces = "text/csv")
    public ResponseEntity<String> exportKpisCsv() {
        String body = "\uFEFF" + """
            period,tenants,datasets_ingested,quality_score_avg,alerts
            2025-08,9,52,92.1,5
            2025-09,10,61,93.4,4
            2025-10,10,68,94.2,3
            2025-11,11,73,95.0,3
            2025-12,12,79,95.7,2
            2026-01,12,84,96.4,2
            """;
        return csvAttachment("kpis.csv", body);
    }

    private ResponseEntity<String> csvAttachment(String filename, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    @GetMapping(value = "/demo", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> demoPage() {
        String html = """
            <!doctype html>
            <html lang="es">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <title>CSV Data Insight — Demo</title>
                <style>
                  :root {
                    --bg: #0c0f17;
                    --panel: #121724;
                    --panel-2: #0f1420;
                    --accent: #3db6ff;
                    --accent-2: #8be0ff;
                    --text: #f3f6ff;
                    --muted: #9aa6bf;
                    --success: #4fd1c5;
                    --warning: #f6c453;
                    --stroke: rgba(255, 255, 255, 0.08);
                  }
                  * { box-sizing: border-box; }
                  body {
                    margin: 0;
                    font-family: "Space Grotesk", "Segoe UI", system-ui, sans-serif;
                    color: var(--text);
                    background:
                      radial-gradient(1200px 700px at 90% -15%, #1a2f55 0%, transparent 60%),
                      radial-gradient(900px 500px at -10% 5%, #2a1f55 0%, transparent 50%),
                      repeating-linear-gradient(135deg, rgba(255,255,255,0.02) 0 2px, transparent 2px 6px),
                      var(--bg);
                  }
                  header {
                    padding: 36px 24px 12px;
                    max-width: 1200px;
                    margin: 0 auto;
                  }
                  .brand {
                    display: flex;
                    align-items: center;
                    gap: 14px;
                    margin-bottom: 14px;
                  }
                  .logo {
                    width: 50px;
                    height: 50px;
                    border-radius: 12px;
                    background: rgba(255, 255, 255, 0.08);
                    border: 1px solid rgba(255, 255, 255, 0.12);
                    padding: 6px;
                  }
                  .brand-name {
                    font-size: 12px;
                    letter-spacing: 0.22em;
                    text-transform: uppercase;
                    color: var(--muted);
                    margin: 0;
                  }
                  .hero {
                    display: grid;
                    gap: 18px;
                    grid-template-columns: 1.3fr 0.7fr;
                    align-items: center;
                  }
                  @media (max-width: 980px) {
                    .hero { grid-template-columns: 1fr; }
                  }
                  .title {
                    font-size: clamp(30px, 3.4vw, 48px);
                    font-weight: 600;
                    letter-spacing: -0.02em;
                    margin: 0 0 10px;
                  }
                  .subtitle {
                    color: var(--muted);
                    font-size: 16px;
                    margin: 0 0 14px;
                    max-width: 640px;
                  }
                  .hero-actions {
                    display: flex;
                    gap: 10px;
                    flex-wrap: wrap;
                  }
                  .hero-card {
                    background: linear-gradient(160deg, #182038, #0f152a);
                    border-radius: 18px;
                    padding: 16px;
                    border: 1px solid var(--stroke);
                  }
                  .hero-metric {
                    display: grid;
                    grid-template-columns: repeat(3, 1fr);
                    gap: 12px;
                  }
                  .metric {
                    background: rgba(255, 255, 255, 0.04);
                    border: 1px solid var(--stroke);
                    border-radius: 12px;
                    padding: 12px;
                  }
                  .metric h4 {
                    margin: 0 0 4px;
                    font-size: 11px;
                    color: var(--muted);
                    font-weight: 600;
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                  }
                  .metric p {
                    margin: 0;
                    font-size: 20px;
                    font-weight: 700;
                  }
                  main {
                    max-width: 1200px;
                    margin: 0 auto;
                    padding: 20px 24px 48px;
                  }
                  .section-title {
                    font-size: 14px;
                    color: var(--muted);
                    text-transform: uppercase;
                    letter-spacing: 0.18em;
                    margin: 24px 0 10px;
                  }
                  .grid {
                    display: grid;
                    gap: 16px;
                    grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
                  }
                  .card {
                    background: linear-gradient(160deg, var(--panel), var(--panel-2));
                    border: 1px solid var(--stroke);
                    border-radius: 16px;
                    padding: 18px;
                    box-shadow: 0 12px 24px rgba(0, 0, 0, 0.28);
                  }
                  .card h3 {
                    margin: 0 0 8px;
                    font-size: 18px;
                  }
                  .pill {
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                    padding: 6px 10px;
                    border-radius: 999px;
                    background: rgba(79, 209, 197, 0.15);
                    color: var(--success);
                    font-size: 12px;
                    font-weight: 600;
                    letter-spacing: 0.02em;
                  }
                  .endpoint {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 12px;
                    padding: 12px 14px;
                    border-radius: 12px;
                    background: rgba(255, 255, 255, 0.04);
                    border: 1px solid var(--stroke);
                    margin-top: 10px;
                  }
                  .endpoint code {
                    color: var(--accent-2);
                    font-size: 13px;
                  }
                  .btn {
                    background: linear-gradient(135deg, var(--accent), #7cc4ff);
                    color: #08101f;
                    border: none;
                    border-radius: 10px;
                    padding: 8px 12px;
                    font-weight: 700;
                    cursor: pointer;
                  }
                  .btn.secondary {
                    background: transparent;
                    color: var(--text);
                    border: 1px solid var(--stroke);
                  }
                  .btn:active { transform: translateY(1px); }
                  .result {
                    margin-top: 10px;
                    font-size: 13px;
                    color: var(--muted);
                    white-space: pre-wrap;
                  }
                  .chart {
                    margin-top: 12px;
                    height: 120px;
                    border-radius: 12px;
                    background: linear-gradient(180deg, rgba(62, 166, 255, 0.15), rgba(62, 166, 255, 0.02));
                    position: relative;
                    overflow: hidden;
                  }
                  .bar {
                    position: absolute;
                    bottom: 0;
                    width: 14%;
                    background: linear-gradient(180deg, var(--accent), #93d5ff);
                    border-radius: 8px 8px 0 0;
                  }
                  .badge {
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                    border: 1px solid var(--stroke);
                    border-radius: 999px;
                    padding: 6px 10px;
                    font-size: 12px;
                    color: var(--muted);
                  }
                  footer {
                    color: var(--muted);
                    font-size: 12px;
                    text-align: center;
                    padding: 24px;
                  }
                </style>
              </head>
              <body>
                <header>
                  <div class="brand">
                    <img class="logo" src="/assets/biblioteca.png" alt="Biblioteca" />
                    <p class="brand-name">Biblioteca</p>
                    <span class="pill">DEMO — Mock Mode</span>
                  </div>
                  <div class="hero">
                    <div>
                      <h1 class="title">CSV Data Insight — Cegid Data Access</h1>
                      <p class="subtitle">Panel de demostración local sin credenciales. Ideal para mostrar flujo, calidad de datos y endpoints, y conectar a BI en minutos.</p>
                      <div class="hero-actions">
                        <a class="btn" href="/swagger-ui/index.html">Swagger Local</a>
                        <a class="btn secondary" href="/v3/api-docs">OpenAPI JSON</a>
                        <span class="badge">Power BI Ready</span>
                      </div>
                    </div>
                    <div class="hero-card">
                      <div class="hero-metric">
                        <div class="metric">
                          <h4>Calidad</h4>
                          <p>96.4%</p>
                        </div>
                        <div class="metric">
                          <h4>Datasets</h4>
                          <p>84</p>
                        </div>
                        <div class="metric">
                          <h4>Alertas</h4>
                          <p>2</p>
                        </div>
                      </div>
                      <div class="chart">
                        <div class="bar" style="left: 6%; height: 45%"></div>
                        <div class="bar" style="left: 24%; height: 62%"></div>
                        <div class="bar" style="left: 42%; height: 70%"></div>
                        <div class="bar" style="left: 60%; height: 85%"></div>
                        <div class="bar" style="left: 78%; height: 66%"></div>
                      </div>
                    </div>
                  </div>
                </header>
                <main>
                  <div class="section-title">Core Demo</div>
                  <div class="grid">
                    <div class="card">
                      <h3>Estado del servicio</h3>
                      <div class="endpoint">
                        <code>GET /demo/health</code>
                        <button class="btn" onclick="call('/demo/health', 'health')">Probar</button>
                      </div>
                      <div id="health" class="result"></div>
                    </div>
                    <div class="card">
                      <h3>Flujo completo</h3>
                      <div class="endpoint">
                        <code>GET /demo/flow</code>
                        <button class="btn" onclick="call('/demo/flow', 'flow')">Probar</button>
                      </div>
                      <div id="flow" class="result"></div>
                    </div>
                    <div class="card">
                      <h3>Token Provider</h3>
                      <div class="endpoint">
                        <code>GET /tokenprovider/Token</code>
                        <button class="btn" onclick="call('/tokenprovider/Token', 'token')">Probar</button>
                      </div>
                      <div id="token" class="result"></div>
                    </div>
                    <div class="card">
                      <h3>SAS Token (LR)</h3>
                      <div class="endpoint">
                        <code>GET /storage/api/V1/storages/GetSASTokenLR</code>
                        <button class="btn" onclick="call('/storage/api/V1/storages/GetSASTokenLR', 'sas')">Probar</button>
                      </div>
                      <div id="sas" class="result"></div>
                    </div>
                    <div class="card">
                      <h3>Datasources</h3>
                      <div class="endpoint">
                        <code>GET /datasource/api/V2/datasources/tenant/DEMO</code>
                        <button class="btn" onclick="call('/datasource/api/V2/datasources/tenant/DEMO', 'ds')">Probar</button>
                      </div>
                      <div id="ds" class="result"></div>
                    </div>
                    <div class="card">
                      <h3>KPIs Operativos</h3>
                      <div class="endpoint">
                        <code>GET /demo/kpis</code>
                        <button class="btn" onclick="call('/demo/kpis', 'kpis')">Probar</button>
                      </div>
                      <div id="kpis" class="result"></div>
                    </div>
                    <div class="card">
                      <h3>Clientes (Demo)</h3>
                      <div class="endpoint">
                        <code>GET /demo/customers</code>
                        <button class="btn" onclick="call('/demo/customers', 'customers')">Probar</button>
                      </div>
                      <div id="customers" class="result"></div>
                    </div>
                    <div class="card">
                      <h3>BI — Customers</h3>
                      <div class="endpoint">
                        <code>GET /bi/customers</code>
                        <button class="btn" onclick="call('/bi/customers', 'bi-customers')">Probar</button>
                      </div>
                      <div id="bi-customers" class="result"></div>
                    </div>
                    <div class="card">
                      <h3>BI — KPIs (histórico)</h3>
                      <div class="endpoint">
                        <code>GET /bi/kpis</code>
                        <button class="btn" onclick="call('/bi/kpis', 'bi-kpis')">Probar</button>
                      </div>
                      <div id="bi-kpis" class="result"></div>
                    </div>
                    <div class="card">
                      <h3>BI — Alertas</h3>
                      <div class="endpoint">
                        <code>GET /bi/alerts</code>
                        <button class="btn" onclick="call('/bi/alerts', 'bi-alerts')">Probar</button>
                      </div>
                      <div id="bi-alerts" class="result"></div>
                    </div>
                    <div class="card">
                      <h3>Exportación CSV</h3>
                      <div class="endpoint">
                        <code>GET /export/customers.csv</code>
                        <div>
                          <a class="btn" href="/export/customers.csv" download>Descargar</a>
                          <button class="btn secondary" onclick="call('/export/customers.csv', 'csv-customers')">Ver</button>
                        </div>
                      </div>
                      <div class="endpoint">
                        <code>GET /export/kpis.csv</code>
                        <div>
                          <a class="btn" href="/export/kpis.csv" download>Descargar</a>
                          <button class="btn secondary" onclick="call('/export/kpis.csv', 'csv-kpis')">Ver</button>
                        </div>
                      </div>
                      <div id="csv-customers" class="result"></div>
                      <div id="csv-kpis" class="result"></div>
                    </div>
                  </div>
                </main>
                <footer>Demo local — 04 Feb 2026 · Spring Boot Mock</footer>
                <script>
                  async function call(path, targetId) {
                    const el = document.getElementById(targetId);
                    el.textContent = "Cargando...";
                    try {
                      const res = await fetch(path);
                      const txt = await res.text();
                      el.textContent = txt;
                    } catch (e) {
                      el.textContent = "Error al llamar al endpoint.";
                    }
                  }
                </script>
              </body>
            </html>
            """;
        return new ResponseEntity<>(html, HttpStatus.OK);
    }
}
