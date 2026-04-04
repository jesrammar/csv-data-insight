import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { useEffect, useMemo, useState } from 'react'
import {
  getDashboard,
  getLatestRecommendations,
  getIngestionStatus,
  getMacroContext,
  getReports,
  retryImport,
  getTribunalSummary,
  getUniversalSummary,
  getUserId,
  getUserRole
} from '../api'
import KpiChart from '../components/KpiChart'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import Skeleton from '../components/ui/Skeleton'
import { useCompanySelection } from '../hooks/useCompany'
import { formatMoney } from '../utils/format'
import { useToast } from '../components/ui/ToastProvider'

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
  const toast = useToast()
  const navigate = useNavigate()
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const role = getUserRole()
  const isClient = role === 'CLIENTE'
  const isConsultor = role === 'CONSULTOR'
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

  const { data: macro } = useQuery({
    queryKey: ['overview-macro', companyId, to],
    queryFn: () => getMacroContext(companyId as number, to),
    enabled: !!companyId
  })

  const { data: ingestion, isLoading: ingestionLoading } = useQuery({
    queryKey: ['overview-ingestion', companyId],
    queryFn: () => getIngestionStatus(companyId as number),
    enabled: !!companyId
  })

  const wizardKey = useMemo(() => {
    if (!companyId) return ''
    const userId = getUserId() ?? 'anon'
    return `onboarding_wizard_dismissed:${userId}:${companyId}`
  }, [companyId])

  const wizardProgressKey = useMemo(() => {
    if (!companyId) return ''
    const userId = getUserId() ?? 'anon'
    return `onboarding_wizard_progress:${userId}:${companyId}`
  }, [companyId])

  const [wizardDismissed, setWizardDismissed] = useState(false)
  const [wizardModule, setWizardModule] = useState<'caja' | 'universal' | 'tribunal' | 'presupuesto'>('caja')
  const [wizardProgress, setWizardProgress] = useState<{ step1: boolean; step2: boolean; step3: boolean }>({
    step1: false,
    step2: false,
    step3: false
  })

  useEffect(() => {
    if (!wizardKey) return
    try {
      setWizardDismissed(localStorage.getItem(wizardKey) === '1')
    } catch {
      setWizardDismissed(false)
    }
  }, [wizardKey])

  useEffect(() => {
    if (!wizardProgressKey) return
    try {
      const raw = localStorage.getItem(wizardProgressKey)
      if (!raw) return
      const parsed = JSON.parse(raw || '{}')
      setWizardProgress({
        step1: !!parsed.step1,
        step2: !!parsed.step2,
        step3: !!parsed.step3
      })
    } catch {
      // ignore
    }
  }, [wizardProgressKey])

  const markWizardStep = (step: 1 | 2 | 3) => {
    setWizardProgress((prev) => {
      const next = { ...prev, [`step${step}`]: true } as { step1: boolean; step2: boolean; step3: boolean }
      if (wizardProgressKey) {
        try {
          localStorage.setItem(wizardProgressKey, JSON.stringify(next))
        } catch {
          // ignore
        }
      }
      return next
    })
  }

  const showWizard = isConsultor && !!companyId && !wizardDismissed && !ingestionLoading && !ingestion?.lastImport

  const wizardMeta = useMemo(() => {
    return {
      caja: {
        label: 'Caja',
        desc: 'Movimientos de banco/caja por periodo (fecha + importe).',
        sampleHref: '/samples/plantilla-caja-transacciones.csv',
        guideModule: 'caja',
        importsHref: '/imports?mode=transactions',
        dashboardHref: '/dashboard'
      },
      universal: {
        label: 'Universal',
        desc: 'Cualquier CSV/XLSX: presupuesto, ventas, inventario, nóminas…',
        sampleHref: '/samples/plantilla-universal.csv',
        guideModule: 'universal',
        importsHref: '/imports?mode=universal',
        dashboardHref: '/universal'
      },
      tribunal: {
        label: 'Tribunal',
        desc: 'Cartera/cumplimiento (CSV de clientes + flags).',
        sampleHref: '/samples/plantilla-tribunal.csv',
        guideModule: 'tribunal',
        importsHref: '/imports?mode=auto',
        dashboardHref: '/tribunal'
      },
      presupuesto: {
        label: 'Presupuesto',
        desc: 'XLSX anual con meses (ENERO…DICIEMBRE) → insights + PDF.',
        sampleHref: '/samples/presupuesto-ejemplo.xlsx',
        guideModule: 'presupuesto',
        importsHref: '/imports?mode=universal',
        dashboardHref: '/budget'
      }
    } as const
  }, [])

  const selectedMeta = wizardMeta[wizardModule]
  const wizardDoneCount = Number(wizardProgress.step1) + Number(wizardProgress.step2) + Number(wizardProgress.step3)

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

  function fmt(v: number | null | undefined, unit?: string | null) {
    if (v == null || Number.isNaN(Number(v))) return '—'
    const n = Number(v)
    const text = unit === '%' ? `${n.toFixed(2)}%` : unit ? `${n.toFixed(3)} ${unit}` : String(n)
    return text
  }

  function fmtTime(iso?: string | null) {
    if (!iso) return '—'
    try {
      return new Date(iso).toLocaleString()
    } catch {
      return iso
    }
  }

  const lastImport = ingestion?.lastImport || null
  const canRetry = !isClient && !!companyId && !!lastImport?.id && (lastImport.status === 'DEAD' || lastImport.status === 'ERROR')

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
            <div className="upload-hint" style={{ marginTop: 10 }}>
              Última ingesta: {fmtTime(ingestion?.lastProcessedImport?.processedAt || ingestion?.lastImport?.createdAt)}
            </div>
            <div className="upload-hint" style={{ marginTop: 4 }}>
              Estado: {String(ingestion?.lastImport?.status || '—')}
            </div>
            {canRetry ? (
              <div style={{ marginTop: 10 }}>
                <Button
                  size="sm"
                  onClick={async () => {
                    try {
                      await retryImport(companyId as number, lastImport!.id)
                      toast.push({ tone: 'success', title: 'Import', message: 'Reintento encolado. Revisa Caja en 1–2 minutos.' })
                    } catch (e: any) {
                      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo reintentar.' })
                    }
                  }}
                >
                  Reintentar último import
                </Button>
              </div>
            ) : null}
            <div className="upload-hint" style={{ marginTop: 10 }}>
              IPC (interanual): {fmt(macro?.inflationYoyPct?.value, '%')}
            </div>
            <div className="upload-hint" style={{ marginTop: 4 }}>
              Euribor 1a: {fmt(macro?.euribor1yPct?.value, '%')}
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

      {showWizard ? (
        <div className="section">
          <div className="card wizard">
            <div className="wizard-head">
              <div>
                <div className="wizard-title">Arranque rápido</div>
                <div className="wizard-sub">
                  Esta empresa aún no tiene datos. Elige un módulo y sigue estos 3 pasos (2–5 min).
                </div>
                <div className="wizard-progress">{wizardDoneCount}/3 completado</div>
              </div>
              <div className="wizard-actions">
                <Button size="sm" variant="ghost" onClick={() => setWizardDismissed(true)}>
                  Ocultar
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    if (!wizardKey) return
                    try {
                      localStorage.setItem(wizardKey, '1')
                    } catch {
                      // ignore
                    }
                    setWizardDismissed(true)
                  }}
                >
                  No volver a mostrar
                </Button>
              </div>
            </div>

            <div className="segmented wizard-tabs" role="tablist" aria-label="Módulo (wizard)">
              <Button type="button" size="sm" variant={wizardModule === 'caja' ? 'secondary' : 'ghost'} onClick={() => setWizardModule('caja')}>
                Caja
              </Button>
              <Button
                type="button"
                size="sm"
                variant={wizardModule === 'universal' ? 'secondary' : 'ghost'}
                onClick={() => setWizardModule('universal')}
              >
                Universal
              </Button>
              <Button
                type="button"
                size="sm"
                variant={wizardModule === 'tribunal' ? 'secondary' : 'ghost'}
                onClick={() => setWizardModule('tribunal')}
                disabled={!hasGold}
                title={!hasGold ? 'Disponible en GOLD/PLATINUM' : undefined}
              >
                Tribunal {!hasGold ? '(GOLD)' : ''}
              </Button>
              <Button
                type="button"
                size="sm"
                variant={wizardModule === 'presupuesto' ? 'secondary' : 'ghost'}
                onClick={() => setWizardModule('presupuesto')}
              >
                Presupuesto
              </Button>
            </div>

            <div className="wizard-module-desc">{selectedMeta.desc}</div>

            <div className="wizard-steps">
              <div className="wizard-step">
                <div className="wizard-step-head">
                  <span className={`wizard-step-num ${wizardProgress.step1 ? 'done' : ''}`}>{wizardProgress.step1 ? '✓' : '1'}</span>
                  <div>
                    <div className="wizard-step-title">Descarga un ejemplo</div>
                    <div className="wizard-step-sub">Te sirve como plantilla y para validar el formato.</div>
                  </div>
                </div>
                <div className="wizard-step-actions">
                  <a
                    className="btn btn-secondary btn-sm"
                    href={selectedMeta.sampleHref}
                    download
                    onClick={() => markWizardStep(1)}
                  >
                    Descargar {selectedMeta.label}
                  </a>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      markWizardStep(1)
                      navigate(`/guides?module=${selectedMeta.guideModule}`)
                    }}
                  >
                    Ver guía
                  </Button>
                </div>
                <div className="wizard-note">Si es XLSX “raro”: usa modo guiado (hoja + fila de cabecera).</div>
              </div>

              <div className="wizard-step">
                <div className="wizard-step-head">
                  <span className={`wizard-step-num ${wizardProgress.step2 ? 'done' : ''}`}>{wizardProgress.step2 ? '✓' : '2'}</span>
                  <div>
                    <div className="wizard-step-title">Sube el fichero</div>
                    <div className="wizard-step-sub">Siempre desde “Cargar datos”.</div>
                  </div>
                </div>
                <div className="wizard-step-actions">
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => {
                      markWizardStep(2)
                      navigate(selectedMeta.importsHref)
                    }}
                  >
                    Ir a Cargar datos
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      markWizardStep(2)
                      navigate('/imports')
                    }}
                  >
                    Abrir AUTO
                  </Button>
                </div>
              </div>

              <div className="wizard-step">
                <div className="wizard-step-head">
                  <span className={`wizard-step-num ${wizardProgress.step3 ? 'done' : ''}`}>{wizardProgress.step3 ? '✓' : '3'}</span>
                  <div>
                    <div className="wizard-step-title">Revisa el dashboard</div>
                    <div className="wizard-step-sub">Confirma KPIs/insights antes de enseñarlo al cliente.</div>
                  </div>
                </div>
                <div className="wizard-step-actions">
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      markWizardStep(3)
                      navigate(selectedMeta.dashboardHref)
                    }}
                    disabled={wizardModule === 'tribunal' && !hasGold}
                  >
                    Abrir {selectedMeta.label}
                  </Button>
                </div>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      {companyId && !dashboardError && !overviewLoading && !hasCashData && !showWizard ? (
        <div className="section">
          <Alert tone="warning" title="Empezar aquí (2 minutos)">
            <div className="hero-sub">
              <ol style={{ margin: 0, paddingLeft: 18, display: 'grid', gap: 8 }}>
                <li>
                  Ve a <strong>Cargar datos</strong> y sube un CSV/XLSX de transacciones (fecha + importe).
                </li>
                <li>
                  Entra en <strong>Caja</strong> para ver KPIs y gráficos.
                </li>
                <li>
                  Genera un <strong>Informe</strong> en Entregables (PDF).
                </li>
              </ol>
            </div>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 12 }}>
              <Link className="badge" to="/guides">
                Guías de carga
              </Link>
              <Link className="badge" to="/imports">
                1) Cargar datos
              </Link>
              <Link className="badge" to="/dashboard">
                2) Ir a Caja
              </Link>
              <Link className="badge" to="/reports">
                3) Entregables
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
