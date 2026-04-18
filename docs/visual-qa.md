# Visual QA (Responsive / Overflow)

Objetivo: detectar desbordes, cortes y densidad excesiva en pantallas tipo **1366×768**, en ambos modos **Cómodo** y **Compacto**.

## Preparación

- Arranca con Docker como siempre (`docker compose up`).
- Entra como:
  - **CONSULTOR** (para ver dashboards completos y acciones).
  - **CLIENTE** (para ver la vista simplificada).
- Prueba con **zoom 100%** y también 90%/110% (Chrome).

## Viewports a revisar

- Desktop: `1366×768` (prioridad)
- Desktop: `1440×900`
- Laptop: `1280×720`

## Densidad

- En `Topbar` → botón **Espaciado**:
  - `Cómodo`
  - `Compacto`

## Checklist por pantalla (claves)

### Global

- Topbar: no debe “empujar” el contenido ni romper a 2 líneas de forma rara.
- Sidebar: no debe generar scroll horizontal.
- Cards/sections: nada debe “salirse” del contenedor (especialmente tablas y listas).

### Consultoría

- `Vista ejecutiva` (`/overview`)
  - Cards en grid: sin recortes ni solapes.
- `Caja` (`/dashboard`)
  - Filtros y tablas: deben **wrappear** y/o tener **scroll horizontal** si hace falta.
- `Tribunal` (`/tribunal`)
  - Barra sticky de estado: no debe tapar contenido al hacer scroll.
  - Vista previa / tablas: scroll horizontal dentro del card si procede.
- `Universal` (`/universal/view/:id`)
  - KPI cards: grid estable (sin overflow).
  - Pivot mensual: tabla con scroll horizontal dentro del card.

### Cliente

- `Resumen` (`/home`)
- `Caja` (`/cash`)
- `Alertas` (`/alerts`)
- `Informes` (`/reports`)

## Señales de bug visual (para reportar)

- Scroll horizontal del navegador (casi siempre es overflow no controlado).
- Botones/inputs que se salen de su card.
- Tablas sin scroll que “empujan” el layout.
- Sticky elements que tapan contenido.

