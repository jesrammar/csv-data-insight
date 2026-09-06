# Política de datos del repositorio

Este repositorio, con independencia de su visibilidad, solo puede contener código, configuración sin secretos, documentación y datos sintéticos.

## Datos permitidos

- Fixtures creados expresamente para pruebas, sin relación con personas, clientes u organismos reales.
- Plantillas vacías o con ejemplos claramente ficticios.
- Identificadores, importes y fechas inventados que no reproduzcan conjuntos de datos reales.

Los fixtures de backend se guardan en `backend/src/test/resources/fixtures/`. Las plantillas descargables de la aplicación se guardan en `frontend/public/samples/` y deben cumplir la misma regla.

## Datos prohibidos

- Exportaciones de producción o de herramientas de terceros.
- Datos personales, financieros, laborales, tributarios o contractuales reales.
- Documentos oficiales, aunque se renombren como ejemplos.
- Secretos, credenciales, tokens, copias de seguridad e informes generados a partir de datos reales.
- Contenido descomprimido de CSV, XLSX, ZIP o PDF usado durante una investigación local.

## Antes de hacer commit

Ejecuta:

```bash
node scripts/check-repository-hygiene.mjs
```

Revisa también los binarios nuevos manualmente. El control automático bloquea rutas y huellas conocidas, pero no puede demostrar por sí solo que un conjunto de datos sea sintético.

Si se publica información no permitida, hay que detener la exposición, retirar todas las copias y derivados, reescribir las referencias Git afectadas y revisar si procede rotar credenciales o activar el proceso interno de gestión de incidentes.
