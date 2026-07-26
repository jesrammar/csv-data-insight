# Flujo anual de presupuesto

## Objetivo

Tratar el presupuesto anual como un flujo de producto propio, distinto del cierre mensual de caja.

No es un módulo más. Es una ruta consultiva para convertir una hoja anual en:

- lectura estructurada del ejercicio,
- detección de concentración y estacionalidad,
- validación de calidad del modelo,
- informe anual presentable,
- base para comparar presupuesto vs real.

## Qué entra

Entrada típica:

- hoja anual de presupuesto,
- cuenta de explotación prevista,
- tesorería prevista,
- hipótesis incrustadas en el Excel.

## Qué sale

Salida útil para consultora:

- resumen anual ejecutivo,
- peor mes y mejor mes del ejercicio,
- top drivers de coste e ingreso,
- señales de concentración,
- señales de calidad del archivo,
- informe anual exportable en PDF.

## Workflow propuesto

1. Cargar XLSX anual en `Universal`.
2. Detectar si el archivo es de presupuesto anual.
3. Validar estructura mínima:
   meses, partidas, totales, consistencia básica.
4. Generar lectura anual:
   margen, concentración, estacionalidad, huecos.
5. Publicar informe anual.
6. Dejar preparado el siguiente paso:
   comparativa `real vs presupuesto`.

## Qué no debe mezclarse

No mezclar este flujo con:

- cierre mensual operativo,
- bandeja de excepciones de imports de caja,
- workflow de cartera o Tribunal.

La lógica anual responde a planificación y narrativa del ejercicio.
La lógica mensual responde a operación y control recurrente.

## Estado recomendado en producto

Estado actual deseable:

- `presupuesto detectado`
- `estructura validada`
- `lectura anual generada`
- `informe anual listo`

Estado futuro valioso:

- `comparativa real vs presupuesto disponible`
- `escenario alternativo cargado`
- `desviaciones explicadas`

## Valor para el TFG

Este flujo añade mucho valor porque demuestra que el software no solo automatiza reporting mensual, sino que también transforma Excel de planificación en servicio consultivo reutilizable.

En términos de producto:

- mensual = control operativo,
- anual = planificación y decisión,
- ambos juntos = cuadro de mando consultivo más serio.
