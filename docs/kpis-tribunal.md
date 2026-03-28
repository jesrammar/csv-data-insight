# KPIs tribunal (implementado)

Este documento describe lo que calcula actualmente el backend en `TribunalImportService.getSummary()`.

## Acceso / plan
- Endpoint: `GET /api/companies/{companyId}/tribunal/summary`
- Requiere plan **GOLD o superior** (controlado en backend).

## Base (TribunalKpiDto)
- **Total clientes**: `clients.size()`
- **Clientes activos**: `f_baja = null`
- **% bajas**: `(total - activos) / total * 100`
- **Minuta media**: media de `minutas` (solo clientes con `minutas != null`)
- **Carga media**: media de `carga_de_trabajo` (solo clientes con `carga_de_trabajo != null`)

## Cumplimiento
- **% contabilidad**: `cont_modelos = true` sobre total
- **% fiscal**: `is_irpf_ok = true` AND `ddcc_ok = true` AND `libros_ok = true` sobre total
- Nota: actualmente el resumen devuelve los % de contabilidad y fiscal, pero no un % global de “incumplimientos”.

## Riesgo/alertas
- Se genera una lista de **hasta 50 clientes** con incidencias (`TribunalRiskDto`).
- Reglas actuales (por cliente):
  - `CONT/MODELOS=NO` si `cont_modelos == false`
  - `IS/IRPF` si `is_irpf_ok == false` o el status contiene `PDTE` o `NEGATIVO`
  - `DDCC` si `ddcc_ok == false` o el status contiene `PDTE` o `NEGATIVO`
  - `LIBROS` si `libros_ok == false` o el status contiene `PDTE` o `NEGATIVO`
- Nota: “minutas = 0 y activo” no está implementado como riesgo hoy (se puede añadir).

## Actividad (numero de asientos)
- **Asientos totales por año**: suma de `n_as_YYYY` agregada por `company_id`.
- Años incluidos actualmente: **2015–2024** (fijo en código).
- Nota: promedio/tendencia no están implementados en el resumen hoy (se puede añadir).

## Gestion
- Se devuelve un ranking por gestor (`TribunalGestorDto`):
  - **Clientes por gestor** (total y activos)
  - **Minuta media por gestor**
  - **Carga media por gestor**
- Si el cliente no tiene gestor, se agrupa en `SIN GESTOR`.
