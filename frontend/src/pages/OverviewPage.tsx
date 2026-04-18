import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { useEffect, useMemo, useState } from 'react'
import {
  getDashboard,
  getLatestRecommendations,
  getIngestionStatus,
  getMacroContext,
  getReports,
  getAlerts,
  getImports,
  getChecklist,
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
import { getWorkPeriod } from '../utils/workPeriod'

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

function mean(nums: number[]) {
  if (!nums.length) return 0
  return nums.reduce((acc, v) => acc + v, 0) / nums.length
}

function stddev(nums: number[]) {
  if (nums.length < 2) return 0
  const m = mean(nums)
  const v = mean(nums.map((x) => (x - m) ** 2))
  return Math.sqrt(v)
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
  const workPeriod = useMemo(() => (companyId ? getWorkPeriod(companyId) : null) || to, [companyId, to])

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

  const { data: importJobs } = useQuery({
    queryKey: ['overview-imports', companyId],
    queryFn: () => getImports(companyId as number),
    enabled: !!companyId && !isClient
  })

  const { data: checklist } = useQuery({
    queryKey: ['overview-checklist', companyId, workPeriod],
    queryFn: () => getChecklist(companyId as number, workPeriod),
    enabled: !!companyId
  })

  const { data: alertsByMonth } = useQuery({
    queryKey: ['overview-alerts-by-month', companyId, from, to],
    queryFn: async () => {
      if (!companyId) return {} as Record<string, any[]>
      const entries = await Promise.all(
        months.map(async (p) => {
          try {
            const res = await getAlerts(companyId as number, p)
            return [p, Array.isArray(res) ? res : []] as const
          } catch {
            return [p, []] as const
          }
        })
      )
      return Object.fromEntries(entries) as Record<string, any[]>
    },
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
        desc: 'Cualquier CSV/XLSX: presupuesto, ventas, inventario, nominas...',
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
        desc: 'XLSX anual con meses (ENERO...DICIEMBRE) -> insights + PDF.',
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
  const chartPoints: Array<{ label: string; value: number }> = (dashboard?.kpis || []).map((k: any) => ({
    label: String(k.period || ''),
    value: Number(k.netFlow)
  }))
  const hasCashData = (dashboard?.kpis || []).length > 0

  const values = chartPoints.map((p) => Number(p.value))
  const last = values[values.length - 1] ?? 0
  const prev = values.length >= 2 ? values[values.length - 2] : null
  const deltaMoM = prev == null ? null : last - prev
  const deltaMoMPct = prev == null || Math.abs(prev) < 1e-9 ? null : (deltaMoM! / Math.abs(prev)) * 100
  const last6 = values.slice(Math.max(0, values.length - 6))
  const avg6 = mean(last6)
  const sd6 = stddev(last6)
  const band = sd6 > 0 ? { low: avg6 - sd6, high: avg6 + sd6, name: 'Banda objetivo' } : null
  const deltaVsAvg6 = values.length ? last - avg6 : null

  const alertPeriods = new Set<string>()
  for (const p of months) {
    const n = (alertsByMonth as any)?.[p]?.length || 0
    if (n > 0) alertPeriods.add(p)
  }

  const importPeriods = new Map<string, any[]>()
  ;(importJobs as any[] | undefined)?.forEach((j: any) => {
    const p = String(j?.period || '').slice(0, 7)
    if (!p) return
    if (!importPeriods.has(p)) importPeriods.set(p, [])
    importPeriods.get(p)!.push(j)
  })

  const markers = months
    .flatMap((p) => {
      const out: any[] = []
      if (alertPeriods.has(p)) {
        const n = (alertsByMonth as any)?.[p]?.length || 0
        out.push({ label: p, name: 'A', kind: 'alert', text: `${n} alerta${n === 1 ? '' : 's'}` })
      }
      const jobs = importPeriods.get(p) || []
      const bad = jobs.find((j: any) => ['DEAD', 'ERROR', 'WARNING'].includes(String(j?.status || '')))
      if (bad) {
        out.push({
          label: p,
          name: 'I',
          kind: bad.status === 'WARNING' ? 'warning' : 'import',
          text: `Import ${String(bad.status || '').toLowerCase()}`
        })
      }
      return out
    })
    .slice(0, 18)

  const tribunalKpis = (tribunal as any)?.kpis
  const tribunalRiskCount = ((tribunal as any)?.risk || []).length

  const universalAny = universal as any
  const universalFilename = universalAny?.filename
  const universalRows = universalAny?.rowCount
  const universalInsights = (universalAny?.insights || []).length

  const reportsCount = Array.isArray(reports) ? reports.length : 0
  const overviewLoading = !!companyId && dashboardLoading
  const topActions = (recSnapshot?.actions || []).slice(0, 3)
  const lastImport = ingestion?.lastImport || null
  const canRetry = !isClient && !!companyId && !!lastImport?.id && (lastImport.status === 'DEAD' || lastImport.status === 'ERROR')
  const nextAction = !companyId
    ? null
    : showWizard
      ? { title: 'Completa el arranque', detail: 'Descarga un ejemplo, sube un fichero y revisa el dashboard inicial.', href: '/imports', cta: 'Empezar' }
      : !hasCashData
        ? { title: 'Carga el primer dataset', detail: 'Sin transacciones no puedo calcular KPIs ni liquidez.', href: '/imports', cta: 'Cargar datos' }
        : canRetry
          ? { title: 'Recupera la ultima ingesta', detail: 'El ultimo import fallo o quedo en error. Reintentalo antes de seguir.', href: '/imports', cta: 'Revisar imports' }
          : !reportsCount
            ? { title: 'Genera el primer entregable', detail: 'Ya hay datos suficientes para preparar un informe compartible.', href: '/reports', cta: 'Ir a informes' }
            : !isClient && !universalFilename
              ? { title: 'Activa Universal', detail: 'Sube un CSV o XLSX para ampliar el analisis mas alla de Caja.', href: '/universal', cta: 'Abrir Universal' }
              : { title: 'Revisa el periodo actual', detail: 'La foto ejecutiva ya esta lista. Entra al modulo con mas movimiento.', href: '/dashboard', cta: 'Ir a Caja' }

  function renderPanelState(title: string, detail?: string, tone: 'default' | 'loading' | 'locked' = 'default', className = 'mt-3') {
    return (
      <div className={`panel-state panel-state-${tone} ${className}`.trim()}>
        <div className="panel-state-title">{title}</div>
        {detail ? <div className="panel-state-detail">{detail}</div> : null}
      </div>
    )
  }

  function fmt(v: number | null | undefined, unit?: string | null) {
    if (v == null || Number.isNaN(Number(v))) return '-'
    const n = Number(v)
    const text = unit === '%' ? `${n.toFixed(2)}%` : unit ? `${n.toFixed(3)} ${unit}` : String(n)
    return text
  }

  function fmtTime(iso?: string | null) {
    if (!iso) return '-'
    try {
      return new Date(iso).toLocaleString()
    } catch {
      return iso
    }
  }
  return (
    <div className="overview-page">
      <PageHeader
        title="Vista ejecutiva"
        subtitle="Caja · Tribunal · Universal · Informes"
        actions={
          <div className="card soft overview-status">
            <div className="upload-hint">Periodo actual</div>
            <div className="fw-800 mt-1">{to}</div>
            <div className="upload-hint mt-1">
              {latest ? `Neto del mes: ${formatMoney(latest.netFlow)}` : 'Sin datos'}
            </div>
            <div className="upload-hint mt-2">
              Ultima ingesta: {fmtTime(ingestion?.lastProcessedImport?.processedAt || ingestion?.lastImport?.createdAt)}
            </div>
            <div className="upload-hint mt-tight">
              Estado: {String(ingestion?.lastImport?.status || '-')}
            </div>
            {canRetry ? (
              <div className="mt-2">
                <Button
                  size="sm"
                  onClick={async () => {
                    try {
                      await retryImport(companyId as number, lastImport!.id)
                      toast.push({ tone: 'success', title: 'Import', message: 'Reintento encolado. Revisa Caja en 1-2 minutos.' })
                    } catch (e: any) {
                      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo reintentar.' })
                    }
                  }}
                >
                  Reintentar ultimo import
                </Button>
              </div>
            ) : null}
            <div className="upload-hint mt-2">
              IPC (interanual): {fmt(macro?.inflationYoyPct?.value, '%')}
            </div>
            <div className="upload-hint mt-tight">
              Euribor 1a: {fmt(macro?.euribor1yPct?.value, '%')}
            </div>
          </div>
        }
      />
      {!companyId ? (
        renderPanelState('Selecciona una empresa', 'Elige una empresa arriba para ver el resumen ejecutivo.', 'default', 'mb-3')
      ) : dashboardError ? (
        renderPanelState('No se pudo cargar el resumen', 'Revisa la conexion, los permisos o vuelve a intentarlo en unos segundos.', 'default', 'mb-3')
      ) : null}

      {companyId ? (
        <div className="card section soft">
          <div className="mini-row row-baseline">
            <h3 className="m-0">Hoy</h3>
            <span className="upload-hint">La lectura rapida para no perder el foco.</span>
          </div>
          <div className="grid grid-autofit-220 mt-12">
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Periodo de trabajo</div>
              <div className="fw-800 mt-1">{checklist?.period || workPeriod}</div>
              <div className="upload-hint mt-1">{latest ? `Neto del mes: ${formatMoney(latest.netFlow)}` : 'Aun sin datos de caja.'}</div>
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Estado de datos</div>
              <div className="fw-800 mt-1">{String(ingestion?.lastImport?.status || 'SIN INGESTA')}</div>
              <div className="upload-hint mt-1">Ultima ingesta: {fmtTime(ingestion?.lastProcessedImport?.processedAt || ingestion?.lastImport?.createdAt)}</div>
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Que toca ahora</div>
              <div className="fw-800 mt-1">{nextAction?.title || 'Selecciona una empresa'}</div>
              <div className="upload-hint mt-1">{nextAction?.detail || 'Elige una empresa para activar la hoja de ruta.'}</div>
              {nextAction ? (
                <div className="mt-2">
                  <Link className="badge" to={nextAction.href}>
                    {nextAction.cta}
                  </Link>
                </div>
              ) : null}
            </div>
          </div>
        </div>
      ) : null}

      {showWizard ? (
        <div className="section">
          <div className="mini-row row-baseline mb-12">
            <h3 className="m-0">Primeros pasos</h3>
            <span className="upload-hint">Solo aparece cuando la empresa aun no tiene datos.</span>
          </div>
          <div className="card wizard">
            <div className="wizard-head">
              <div>
                <div className="wizard-title">Arranque rapido</div>
                <div className="wizard-sub">
                  Esta empresa aun no tiene datos. Elige un modulo y sigue estos 3 pasos (2-5 min).
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

            <div className="segmented wizard-tabs" role="tablist" aria-label="Modulo (wizard)">
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
                  <span className={`wizard-step-num ${wizardProgress.step1 ? 'done' : ''}`}>{wizardProgress.step1 ? 'OK' : '1'}</span>
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
                    Ver guia
                  </Button>
                </div>
                <div className="wizard-note">Si es XLSX "raro": usa modo guiado (hoja + fila de cabecera).</div>
              </div>

              <div className="wizard-step">
                <div className="wizard-step-head">
                  <span className={`wizard-step-num ${wizardProgress.step2 ? 'done' : ''}`}>{wizardProgress.step2 ? 'OK' : '2'}</span>
                  <div>
                    <div className="wizard-step-title">Sube el fichero</div>
                    <div className="wizard-step-sub">Siempre desde "Cargar datos".</div>
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
                  <span className={`wizard-step-num ${wizardProgress.step3 ? 'done' : ''}`}>{wizardProgress.step3 ? 'OK' : '3'}</span>
                  <div>
                    <div className="wizard-step-title">Valida el resultado</div>
                    <div className="wizard-step-sub">Confirma KPIs e insights antes de ensenarlo al cliente.</div>
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
          <div className="mini-row row-baseline mb-12">
            <h3 className="m-0">Primeros pasos</h3>
            <span className="upload-hint">La ruta minima para tener valor en 2 minutos.</span>
          </div>
          <div className="card soft">
            <div className="mini-row row-baseline">
              <h3 className="m-0">Empezar (2 minutos)</h3>
              <Link className="badge" to="/guides">
                Ver guia
              </Link>
            </div>

            <div className="upload-hint mt-8">
              Mes de trabajo: <span className="badge">{checklist?.period || workPeriod}</span>
            </div>

            {checklist?.items?.length ? (
              <div className="upload-hint mt-8">
                <ol className="list-steps">
                  {checklist.items.slice(0, 4).map((it: any) => (
                    <li key={it.id}>
                      <span className="fw-700">{it.done ? 'OK' : '·'}</span> {it.label}{' '}
                      {!it.done && it.hint ? <span className="upload-hint">- {it.hint}</span> : null}
                    </li>
                  ))}
                </ol>
              </div>
            ) : (
              <div className="upload-hint mt-8">
                1) Sube transacciones · 2) Revisa Caja · 3) Genera PDF
              </div>
            )}

            <div className="overview-start-actions">
              <Link className="badge" to="/imports">
                Cargar datos
              </Link>
              <Link className="badge" to="/dashboard">
                Ir a Caja
              </Link>
              <Link className="badge" to="/reports">
                Entregables
              </Link>
              <details className="overview-start-details">
                <summary className="upload-hint cursor-pointer">
                  Ver pasos
                </summary>
                <div className="upload-hint mt-8">
                  <ol className="list-steps">
                    <li>
                      Ve a <strong>Cargar datos</strong> y sube un CSV/XLSX de transacciones (fecha + importe).
                    </li>
                    <li>
                      Entra en <strong>Caja</strong> para ver KPIs y graficos.
                    </li>
                    <li>
                      Genera un <strong>Informe</strong> en Entregables (PDF).
                    </li>
                  </ol>
                </div>
              </details>
            </div>
          </div>
        </div>
      ) : null}

      {overviewLoading ? (
        <div className="overview-modules section" aria-label="Cargando resumen">
          {Array.from({ length: 4 }).map((_, idx) => (
            <div className="card" key={idx}>
              <Skeleton className="sk-w-56p sk-h-14" />
              <Skeleton className="sk-w-82p sk-h-12 mt-3" />
              <Skeleton className="sk-w-68p sk-h-12 mt-2" />
              <Skeleton className="sk-w-74p sk-h-12 mt-2" />
              <div className="row gap-2 mt-4">
                <Skeleton className="sk-w-86 sk-h-28 radius-pill" />
                <Skeleton className="sk-w-110 sk-h-28 radius-pill" />
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="section">
          <div className="mini-row row-baseline mb-12">
            <h3 className="m-0">Modulos clave</h3>
            <span className="upload-hint">Caja primero; el resto amplia el contexto.</span>
          </div>
          <div className="overview-modules">
            <div className="card">
          <div className="mini-row row-baseline">
            <h3 className="m-0">Caja y liquidez</h3>
            <Link className="badge" to="/dashboard">
              Ir a Caja
            </Link>
          </div>
          {!dashboard?.kpis?.length ? (
            renderPanelState('Caja pendiente de datos', 'Importa transacciones para calcular KPIs, saldo y tendencias de caja.')
          ) : (
            <div className="mt-3">
              <KpiChart
                title={`Neto (ultimos ${monthsCount} meses)`}
                points={chartPoints}
                variant="area"
                module="overview"
                valueSuffix="€"
                markers={markers as any}
                showTrend
                band={band}
                onPointClick={(period) => navigate('/dashboard', { state: { drillPeriod: period } })}
              />

              <div className="grid mt-3">
                <div className="card soft">
                  <div className="upload-hint">Vs mes anterior</div>
                  <div className="fw-900 mt-1">{deltaMoM == null ? '-' : formatMoney(deltaMoM)}</div>
                  <div className="upload-hint mt-1">{deltaMoMPct == null ? '-' : `(${deltaMoMPct.toFixed(1)}%)`}</div>
                </div>
                <div className="card soft">
                  <div className="upload-hint">Vs promedio 6m</div>
                  <div className="fw-900 mt-1">{deltaVsAvg6 == null ? '-' : formatMoney(deltaVsAvg6)}</div>
                  <div className="upload-hint mt-1">{values.length ? `Promedio 6m: ${formatMoney(avg6)}` : '-'}</div>
                </div>
              </div>
              <div className="grid mt-3">
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
          <div className="mini-row row-baseline">
            <h3 className="m-0">Cumplimiento (Tribunal)</h3>
            {!isClient && hasGold ? (
              <Link className="badge" to="/tribunal">
                Ir a Tribunal
              </Link>
            ) : (
              <span className="badge warn">{isClient ? 'Solo consultor' : 'GOLD+'}</span>
            )}
          </div>
          {isClient ? (
            <div className="mt-3">
              {!topActions.length ? (
                renderPanelState('Sin recomendaciones todavia', 'Tu consultora puede generar el snapshot para que aqui aparezcan prioridades y acciones.', 'default', 'mt-0')
              ) : (
                <div className="bar-stack">
                  {topActions.map((a) => (
                    <div className="kpi" key={`${a.priority}-${a.title}`}>
                      <div className="mini-row mt-0">
                        <span className="badge warn">{a.priority}</span>
                        <span className="upload-hint">{a.horizon}</span>
                      </div>
                      <strong className="block mt-8 fs-14">{a.title}</strong>
                      <div className="upload-hint mt-1">
                        {a.kpi}
                      </div>
                    </div>
                  ))}
                </div>
              )}
              <div className="upload-hint mt-2">
                La seccion completa de Tribunal se gestiona desde consultoria.
              </div>
            </div>
          ) : !hasGold ? (
            renderPanelState('Tribunal bloqueado por plan', 'Este modulo se habilita en GOLD o PLATINUM.', 'locked')
          ) : !tribunalKpis ? (
            renderPanelState('Tribunal sin datos', 'Sube un CSV de Tribunal para ver KPIs, clientes activos y riesgos.')
          ) : (
            <div className="mt-3">
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
              <details className="mt-3">
                <summary className="upload-hint cursor-pointer">
                  Ver detalle
                </summary>
                <div className="grid mt-3">
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
          <div className="mini-row row-baseline">
            <h3 className="m-0">Analisis universal</h3>
            {isClient ? <span className="badge warn">Solo consultor</span> : (
              <Link className="badge" to="/universal">
                Ir a Universal
              </Link>
            )}
          </div>
          {isClient ? (
            renderPanelState('Universal gestionado por consultoria', 'La consultora realiza el analisis y te comparte conclusiones y acciones.')
          ) : !universalFilename ? (
            renderPanelState('Universal sin dataset', 'Sube un CSV o XLSX para activar analisis exploratorio y asesoramiento.')
          ) : (
            <div className="mt-3">
              <div className="kpi">
                <h4>Ultimo dataset</h4>
                <strong className="fs-16">{universalFilename}</strong>
                <div className="upload-hint mt-8">
                  {universalRows} filas · {universalInsights} insights
                </div>
              </div>
              <div className="upload-hint mt-2">
                En PLATINUM: drill-down + Asesor + informe consultivo.
              </div>
            </div>
          )}
        </div>

            <div className="card">
          <div className="mini-row row-baseline">
            <h3 className="m-0">Entregables</h3>
            <Link className="badge" to="/reports">
              Ver informes
            </Link>
          </div>
          <div className="mt-3">
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
            <div className="upload-hint mt-2">Informes mensuales listos para compartir.</div>
          </div>
        </div>
          </div>
        </div>
      )}
    </div>
  )
}

