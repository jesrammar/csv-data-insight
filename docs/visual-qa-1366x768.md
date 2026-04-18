# QA visual (1366×768) + densidad (Compacta/Cómoda)

## Setup
- Navegador: Chrome/Edge.
- Viewport: `1366 × 768` (DevTools → Toggle device toolbar → Responsive).
- Probar en ambas densidades: botón `Espaciado: Cómodo/Compacto` (topbar).

## Qué revisar (checklist)
- **Topbar**
  - No hay elementos cortados (selector empresa, botones Ajustes/Espaciado/Salir).
  - No aparece scroll horizontal del `body`.
- **Cards / secciones**
  - No hay solapes ni “tarjetas dentro” saliéndose del contenedor.
  - En modo **Compacto**, el contenido sigue legible (inputs/botones no se pisan).
- **Grids**
  - Reflow correcto a 1–2 columnas cuando haga falta (sin columnas “aplastadas”).
- **Tablas**
  - Si no caben, hay scroll horizontal en el wrapper (no en toda la página).
  - Headers/filas no rompen el layout por textos largos.
- **Charts**
  - Tooltips muestran valores (aunque sea en texto plano) y no tapan controles.
  - No hay saltos raros al cambiar densidad.

## Rutas clave a testear (mínimo)
- `/overview` (Vista ejecutiva)
- `/dashboard` (Caja)
- `/imports` (Cargar datos)
- `/reports` (Entregables/Reportes)
- `/tribunal`
- `/universal`
- `/budget`
- `/alerts` (cliente)
- `/home` + `/help` (cliente)
- `/admin/users` + `/admin/storage` (admin)

## Señales de “bug visual” (reportar con screenshot)
- Scroll horizontal del `body`.
- Botón/tabla que se sale del card y queda “cortado” por el borde.
- Textos que no envuelven y empujan el layout (IDs/rutas).
- Inconsistencia entre **Cómodo** y **Compacto** (mismos módulos con alturas/anchos incoherentes).

