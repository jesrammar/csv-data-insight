# Demo Script (3–5 min)

## 0. Objetivo (10s)
Mostrar un flujo completo de datos listo para BI: token → SAS → datasets → KPIs → exportación.

## 1. Panel de demo (40s)
Abrir `http://localhost:8080/demo`.
- Enseñar **Estado del servicio**.
- Clicar **Flujo completo** y explicar el pipeline.

## 2. Datos de negocio (40s)
Mostrar **Clientes** y **KPIs**.
- Segmentar por país y segmento.
- Enseñar alertas de calidad.

## 3. BI Ready (60s)
Enseñar endpoints para Power BI:
- `/bi/customers`
- `/bi/kpis`
- `/export/kpis.csv`

## 4. Swagger local (30s)
Abrir `http://localhost:8080/swagger-ui/index.html`.
- Mostrar que la API está documentada.

## 5. Cierre (10s)
“Con credenciales reales, este mismo flujo conecta a producción sin cambiar arquitectura.”
