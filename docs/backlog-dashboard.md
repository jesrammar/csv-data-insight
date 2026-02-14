# Backlog tecnico - Dashboard EnterpriseIQ (Bronce/Oro/Plan 3)

Fecha: 2026-02-14

## Objetivo
Implementar los modulos observados en la maqueta: historicos, tendencias, estructura de costes, lineas de negocio, presupuesto vs real y previsiones.

## Epicas
1. Datos y modelo financiero extendido
2. Importacion de datos no contables (reuniones/prevision)
3. Dashboard financiero (KPIs y graficos)
4. Presupuesto vs Real
5. Previsiones y tendencias
6. Reportes avanzados

## Tareas (MVP Bronce)
1. Modelo de datos
- Crear tablas: usiness_lines, cost_categories, udgets, orecasts, line_profits, company_finance_config.
- Migracion: V4__planning_models.sql.
- Entidades JPA correspondientes.

2. KPI historicos
- Endpoint: GET /api/companies/{companyId}/kpis?from=YYYY-MM&to=YYYY-MM.
- Devuelve series: inflows, outflows, net_flow, ending_balance.

3. Beneficio 5 anos
- Agregar KPI profit (o derivado de net_flow).
- Endpoint: GET /api/companies/{companyId}/profit-5y.

4. Tabla historica (beneficio, deuda, saldo)
- Definir origen de deuda y saldo.
- Endpoint: GET /api/companies/{companyId}/history-table?from&to.

## Tareas (Oro)
5. Presupuesto vs Real (trimestral)
- CRUD basico de budgets.
- Endpoint: GET /api/companies/{companyId}/budget-vs-real?year=YYYY.
- Calculo por trimestre (1Q..4Q).

6. Estructura de costes
- CRUD de categorias de coste.
- Endpoint: GET /api/companies/{companyId}/cost-structure?period=YYYY-MM.

7. Ratios de costes/ventas
- Calcular ratios (%).
- Endpoint: GET /api/companies/{companyId}/cost-ratios?from&to.

## Tareas (Plan 3 / avanzado)
8. Lineas de negocio
- CRUD de lineas de negocio.
- Endpoint: GET /api/companies/{companyId}/line-profits?period=YYYY-MM.

9. Punto muerto
- Configuracion en company_finance_config.
- Endpoint: GET /api/companies/{companyId}/break-even?period=YYYY-MM.

10. Previsiones
- CRUD de forecasts y fuente (Cegid/Reuniones).
- Endpoint: GET /api/companies/{companyId}/forecast?from&to.

11. Tendencia plurianual
- Endpoint: GET /api/companies/{companyId}/trend?from&to.

## Dependencias
- Definir origen y formato para datos de reuniones.
- Alinear definicion de "beneficio", "deuda" y "saldo" con negocio.

## Notas
- Planes deben afectar visibilidad de endpoints y modulos.
- Toda respuesta debe filtrar por companyId y rol.