# Limpieza CSV (excel madre)

## Objetivo
Normalizar el CSV con separador `;`, decimales en coma y meses en espanol, para cargar en Power BI o al backend.

## Reglas de limpieza
- Separador: `;`.
- Quitar primera columna vacia (o renombrarla a `row_id`).
- Normalizar encabezados a `snake_case`.
- Numericos con coma: convertir `.` (miles) y `,` (decimales) a numero.
- Valores `-` o vacio -> `null`.
- Fechas `F/ALTA` y `F/BAJA`: parsear `mes-yy` (es) y fijar dia `01`.
- Campos SI/NO -> booleano.
- Estados compuestos (ej: `SI-NEGATIVO`, `SI-PDTE DE PAGO`) -> booleano `si` + campo `estado` si se requiere.
- Trim de espacios en textos.

## Encabezados normalizados (propuesta)
- `row_id`
- `tipo_cliente`
- `cliente`
- `cif`
- `administrador`
- `dni_nie`
- `minutas`
- `f_alta`
- `f_baja`
- `f_pago`
- `gestor`
- `cont_modelos`
- `is_irpf`
- `ddcc`
- `libros`
- `carga_de_trabajo`
- `pct_contabilidad`
- `n_as_2015` ... `n_as_2024`
- `promedio`

## Nota sobre N AS
`N AS YYYY` se interpreta como numero de asiento contable por anio.
