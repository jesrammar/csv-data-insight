type RoleRow = { role: string; goal: string; canSee: string; canOperate: string }

export const ROLE_MATRIX: RoleRow[] = [
  {
    role: 'CLIENTE',
    goal: 'Entender y decidir rápido',
    canSee: 'Resumen, Caja, Alertas, Informes, Ayuda',
    canOperate: 'No sube datos ni ejecuta automatizaciones'
  },
  {
    role: 'CONSULTOR',
    goal: 'Operar para varias empresas',
    canSee: 'Todo lo anterior + módulos de consultoría (según plan)',
    canOperate: 'Importaciones, Tribunal, Universal, Automatización, Recomendaciones'
  },
  {
    role: 'ADMIN',
    goal: 'Administrar plataforma',
    canSee: 'Todo',
    canOperate: 'Gestión de empresas + permisos totales'
  }
]

export default function RoleCapabilitiesTable({
  title = 'Roles (permisos funcionales)',
  subtitle = 'El plan aplica por empresa; el rol define qué pantallas puede operar cada usuario.',
  compact = false
}: {
  title?: string
  subtitle?: string
  compact?: boolean
}) {
  return (
    <section className={`card section ${compact ? 'soft' : ''}`.trim()}>
      <div className="pricing-matrix-head">
        <h3 style={{ marginTop: 0 }}>{title}</h3>
        {subtitle ? <p style={{ marginTop: 6 }}>{subtitle}</p> : null}
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
