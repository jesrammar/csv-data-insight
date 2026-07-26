type RoleRow = { role: string; goal: string; canSee: string; canOperate: string }

export const ROLE_MATRIX: RoleRow[] = [
  {
    role: 'CLIENTE',
    goal: 'Consultar y decidir con contexto',
    canSee: 'Resumen, Caja, Alertas, Informes, Ayuda',
    canOperate: 'No sube datos ni ejecuta automatizaciones; consume lectura publicada'
  },
  {
    role: 'CONSULTOR',
    goal: 'Operar una cartera de empresas',
    canSee: 'Todo lo anterior + módulos de consultoría (según plan)',
    canOperate: 'Importaciones, Tribunal, Universal, Automatización, Recomendaciones'
  },
  {
    role: 'ADMIN',
    goal: 'Gobernar la operación de la consultora',
    canSee: 'Todo',
    canOperate: 'Gestión de empresas gestionadas, usuarios y permisos'
  }
]

export default function RoleCapabilitiesTable({
  title = 'Roles (permisos funcionales)',
  subtitle = 'La consultora opera con ADMIN y CONSULTOR; CLIENTE queda como acceso opcional de lectura por empresa gestionada.',
  compact = false
}: {
  title?: string
  subtitle?: string
  compact?: boolean
}) {
  return (
    <section className={`card section ${compact ? 'soft' : ''}`.trim()}>
      <div className="pricing-matrix-head">
        <h3 className="h3-reset">{title}</h3>
        {subtitle ? <p className="m-0 mt-6">{subtitle}</p> : null}
      </div>
      <div className="pricing-table-wrap">
        <table className="table pricing-table">
          <thead>
            <tr>
              <th>Rol</th>
              <th>Objetivo</th>
              <th>Puede ver</th>
              <th>Puede operar</th>
            </tr>
          </thead>
          <tbody>
            {ROLE_MATRIX.map((row) => (
              <tr key={row.role}>
                <td>
                  <strong>{row.role}</strong>
                </td>
                <td>{row.goal}</td>
                <td>{row.canSee}</td>
                <td>{row.canOperate}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
