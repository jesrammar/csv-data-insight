# Modelo de datos Power BI (estrella)

## Dimensiones
- DimCliente: cliente, cif, administrador, dni_nie, tipo_cliente, gestor, f_alta, f_baja, f_pago
- DimGestor: gestor (y atributos si existen)
- DimFecha: calendario

## Hechos
- FactCliente: minutas, carga_de_trabajo, pct_contabilidad, cont_modelos, is_irpf, ddcc, libros, promedio
- FactActividadAnual: cliente_id, anio, n_as (numero de asiento contable)

## Relaciones
- FactCliente[cliente_id] -> DimCliente[cliente_id]
- FactActividadAnual[cliente_id] -> DimCliente[cliente_id]
- DimCliente[gestor] -> DimGestor[gestor]
- DimCliente[f_alta] -> DimFecha[date] (relacion inactiva)
- DimCliente[f_baja] -> DimFecha[date] (relacion inactiva)

## Nota
Si no existe un id natural, usar row_id o crear un hash de CIF + cliente.
