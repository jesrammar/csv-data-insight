import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { getAlerts, getDashboard, getReports } from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import Alert from '../components/ui/Alert'
import PageHeader from '../components/ui/PageHeader'
import { formatMoney } from '../utils/format'

function ym(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

export default function ClientHomePage() {
  const { id: companyId, plan } = useCompanySelection()

  const to = ym(new Date())
  const from = ym(new Date(new Date().getFullYear(), new Date().getMonth() - 5, 1))

  const { data: dashboard } = useQuery({
    queryKey: ['client-home-dashboard', companyId, from, to],
    queryFn: () => getDashboard(companyId as number, from, to),
    enabled: !!companyId
  })

  const { data: reports } = useQuery({
    queryKey: ['client-home-reports', companyId],
    queryFn: () => getReports(companyId as number),
    enabled: !!companyId
  })

  const { data: alerts } = useQuery({
    queryKey: ['client-home-alerts', companyId],
    queryFn: () => getAlerts(companyId as number),
    enabled: !!companyId
  })

  const hasData = (dashboard as any)?.kpis?.length > 0
  const latest = (dashboard as any)?.kpis?.[(dashboard as any)?.kpis?.length - 1]
  const reportsCount = Array.isArray(reports) ? reports.length : 0
  const alertsCount = Array.isArray(alerts) ? alerts.length : 0

  return (
    <div>
      <PageHeader
        title="Resumen"
        subtitle="Lo importante hoy: caja, alertas e informes (sin entrar en detalles técnicos)."
        actions={<span className="badge">{(plan || 'BRONZE').toUpperCase()}</span>}
      />

      {!companyId ? (
        <Alert tone="warning" title="Falta seleccionar empresa">
          Selecciona una empresa arriba para ver el resumen.
        </Alert>
      ) : null}

      <div className="grid section">
        <div className="card">
          <div className="mini-row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
            <h3 style={{ margin: 0 }}>Caja</h3>
            <Link className="badge" to="/cash">
              Abrir
            </Link>
          </div>
          {!hasData ? (
            <div className="empty" style={{ marginTop: 12 }}>
              Aún no hay datos. Tu consultora debe cargar el CSV/XLSX del periodo para calcular la caja.
            </div>
          ) : (
            <div style={{ marginTop: 12 }} className="grid">
              <div className="kpi">
                <h4>Entradas</h4>
                <strong>{formatMoney(latest?.inflows)}</strong>
              </div>
              <div className="kpi">
                <h4>Salidas</h4>
                <strong>{formatMoney(latest?.outflows)}</strong>
              </div>
              <div className="kpi">
                <h4>Neto</h4>
                <strong>{formatMoney(latest?.netFlow)}</strong>
              </div>
              <div className="kpi">
                <h4>Saldo fin</h4>
                <strong>{formatMoney(latest?.endingBalance)}</strong>
              </div>
            </div>
          )}
        </div>

        <div className="card">
          <div className="mini-row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
            <h3 style={{ margin: 0 }}>Alertas</h3>
            <Link className="badge" to="/alerts">
              Ver
            </Link>
          </div>
          <div style={{ marginTop: 12 }}>
            {!alertsCount ? (
              <div className="empty">Sin alertas por ahora.</div>
            ) : (
              <div className="kpi">
                <h4>Activas</h4>
                <strong>{alertsCount}</strong>
                <div className="upload-hint" style={{ marginTop: 8 }}>
                  Revisa primero las alertas de caja y anomalías.
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="card">
          <div className="mini-row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
            <h3 style={{ margin: 0 }}>Informes</h3>
            <Link className="badge" to="/reports">
              Abrir
            </Link>
          </div>
          <div style={{ marginTop: 12 }}>
            {!reportsCount ? (
              <div className="empty">No hay informes todavía.</div>
            ) : (
              <div className="kpi">
                <h4>Disponibles</h4>
                <strong>{reportsCount}</strong>
                <div className="upload-hint" style={{ marginTop: 8 }}>
                  Lista de informes mensuales listos para compartir.
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="card">
          <div className="mini-row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
            <h3 style={{ margin: 0 }}>Qué hago ahora</h3>
            <Link className="badge" to="/help">
              Guía
            </Link>
          </div>
          <div style={{ marginTop: 12 }} className="bar-stack">
            <div className="kpi" style={{ padding: 12 }}>
              <div className="upload-hint">Prioridad 1</div>
              <strong>Evitar sustos de caja</strong>
              <div className="upload-hint" style={{ marginTop: 6 }}>
                Revisa neto del mes y alertas. Si el neto baja, actúa en gastos fijos y cobros.
              </div>
            </div>
            <div className="kpi" style={{ padding: 12 }}>
              <div className="upload-hint">Prioridad 2</div>
              <strong>Preparar el informe</strong>
              <div className="upload-hint" style={{ marginTop: 6 }}>
                Asegura que el periodo está importado y el informe mensual generado.
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
