# ADR-002: Pricing por madurez operativa

## Estado

Aprobada

## Contexto

EnterpriseIQ se está posicionando como plataforma operativa para consultoras, no como simple visor de dashboards.

La duda a fijar era esta:

- si los planes deben diferenciarse por número de módulos o pantallas
- o si deben diferenciarse por el nivel de valor operativo que recibe la consultora

El riesgo del primer enfoque es claro:

- genera catálogos difíciles de explicar
- incentiva a añadir más UI en lugar de más valor real
- hace que el producto parezca una suma de features, no una ruta de madurez

## Decisión

Se fija que el pricing se define por madurez operativa, no por cantidad de módulos.

La progresión oficial de valor será:

- `BRONZE`: ordenar y visualizar el dato
- `SILVER`: operar el cierre y controlar incidencias
- `GOLD`: convertir el dato en lectura consultiva y comparativa anual
- `PLATINUM`: automatizar y escalar la operación multiempresa

## Criterio de asignación

Cada funcionalidad nueva debe asignarse al plan según uno o varios de estos ejes:

- automatización operativa
- profundidad analítica
- calidad del entregable
- escalabilidad de la consultora

No se debe justificar un plan por:

- tener más pantallas
- mostrar más widgets
- exponer más controles técnicos sin impacto claro en el servicio

## Definición de planes

### BRONZE

- carga manual
- lectura ejecutiva básica
- caja / liquidez
- cierre mensual simple
- informe mensual estándar

### SILVER

- workflow oficial de cierre mensual
- bandeja de excepciones
- reproceso y validaciones más fuertes
- seguimiento consultivo básico
- control operativo de cartera cuando aplique

### GOLD

- Universal avanzado
- plan anual y presupuesto
- comparativa real vs presupuesto
- vistas guardadas reutilizables
- lectura consultiva más profunda
- entregable anual más potente

### PLATINUM

- recepción automática de ficheros
- clasificación automática
- pipeline avanzado con reglas
- automatismos de extremo a extremo
- operación multiempresa más escalable

## Consecuencias

### Positivas

- hace el catálogo mucho más entendible
- alinea el roadmap con valor real para consultoría
- evita inflar la UI con capacidades secundarias
- refuerza la narrativa del TFG: de dashboard a sistema operativo consultivo

### Limitaciones asumidas

- algunas capacidades hoy todavía están técnicamente repartidas de forma incompleta entre planes
- la UI y los textos todavía deben reflejar esta lógica con más consistencia

## Implicación práctica inmediata

Desde ahora:

- los bloqueos por plan deben explicarse en lenguaje de valor, no de módulo
- el frontend debe enseñar menos “features sueltas” y más capacidades por nivel
- cualquier nueva funcionalidad debe colocarse en el plan según madurez operativa

## Documentos relacionados

- Ver [adr-001-modelo-dominio-consultora.md](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/docs/adr-001-modelo-dominio-consultora.md)
- Ver [capacidades-roles-planes.md](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/docs/capacidades-roles-planes.md)
