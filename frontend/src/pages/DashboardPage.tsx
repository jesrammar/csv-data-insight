import { useQuery } from '@tanstack/react-query'
import { getDashboard } from '../api'
import KpiChart from '../components/KpiChart'

function getSelectedCompanyId(): number | null {
  const value = localStorage.getItem('companyId')
  return value ? Number(value) : null
}

function formatPeriod(date: Date) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

function lastMonths(count: number) {
  const months: string[] = []
  const today = new Date()
  for (let i = count - 1; i >= 0; i--) {
    const d = new Date(today.getFullYear(), today.getMonth() - i, 1)
    months.push(formatPeriod(d))
  }
  return months
}

export default function DashboardPage() {
  const companyId = getSelectedCompanyId()
  const months = lastMonths(6)
  const from = months[0]
  const to = months[months.length - 1]

  const { data, error } = useQuery({
    queryKey: ['dashboard', companyId, from, to],
    queryFn: () => getDashboard(companyId as number, from, to),
    enabled: !!companyId
  })

  const latest = data?.kpis?.[data?.kpis.length - 1]
  const chartPoints = (data?.kpis || []).map((k: any) => ({ label: k.period, value: Number(k.netFlow) }))

  return (
    <div>
      <div className="hero">
        <div>
          <h1 className="hero-title">Dashboard financiero</h1>
          <p className="hero-sub">KPIs del periodo actual y últimos 6 meses.</p>
        </div>
        <div className="card soft">
          <h3 style={{ marginTop: 0 }}>Periodo actual</h3>
          <p className="hero-sub">{to}</p>
          {latest ? (
            <div className="kpi">
              <h4>Net Flow</h4>
              <strong>{latest.netFlow}</strong>
            </div>
          ) : (
            <div className="empty">Aún no hay datos. Sube un CSV.</div>
          )}
        </div>
      </div>

      {error && <p className="error">{String((error as any).message)}</p>}

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>KPIs clave</h3>
          <div className="grid">
            <div className="kpi">
              <h4>Inflows</h4>
              <strong>{latest?.inflows ?? '-'}</strong>
            </div>
            <div className="kpi">
              <h4>Outflows</h4>
              <strong>{latest?.outflows ?? '-'}</strong>
            </div>
            <div className="kpi">
              <h4>Net Flow</h4>
              <strong>{latest?.netFlow ?? '-'}</strong>
            </div>
            <div className="kpi">
              <h4>Ending Balance</h4>
              <strong>{latest?.endingBalance ?? '-'}</strong>
            </div>
          </div>
        </div>
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Evolución mensual</h3>
          {!data?.kpis?.length ? (
            <div className="empty">Sin datos todavía. Importa un CSV.</div>
          ) : (
            <div style={{ marginBottom: 16 }}>
              <KpiChart title="Net Flow (últimos 6 meses)" points={chartPoints} />
            </div>
          )}
          {!data?.kpis?.length ? null : (
            <table className="table">
              <thead>
                <tr>
                  <th>Periodo</th>
                  <th>Inflows</th>
                  <th>Outflows</th>
                  <th>Net Flow</th>
                  <th>Ending Balance</th>
                </tr>
              </thead>
              <tbody>
                {(data?.kpis || []).map((k: any) => (
                  <tr key={k.period}>
                    <td>{k.period}</td>
                    <td>{k.inflows}</td>
                    <td>{k.outflows}</td>
                    <td>{k.netFlow}</td>
                    <td>{k.endingBalance}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  )
}
