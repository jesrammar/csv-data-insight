import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { getAlerts, getDashboard, getReports } from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import Alert from '../components/ui/Alert'
import PageHeader from '../components/ui/PageHeader'
import QuickStartClient from '../components/QuickStartClient'
import Button from '../components/ui/Button'
import { formatMoney } from '../utils/format'

function ym(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

export default function ClientHomePage() {
  const { id: companyId, plan } = useCompanySelection()
  const navigate = useNavigate()

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
  const prev = (dashboard as any)?.kpis?.length >= 2 ? (dashboard as any)?.kpis?.[(dashboard as any)?.kpis?.length - 2] : null
  const reportsCount = Array.isArray(reports) ? reports.length : 0
  const alertsCount = Array.isArray(alerts) ? alerts.length : 0

  const cashStatus = (() => {
    if (!hasData) return { label: 'Caja: sin datos', tone: 'neutral' as const }
    const net = Number(latest?.netFlow || 0)
    const bal = Number(latest?.endingBalance || 0)
    const prevBal = prev ? Number(prev?.endingBalance || 0) : null
    const balDown = prevBal !== null && bal < prevBal
    if (bal < 0) return { label: 'Caja: Rojo', tone: 'danger' as const }
    if (net < 0 && balDown) return { label: 'Caja: Amarillo', tone: 'warning' as const }
    if (net < 0) return { label: 'Caja: Amarillo', tone: 'warning' as const }
    return { label: 'Caja: Verde', tone: 'success' as const }
  })()

  const todayAction = (() => {
    if (!hasData) {
      if (alertsCount) return { label: 'Ver alertas', route: '/alerts', hint: 'Hay alertas activas. Empieza por ahí.' }
      if (reportsCount) return { label: 'Ver informes', route: '/reports', hint: 'Ya tienes informes disponibles para revisar/compartir.' }
      return { label: 'Ver guía', route: '/help', hint: 'Primero tu consultora debe cargar datos.' }
    }
    if (cashStatus.tone === 'danger') {
      return {
        label: 'Actuar en caja',
        route: alertsCount ? '/alerts' : '/cash',
        hint: alertsCount ? 'Empieza por las alertas críticas.' : 'Revisa salidas y cobros pendientes.'
      }
    }
    if (cashStatus.tone === 'warning') return { label: 'Revisar caja', route: '/cash', hint: 'Prioriza cobros y revisa gastos fijos.' }
    return {
      label: 'Preparar informe',
      route: reportsCount ? '/reports' : '/cash',
      hint: reportsCount ? 'Comparte el informe del mes.' : 'Revisa caja y genera el informe.'
    }
  })()

  function cashBadgeStyle() {
    if (cashStatus.tone === 'danger') return { borderColor: 'rgba(248, 113, 113, 0.45)', color: 'rgba(254, 202, 202, 0.95)' }
    if (cashStatus.tone === 'warning') return { borderColor: 'rgba(250, 204, 21, 0.45)', color: 'rgba(254, 240, 138, 0.95)' }
    if (cashStatus.tone === 'success') return { borderColor: 'rgba(34, 197, 94, 0.45)', color: 'rgba(187, 247, 208, 0.95)' }
    return { borderColor: 'rgba(148, 163, 184, 0.25)', color: 'rgba(226, 232, 240, 0.78)' }
  }

  return (
    <div>
      <PageHeader
        title="Resumen"
        subtitle="Lo importante hoy: caja, alertas e informes (sin entrar en detalles técnicos)."
        actions={
          <div style={{ display: 'grid', gap: 8, justifyItems: 'end' }}>
            <div className="mini-row" style={{ justifyContent: 'flex-end' }}>
              <span className="badge">{(plan || 'BRONZE').toUpperCase()}</span>
              <span className="badge" style={cashBadgeStyle()}>
                {cashStatus.label}
              </span>
            </div>
            <div style={{ display: 'grid', gap: 6, width: '100%', maxWidth: 240 }}>
              <Button size="sm" onClick={() => navigate(todayAction.route)}>
                Qué hago hoy
              </Button>
              <div className="upload-hint">{todayAction.hint}</div>
            </div>
          </div>
        }
      />

      {!companyId ? (
        <Alert tone="warning" title="Falta seleccionar empresa">
          Selecciona una empresa arriba para ver el resumen.
        </Alert>
      ) : null}

      {companyId ? (
        <QuickStartClient
          companySelected={!!companyId}
          hasCashData={hasData}
          alertsCount={alertsCount}
          reportsCount={reportsCount}
        />
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

        <details className="card" style={{ alignSelf: 'start' }}>
          <summary className="mini-row" style={{ cursor: 'pointer', justifyContent: 'space-between', alignItems: 'baseline' }}>
            <strong>Qué hago ahora</strong>
            <span className="badge">ver</span>
          </summary>
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
            <div className="mini-row" style={{ marginTop: 8 }}>
              <Link className="badge" to="/cash">
                Abrir caja
              </Link>
              <Link className="badge" to="/reports">
                Abrir informes
              </Link>
              <Link className="badge" to="/help">
                Guía
              </Link>
            </div>
          </div>
        </details>
      </div>
    </div>
  )
}
