# csv-data-insight

Framework para **ingesta, limpieza, normalización y visualización** de datos a partir de **CSV heterogéneos**.  
Permite al usuario subir cualquier dataset en CSV, transformarlo a un formato estándar, generar métricas automáticas y exponerlo a herramientas de BI como **Power BI, Superset o Metabase**.  
Incluye de forma opcional un **chatbot en lenguaje natural** para consultas sobre los datos.

---

## 🚀 Características

- 📂 Ingesta de múltiples CSV de distintos contextos.  
- 🧹 Limpieza y normalización mediante reglas configurables.  
- 📊 Generación automática de métricas y gráficos.  
- 📈 Cuadros de mando interactivos (React + Vega-Lite/ECharts).  
- 🔗 Integración directa con herramientas BI externas (PostgreSQL).  
- 🤖 Chat NL→SQL (opcional).

---

## 🧱 Arquitectura

- **Backend (Java, Spring Boot):** orquesta datasets, mappings, calidad y persistencia en PostgreSQL.  
- **Microservicio (Python, FastAPI):** procesamiento de datos, profiling y generación de especificaciones de gráficos automáticos.  
- **Frontend (React):** interfaz ligera para carga de CSV, vista de calidad y dashboards.  
- **Base de datos (PostgreSQL):** almacenamiento en capas `raw`, `core`, `mart`, `audit`, `meta`.

---

## 📦 Quickstart

Clona el repositorio y levanta los servicios con Docker Compose:

```bash
git clone https://github.com/jesrammar/csv-data-insight.git
cd csv-data-insight
docker compose -f infra/docker-compose.yml up --build
