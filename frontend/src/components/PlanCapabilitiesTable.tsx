type MatrixRow = { item: string; bronze: string; gold: string; platinum: string }

const CHECK = '✅'
const CROSS = '❌'

export const CAPABILITY_MATRIX: MatrixRow[] = [
  { item: 'KPIs de caja (in/out/net/saldo)', bronze: CHECK, gold: CHECK, platinum: CHECK },
  { item: 'Histórico recomendado', bronze: '6 meses', gold: '12 meses', platinum: '24 meses' },
  { item: 'Alertas', bronze: CHECK, gold: CHECK, platinum: CHECK },
  { item: 'Informes mensuales (HTML)', bronze: CHECK, gold: CHECK, platinum: '✅ + consultivo' },
  { item: 'Tribunal (cumplimiento)', bronze: CROSS, gold: CHECK, platinum: CHECK },
  { item: 'Drill-down transacciones + analítica', bronze: CROSS, gold: CHECK, platinum: CHECK },
  { item: 'Export transacciones (CSV)', bronze: CROSS, gold: CROSS, platinum: CHECK },
  { item: 'Export Power BI (ZIP) con detalle', bronze: CROSS, gold: CROSS, platinum: CHECK },
  { item: 'Universal: análisis + preview XLSX', bronze: CHECK, gold: CHECK, platinum: CHECK },
  { item: 'Universal: correlaciones', bronze: CROSS, gold: CHECK, platinum: CHECK },
  { item: 'Universal: CSV normalizado + preview filas', bronze: CROSS, gold: CROSS, platinum: CHECK },
  { item: 'Asistente (chat)', bronze: CROSS, gold: CROSS, platinum: CHECK },
  { item: 'Soporte objetivo', bronze: '48h', gold: '12h', platinum: '4h' }
]

export default function PlanCapabilitiesTable({
  title = 'Comparativa por plan',
  subtitle = 'Diferencias visibles y medibles en análisis y asesoramiento.',
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
              <th>Capacidad</th>
              <th>Bronze</th>
              <th>Gold</th>
              <th>Platinum</th>
            </tr>
          </thead>
          <tbody>
            {CAPABILITY_MATRIX.map((row) => (
              <tr key={row.item}>
                <td>{row.item}</td>
                <td>{row.bronze}</td>
                <td>{row.gold}</td>
                <td>{row.platinum}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
