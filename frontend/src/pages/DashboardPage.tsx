import { useQuery } from '@tanstack/react-query'
import { getDashboard } from '../api'

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

  return (
    <div>
      <h2>Dashboard</h2>
      {error && <p className="error">{String((error as any).message)}</p>}
      <div className="card">
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
      </div>
    </div>
  )
}
