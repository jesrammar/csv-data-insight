import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { getDashboard, getLatestRecommendations, getReports, getTribunalSummary, getUniversalSummary, getUserRole } from '../api'
import KpiChart from '../components/KpiChart'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Skeleton from '../components/ui/Skeleton'
import { useCompanySelection } from '../hooks/useCompany'
import { formatMoney } from '../utils/format'

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

export default function OverviewPage() {
  const { id: companyId, plan } = useCompanySelection()
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const isClient = getUserRole() === 'CLIENTE'
  const monthsCount = plan === 'PLATINUM' ? 12 : plan === 'GOLD' ? 9 : 6
  const months = lastMonths(monthsCount)
  const from = months[0]
  const to = months[months.length - 1]

  const { data: dashboard, isLoading: dashboardLoading, error: dashboardError } = useQuery({
    queryKey: ['overview-dashboard', companyId, from, to],
    queryFn: () => getDashboard(companyId as number, from, to),
    enabled: !!companyId
  })

  const { data: tribunal } = useQuery({
    queryKey: ['overview-tribunal', companyId],
    queryFn: () => getTribunalSummary(companyId as number),
    enabled: !!companyId && hasGold && !isClient
  })

  const { data: universal } = useQuery({
    queryKey: ['overview-universal', companyId],
    queryFn: () => getUniversalSummary(companyId as number),
    enabled: !!companyId && !isClient
  })

  const { data: reports } = useQuery({
    queryKey: ['overview-reports', companyId],
    queryFn: () => getReports(companyId as number),
    enabled: !!companyId
  })

  const { data: recSnapshot } = useQuery({
    queryKey: ['overview-recommendations', companyId],
    queryFn: () => getLatestRecommendations(companyId as number),
    enabled: !!companyId && isClient
  })

  const latest = dashboard?.kpis?.[dashboard?.kpis.length - 1]
  const chartPoints = (dashboard?.kpis || []).map((k: any) => ({ label: k.period, value: Number(k.netFlow) }))
  const hasCashData = (dashboard?.kpis || []).length > 0

  const tribunalKpis = (tribunal as any)?.kpis
  const tribunalRiskCount = ((tribunal as any)?.risk || []).length

  const universalAny = universal as any
  const universalFilename = universalAny?.filename
  const universalRows = universalAny?.rowCount
  const universalInsights = (universalAny?.insights || []).length

  const reportsCount = Array.isArray(reports) ? reports.length : 0
  const overviewLoading = !!companyId && dashboardLoading
  const topActions = (recSnapshot?.actions || []).slice(0, 3)

  return (
    <div>
      <PageHeader
        title="Vista ejecutiva"
        subtitle="Caja · Tribunal · Universal · Informes"
        actions={
          <div className="card soft" style={{ padding: 14, minWidth: 220 }}>
            <div className="upload-hint">Periodo actual</div>
            <div style={{ fontWeight: 800, marginTop: 6 }}>{to}</div>
            <div className="upload-hint" style={{ marginTop: 6 }}>
              {latest ? `Neto del mes: ${formatMoney(latest.netFlow)}` : 'Sin datos'}
            </div>
          </div>
        }
      />
      {!companyId ? (
        <div className="empty" style={{ marginBottom: 14 }}>
          Selecciona una empresa arriba para ver el resumen.
        </div>
      ) : dashboardError ? (
        <div className="empty" style={{ marginBottom: 14 }}>
          No se pudo cargar el resumen. Revisa la conexión o los permisos.
        </div>
      ) : null}

      {companyId && !dashboardError && !overviewLoading && !hasCashData ? (
        <div className="section">
          <Alert tone="warning" title="Siguiente paso: cargar datos">
            No hay datos del periodo. Carga el CSV/XLSX y vuelve a esta vista.
            <div style={{ marginTop: 10 }}>
              <Link className="badge" to="/imports">
                Cargar datos
              </Link>
            </div>
          </Alert>
        </div>
      ) : null}

      {overviewLoading ? (
        <div className="grid section" aria-label="Cargando resumen">
          {Array.from({ length: 4 }).map((_, idx) => (
            <div className="card" key={idx}>
              <Skeleton style={{ width: '56%', height: 14 }} />
              <Skeleton style={{ width: '82%', height: 12, marginTop: 14 }} />
              <Skeleton style={{ width: '68%', height: 12, marginTop: 10 }} />
              <Skeleton style={{ width: '74%', height: 12, marginTop: 10 }} />
              <div style={{ display: 'flex', gap: 10, marginTop: 16 }}>
                <Skeleton style={{ width: 86, height: 28, borderRadius: 999 }} />
                <Skeleton style={{ width: 110, height: 28, borderRadius: 999 }} />
              </div>
            </div>
          ))}
        </div>
      ) : (
      <div className="grid section">
        <div className="card">
          <div className="mini-row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
            <h3 style={{ margin: 0 }}>Caja y liquidez</h3>
            <Link className="badge" to="/dashboard">
              Ir a Caja
            </Link>
          </div>
          {!dashboard?.kpis?.length ? (
            <div className="empty" style={{ marginTop: 12 }}>
              Sin KPIs mensuales. Importa transacciones para calcular caja.
            </div>
          ) : (
            <div style={{ marginTop: 12 }}>
              <KpiChart title={`Neto (últimos ${monthsCount} meses)`} points={chartPoints} variant="area" />
              <div className="grid" style={{ marginTop: 14 }}>
                <div className="kpi">
                  <h4>Entradas</h4>
                  <strong>{formatMoney(latest?.inflows)}</strong>
                </div>
                <div className="kpi">
                  <h4>Salidas</h4>
                  <strong>{formatMoney(latest?.outflows)}</strong>
                </div>
                <div className="kpi">
                  <h4>Saldo fin</h4>
                  <strong>{formatMoney(latest?.endingBalance)}</strong>
                </div>
              </div>
            </div>
          )}
        </div>

        <div className="card">
          <div className="mini-row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
            <h3 style={{ margin: 0 }}>Cumplimiento (Tribunal)</h3>
            {!isClient && hasGold ? (
              <Link className="badge" to="/tribunal">
                Ir a Tribunal
              </Link>
            ) : (
              <span className="badge warn">{isClient ? 'Solo consultor' : 'GOLD+'}</span>
            )}
          </div>
          {isClient ? (
            <div style={{ marginTop: 12 }}>
              {!topActions.length ? (
                <div className="empty">
                  Aún no hay recomendaciones generadas. Tu consultora puede ejecutar el snapshot desde automatización.
                </div>
              ) : (
                <div className="bar-stack">
                  {topActions.map((a) => (
                    <div className="kpi" key={`${a.priority}-${a.title}`} style={{ padding: 12 }}>
                      <div className="mini-row" style={{ marginTop: 0 }}>
                        <span className="badge warn">{a.priority}</span>
                        <span className="upload-hint">{a.horizon}</span>
                      </div>
                      <strong style={{ display: 'block', marginTop: 8, fontSize: 14 }}>{a.title}</strong>
                      <div className="upload-hint" style={{ marginTop: 6 }}>
                        {a.kpi}
                      </div>
                    </div>
                  ))}
                </div>
              )}
              <div className="upload-hint" style={{ marginTop: 10 }}>
                La sección completa de Tribunal se gestiona desde consultoría.
              </div>
            </div>
          ) : !hasGold ? (
            <div className="empty" style={{ marginTop: 12 }}>
              Disponible en plan GOLD/PLATINUM.
            </div>
          ) : !tribunalKpis ? (
            <div className="empty" style={{ marginTop: 12 }}>
              Sube un CSV de Tribunal para ver KPIs y riesgos.
            </div>
          ) : (
            <div style={{ marginTop: 12 }}>
              <div className="grid">
                <div className="kpi">
                  <h4>Clientes</h4>
                  <strong>{tribunalKpis.totalClients}</strong>
                </div>
                <div className="kpi">
                  <h4>Activos</h4>
                  <strong>{tribunalKpis.activeClients}</strong>
                </div>
                <div className="kpi">
                  <h4>Riesgos</h4>
                  <strong>{tribunalRiskCount}</strong>
                </div>
              </div>
              <details style={{ marginTop: 12 }}>
                <summary className="upload-hint" style={{ cursor: 'pointer' }}>
                  Ver detalle
                </summary>
                <div className="grid" style={{ marginTop: 12 }}>
                  <div className="kpi">
                    <h4>% Contabilidad</h4>
                    <strong>{Number(tribunalKpis.contabilidadPct).toFixed(0)}%</strong>
                  </div>
                </div>
              </details>
            </div>
          )}
        </div>

        <div className="card">
          <div className="mini-row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
            <h3 style={{ margin: 0 }}>Análisis universal</h3>
            {isClient ? <span className="badge warn">Solo consultor</span> : (
              <Link className="badge" to="/universal">
                Ir a Universal
              </Link>
            )}
          </div>
          {isClient ? (
            <div className="empty" style={{ marginTop: 12 }}>
              La consultora realiza el análisis y te entrega conclusiones + acciones.
            </div>
          ) : !universalFilename ? (
            <div className="empty" style={{ marginTop: 12 }}>
              Sube un CSV/XLSX para análisis exploratorio y asesoramiento.
            </div>
          ) : (
            <div style={{ marginTop: 12 }}>
              <div className="kpi">
                <h4>Último dataset</h4>
                <strong style={{ fontSize: 16 }}>{universalFilename}</strong>
                <div className="upload-hint" style={{ marginTop: 8 }}>
                  {universalRows} filas · {universalInsights} insights
                </div>
              </div>
              <div className="upload-hint" style={{ marginTop: 10 }}>
                En PLATINUM: drill-down + Asesor + informe consultivo.
              </div>
            </div>
          )}
        </div>

        <div className="card">
          <div className="mini-row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
            <h3 style={{ margin: 0 }}>Entregables</h3>
            <Link className="badge" to="/reports">
              Ver informes
            </Link>
          </div>
          <div style={{ marginTop: 12 }}>
            <div className="grid">
              <div className="kpi">
                <h4>Informes</h4>
                <strong>{reportsCount}</strong>
              </div>
              <div className="kpi">
                <h4>Plan</h4>
                <strong>{(plan || 'BRONZE').toUpperCase()}</strong>
              </div>
            </div>
            <div className="upload-hint" style={{ marginTop: 10 }}>Informes mensuales listos para compartir.</div>
          </div>
        </div>
      </div>
      )}
    </div>
  )
}
