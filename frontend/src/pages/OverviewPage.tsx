import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { useEffect, useMemo, useState } from 'react'
import {
  getChecklist,
  getDashboard,
  getIngestionStatus,
  getPeriodWorkflow,
  getPipelineSummary,
  getRecommendationFollowUps,
  getUserId,
  getUserRole,
  retryImport,
  type AdvisorActionFollowUp
} from '../api'
import PageHeader from '../components/ui/PageHeader'
import Button from '../components/ui/Button'
import Skeleton from '../components/ui/Skeleton'
import { useCompanySelection } from '../hooks/useCompany'
import { EMPTY_DATA_TEXT, formatMoney } from '../utils/format'
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

type ViewerRole = 'ADMIN' | 'CONSULTOR' | 'CLIENTE'

function normalizeViewerRole(role?: string | null): ViewerRole {
  const normalized = String(role || '').toUpperCase()
  if (normalized === 'ADMIN' || normalized === 'CLIENTE') return normalized
  return 'CONSULTOR'
}

function overviewRoleCopy(role: ViewerRole) {
  if (role === 'ADMIN') {
    return {
      pageSubtitle: 'La portada de gobierno para saber qué falta y cuál es el siguiente paso oficial del periodo.',
      currentPeriodLabel: 'Periodo supervisado',
      retryImportButton: 'Relanzar import',
      noCompanyTitle: 'Selecciona una empresa gestionada',
      noCompanyDetail: 'Elige una empresa gestionada arriba para activar la portada ejecutiva del periodo.',
      errorTitle: 'No se pudo cargar la portada',
      errorDetail: 'Revisa conexión, permisos o estado del backend antes de seguir.',
      nextActionFallback: 'Elige una empresa gestionada para activar la supervisión del periodo.',
      followupLabel: 'Seguimiento consultivo',
      followupButton: 'Supervisar cierre',
      periodStepCta: 'Supervisar'
    }
  }

  if (role === 'CLIENTE') {
    return {
      pageSubtitle: 'La lectura corta del periodo para entender avance, riesgos y próximos pasos.',
      currentPeriodLabel: 'Periodo visible',
      retryImportButton: 'Ver reintento',
      noCompanyTitle: 'Selecciona una empresa gestionada',
      noCompanyDetail: 'Elige una empresa gestionada arriba para activar la lectura ejecutiva del periodo.',
      errorTitle: 'No se pudo cargar la portada',
      errorDetail: 'Revisa la conexión o vuelve a intentarlo en unos segundos.',
      nextActionFallback: 'Elige una empresa gestionada para ver el siguiente paso del periodo.',
      followupLabel: 'Seguimiento ejecutivo',
      followupButton: 'Ver cierre',
      periodStepCta: 'Ver estado'
    }
  }

  return {
    pageSubtitle: 'La portada corta para operar, detectar riesgos y preparar el siguiente entregable.',
    currentPeriodLabel: 'Periodo actual',
    retryImportButton: 'Reintentar import',
    noCompanyTitle: 'Selecciona una empresa gestionada',
    noCompanyDetail: 'Elige una empresa gestionada arriba para activar la hoja de ruta del periodo.',
    errorTitle: 'No se pudo cargar la portada',
    errorDetail: 'Revisa la conexión, los permisos o vuelve a intentarlo en unos segundos.',
    nextActionFallback: 'Elige una empresa gestionada para activar la hoja de ruta.',
    followupLabel: 'Seguimiento consultivo',
    followupButton: 'Ver cierre',
    periodStepCta: 'Seguir cierre'
  }
}

type SummaryCardProps = {
  eyebrow: string
  title: string
  detail: string
  href?: string
  cta?: string
  className?: string
  tone?: 'default' | 'primary' | 'success' | 'warning'
}

function SummaryCard({ eyebrow, title, detail, href, cta, className, tone = 'default' }: SummaryCardProps) {
  return (
    <div className={`card soft card-pad-sm overview-card overview-card-${tone} ${className || ''}`.trim()}>
      <div className="overview-card-eyebrow">{eyebrow}</div>
      <div className="overview-card-title mt-1">{title}</div>
      <div className="overview-card-detail mt-1">{detail}</div>
      {href && cta ? (
        <div className="row row-wrap gap-8 mt-2">
          <Link className="badge" to={href}>
            {cta}
          </Link>
        </div>
      ) : null}
    </div>
  )
}

export default function OverviewPage() {
  const { id: companyId, plan } = useCompanySelection()
  const toast = useToast()
  const navigate = useNavigate()
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const role = getUserRole()
  const viewerRole = normalizeViewerRole(role)
  const copy = overviewRoleCopy(viewerRole)
  const isClient = role === 'CLIENTE'
  const isConsultor = role === 'CONSULTOR'

  const monthsCount = plan === 'PLATINUM' ? 12 : plan === 'GOLD' ? 9 : 6
  const months = lastMonths(monthsCount)
  const from = months[0]
  const to = months[months.length - 1]
  const workPeriod = useMemo(() => (companyId ? getWorkPeriod(companyId) : null) || to, [companyId, to])
  const workflowHref = `/monthly-close?period=${encodeURIComponent(workPeriod)}`

  const { data: dashboard, isLoading: dashboardLoading, error: dashboardError } = useQuery({
    queryKey: ['overview-dashboard', companyId, from, to],
    queryFn: () => getDashboard(companyId as number, from, to),
    enabled: !!companyId
  })

  const { data: recommendationFollowUps } = useQuery({
    queryKey: ['overview-recommendation-followups', companyId, workPeriod],
    queryFn: () => getRecommendationFollowUps(companyId as number, workPeriod),
    enabled: !!companyId && !!workPeriod
  })

  const { data: ingestion, isLoading: ingestionLoading } = useQuery({
    queryKey: ['overview-ingestion', companyId],
    queryFn: () => getIngestionStatus(companyId as number),
    enabled: !!companyId
  })

  const { data: pipelineSummary } = useQuery({
    queryKey: ['overview-pipeline', companyId],
    queryFn: () => getPipelineSummary(companyId as number),
    enabled: !!companyId && !isClient
  })

  const { data: checklist } = useQuery({
    queryKey: ['overview-checklist', companyId, workPeriod],
    queryFn: () => getChecklist(companyId as number, workPeriod),
    enabled: !!companyId
  })

  const { data: workPeriodWorkflow } = useQuery({
    queryKey: ['overview-period-workflow', companyId, workPeriod],
    queryFn: () => getPeriodWorkflow(companyId as number, workPeriod),
    enabled: !!companyId && !!workPeriod,
    retry: false
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

  const latest = dashboard?.kpis?.[dashboard?.kpis.length - 1]
  const hasCashData = (dashboard?.kpis || []).length > 0
  const overviewLoading = !!companyId && dashboardLoading
  const workFollowUps = (recommendationFollowUps || []) as AdvisorActionFollowUp[]
  const openFollowUps = workFollowUps.filter((item) => String(item.status || '').toUpperCase() !== 'RESOLVED')
  const topOpenFollowUps = openFollowUps.slice(0, 3)
  const lastImport = ingestion?.lastImport || null
  const canRetry = !isClient && !!companyId && !!lastImport?.id && (lastImport.status === 'DEAD' || lastImport.status === 'ERROR')
  const showWizard = false

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
        desc: 'Cualquier CSV/XLSX: presupuesto, ventas, inventario, nóminas...',
        sampleHref: '/samples/plantilla-universal.csv',
        guideModule: 'universal',
        importsHref: '/imports?mode=universal',
        dashboardHref: '/universal'
      },
      tribunal: {
        label: 'Tribunal',
        desc: 'Cartera y cumplimiento para la empresa gestionada.',
        sampleHref: '/samples/plantilla-tribunal.csv',
        guideModule: 'tribunal',
        importsHref: '/imports?mode=auto',
        dashboardHref: '/tribunal'
      },
      presupuesto: {
        label: 'Plan anual',
        desc: 'XLSX anual con meses para lectura consultiva y comparativa posterior.',
        sampleHref: '/samples/presupuesto-ejemplo.xlsx',
        guideModule: 'presupuesto',
        importsHref: '/imports?mode=universal',
        dashboardHref: '/budget'
      }
    } as const
  }, [])

  const selectedMeta = wizardMeta[wizardModule]
  const wizardDoneCount = Number(wizardProgress.step1) + Number(wizardProgress.step2) + Number(wizardProgress.step3)

  const pipelineHeadline = !companyId || isClient
    ? null
    : pipelineSummary?.errorFiles
      ? {
          title: 'Bandeja con incidencias',
          detail: `${pipelineSummary.errorFiles} fichero(s) han fallado y conviene corregirlos antes de cerrar.`,
          cta: 'Abrir bandeja',
          href: '/pipeline'
        }
      : pipelineSummary?.processingFiles || pipelineSummary?.pendingFiles
        ? {
            title: 'Bandeja en marcha',
            detail: `${(pipelineSummary.processingFiles || 0) + (pipelineSummary.pendingFiles || 0)} fichero(s) siguen en curso.`,
            cta: 'Seguir bandeja',
            href: '/pipeline'
          }
        : null

  const workPortfolioStep = workPeriodWorkflow?.portfolioStep || null
  const officialWorkflowSummary = workPeriodWorkflow
    ? {
        title: workPeriodWorkflow.statusTitle || 'Workflow activo',
        detail: workPeriodWorkflow.statusDetail || workPeriodWorkflow.notes || 'El periodo ya tiene un estado oficial de cierre.',
        cta: workPeriodWorkflow.primaryActionLabel || copy.periodStepCta
      }
    : null

  const rawNextAction = !companyId
    ? null
    : showWizard
      ? {
          title: 'Completa el arranque',
          detail: 'Descarga un ejemplo, sube un fichero y valida la primera lectura.',
          href: '/imports',
          cta: 'Empezar'
        }
      : workPeriodWorkflow?.status === 'CLOSED'
        ? {
            title: workPeriodWorkflow.statusTitle || 'Cierre ya materializado',
            detail: workPeriodWorkflow.statusDetail || workPeriodWorkflow.notes || `El periodo ${workPeriod} ya quedó cerrado y trazado.`,
            href: workflowHref,
            cta: workPeriodWorkflow.primaryActionLabel || 'Ver cierre'
          }
        : workPeriodWorkflow?.reviewedAt && workPeriodWorkflow?.reportStatus === 'READY' && !workPeriodWorkflow?.closedAt
          ? {
              title: 'Autocierre disponible',
              detail: workPeriodWorkflow.notes || `El informe de ${workPeriod} ya está listo y el cierre puede materializarse.`,
              href: workflowHref,
              cta: 'Seguir cierre'
            }
          : workPortfolioStep?.applicable && workPortfolioStep?.status === 'PENDING'
            ? {
                title: 'Cartera pendiente',
                detail: workPortfolioStep.detail || `El periodo ${workPeriod} sigue sin cartera cargada en Tribunal.`,
                href: workflowHref,
                cta: 'Completar cartera'
              }
            : workPortfolioStep?.applicable && workPortfolioStep?.status === 'STALE'
              ? {
                  title: 'Cartera desactualizada',
                  detail: workPortfolioStep.detail || `La cartera de ${workPeriod} ha quedado por detrás del cierre.`,
                  href: workflowHref,
                  cta: 'Actualizar cartera'
                }
              : pipelineSummary?.errorFiles
                ? {
                    title: 'Revisa la bandeja automática',
                    detail: 'Hay ficheros con error en el pipeline y conviene resolverlos antes de seguir.',
                    href: '/pipeline',
                    cta: 'Abrir bandeja'
                  }
                : !hasCashData
                  ? {
                      title: 'Carga el primer dataset',
                      detail: 'Sin transacciones no puedo calcular KPIs ni liquidez.',
                      href: '/imports',
                      cta: 'Cargar datos'
                    }
                  : canRetry
                    ? {
                        title: 'Recupera la última ingesta',
                        detail: 'El último import falló o quedó en error. Reinténtalo antes de seguir.',
                        href: workflowHref,
                        cta: 'Abrir cierre'
                      }
                    : workPeriodWorkflow?.reportStatus !== 'READY'
                      ? {
                          title: 'Materializa el entregable',
                          detail: 'La base ya existe. El siguiente paso natural es cerrar lectura y preparar el informe.',
                          href: workflowHref,
                          cta: 'Abrir cierre'
                        }
                      : {
                          title: 'Revisa el periodo actual',
                          detail: 'La foto ejecutiva ya está lista. Entra al workflow principal del periodo.',
                          href: workflowHref,
                          cta: 'Abrir cierre'
                        }

  const nextAction = useMemo(() => {
    if (!companyId || showWizard) return rawNextAction
    if (workPeriodWorkflow?.status === 'CLOSED') {
      return {
        title: workPeriodWorkflow.orchestrationTitle || workPeriodWorkflow.statusTitle || 'Cierre ya materializado',
        detail:
          workPeriodWorkflow.orchestrationDetail ||
          workPeriodWorkflow.statusDetail ||
          workPeriodWorkflow.notes ||
          `El periodo ${workPeriod} ya quedo cerrado y trazado.`,
        href: workflowHref,
        cta: workPeriodWorkflow.orchestrationActionLabel || workPeriodWorkflow.primaryActionLabel || 'Ver cierre'
      }
    }
    if (workPeriodWorkflow?.autoCloseReady) {
      return {
        title: workPeriodWorkflow.orchestrationTitle || 'Autocierre disponible',
        detail:
          workPeriodWorkflow.orchestrationDetail ||
          workPeriodWorkflow.notes ||
          `El informe de ${workPeriod} ya esta listo y el cierre puede materializarse.`,
        href: workflowHref,
        cta: copy.periodStepCta
      }
    }
    if (workPeriodWorkflow?.orchestrationRunnable) {
      return {
        title: workPeriodWorkflow.orchestrationTitle || 'Automatizacion disponible',
        detail:
          workPeriodWorkflow.orchestrationDetail ||
          workPeriodWorkflow.statusDetail ||
          workPeriodWorkflow.notes ||
          'El workflow ya esta listo para automatizar el siguiente tramo del periodo.',
        href: workflowHref,
        cta: copy.periodStepCta
      }
    }
    return rawNextAction
  }, [companyId, copy.periodStepCta, rawNextAction, showWizard, workPeriod, workPeriodWorkflow, workflowHref])

  const checklistDoneCount = (checklist?.items || []).filter((item: any) => item.done).length
  const checklistTotal = (checklist?.items || []).length

  const officialStateCard = useMemo(() => {
    if (officialWorkflowSummary) {
      return {
        title: officialWorkflowSummary.title,
        detail: officialWorkflowSummary.detail,
        cta: officialWorkflowSummary.cta || copy.periodStepCta
      }
    }

    if (checklistTotal) {
      return checklistDoneCount === checklistTotal
        ? {
            title: 'Lectura validada',
            detail: 'La revisión mínima del periodo está completa y el flujo puede continuar.',
            cta: copy.periodStepCta
          }
        : {
            title: 'Lectura en curso',
            detail: `${checklistDoneCount}/${checklistTotal} comprobaciones completadas.`,
            cta: copy.periodStepCta
          }
    }

    return hasCashData
      ? {
          title: 'Base operativa disponible',
          detail: 'Ya existe base suficiente para revisar el periodo.',
          cta: copy.periodStepCta
        }
      : {
          title: 'Carga pendiente',
          detail: 'Todavía no hay una base operativa suficiente para este periodo.',
          cta: copy.periodStepCta
        }
  }, [checklistDoneCount, checklistTotal, copy.periodStepCta, hasCashData, officialWorkflowSummary])

  const supportCard = useMemo(() => {
    if (openFollowUps.length) {
      return {
        eyebrow: copy.followupLabel,
        title: openFollowUps.length === 1 ? '1 acción abierta' : `${openFollowUps.length} acciones abiertas`,
        detail:
          topOpenFollowUps[0]?.carriedOver && topOpenFollowUps[0]?.originPeriod
            ? `La prioridad actual viene arrastrada desde ${topOpenFollowUps[0].originPeriod}.`
            : 'El periodo ya tiene seguimiento consultivo pendiente de revisar.',
        href: workflowHref,
        cta: copy.followupButton,
        tone: 'warning' as const
      }
    }

    if (!isClient && pipelineHeadline) {
      return {
        eyebrow: 'Bandeja automática',
        title: pipelineHeadline.title,
        detail: pipelineHeadline.detail,
        href: pipelineHeadline.href,
        cta: pipelineHeadline.cta,
        tone: 'warning' as const
      }
    }

    if (workPortfolioStep?.applicable && (workPortfolioStep.status === 'PENDING' || workPortfolioStep.status === 'STALE')) {
      return {
        eyebrow: 'Cartera',
        title: workPortfolioStep.title,
        detail: workPortfolioStep.detail || 'La cartera del periodo necesita intervención.',
        href: workflowHref,
        cta: 'Seguir cierre',
        tone: 'warning' as const
      }
    }

    return null
  }, [copy.followupButton, copy.followupLabel, isClient, openFollowUps.length, pipelineHeadline, topOpenFollowUps, workPortfolioStep, workflowHref])

  const quickSignal = useMemo(() => {
    if (!hasCashData) return null
    const liquidityText = latest
      ? `Liquidez ${formatMoney(latest.netFlow)} con entradas ${formatMoney(latest.inflows)} y salidas ${formatMoney(latest.outflows)}.`
      : 'Sin lectura de caja disponible.'
    const validationText = checklistTotal
      ? checklistDoneCount === checklistTotal
        ? 'Validacion minima completa.'
        : `Validacion en curso: ${checklistDoneCount}/${checklistTotal} comprobaciones.`
      : 'Aun no hay checklist activo para este periodo.'
    return `${liquidityText} ${validationText}`
  }, [checklistDoneCount, checklistTotal, hasCashData, latest])

  const heroTone: SummaryCardProps['tone'] =
    !!workPeriodWorkflow?.autoCloseReady || workPeriodWorkflow?.status === 'CLOSED'
      ? 'success'
      : pipelineSummary?.errorFiles || workPortfolioStep?.status === 'PENDING' || workPortfolioStep?.status === 'STALE'
        ? 'warning'
        : 'primary'

  const showQuickStart = !!companyId && !dashboardError && !overviewLoading && !hasCashData
  function renderPanelState(title: string, detail?: string, tone: 'default' | 'loading' | 'locked' = 'default', className = 'mt-3') {
    return (
      <div className={`panel-state panel-state-${tone} ${className}`.trim()}>
        <div className="panel-state-title">{title}</div>
        {detail ? <div className="panel-state-detail">{detail}</div> : null}
      </div>
    )
  }

  return (
    <div className="overview-page">
      <PageHeader
        title="Vista ejecutiva"
        subtitle={copy.pageSubtitle}
        actions={
          <div className="row row-wrap gap-8">
            <span className="pill">{copy.currentPeriodLabel}: {workPeriod}</span>
            {canRetry ? (
              <Button
                size="sm"
                variant="ghost"
                onClick={async () => {
                  try {
                    await retryImport(companyId as number, lastImport!.id)
                    toast.push({ tone: 'success', title: 'Import', message: 'Reintento encolado. Revisa Caja en 1-2 minutos.' })
                  } catch (e: any) {
                    toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo reintentar.' })
                  }
                }}
              >
                {copy.retryImportButton}
              </Button>
            ) : null}
          </div>
        }
      />

      {!companyId ? renderPanelState(copy.noCompanyTitle, copy.noCompanyDetail, 'default', 'mb-3') : null}
      {companyId && dashboardError ? renderPanelState(copy.errorTitle, copy.errorDetail, 'default', 'mb-3') : null}

      {showQuickStart ? (
        <div className="section">
          {showWizard ? (
            <div className="card wizard">
              <div className="wizard-head">
                <div>
                  <div className="wizard-title">Arranque rápido</div>
                  <div className="wizard-sub">Esta empresa aún no tiene datos. Elige un módulo y sigue estos 3 pasos.</div>
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
                <Button type="button" size="sm" variant={wizardModule === 'universal' ? 'secondary' : 'ghost'} onClick={() => setWizardModule('universal')}>
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
                <Button type="button" size="sm" variant={wizardModule === 'presupuesto' ? 'secondary' : 'ghost'} onClick={() => setWizardModule('presupuesto')}>
                  Plan anual
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
                    <a className="btn btn-secondary btn-sm" href={selectedMeta.sampleHref} download onClick={() => markWizardStep(1)}>
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
                  </div>
                </div>

                <div className="wizard-step">
                  <div className="wizard-step-head">
                    <span className={`wizard-step-num ${wizardProgress.step3 ? 'done' : ''}`}>{wizardProgress.step3 ? 'OK' : '3'}</span>
                    <div>
                      <div className="wizard-step-title">Valida el resultado</div>
                      <div className="wizard-step-sub">Confirma KPIs e insights antes de enseñarlo al cliente final.</div>
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
          ) : (
            <div className="card soft">
              <div className="mini-row row-baseline">
                <h3 className="m-0">Primer paso</h3>
                <Link className="badge" to="/guides">
                  Ver guia
                </Link>
              </div>
              <div className="upload-hint mt-8">Todavía no hay datos. Empieza cargando un fichero y luego valida el periodo desde cierre mensual.</div>
              <div className="overview-start-actions">
                <Link className="badge" to="/imports">
                  Cargar datos
                </Link>
              </div>
            </div>
          )}
        </div>
      ) : null}

      {overviewLoading ? (
        <div className="overview-modules section" aria-label="Cargando portada">
          {Array.from({ length: 2 }).map((_, idx) => (
            <div className="card" key={idx}>
              <Skeleton className="sk-w-56p sk-h-14" />
              <Skeleton className="sk-w-82p sk-h-12 mt-3" />
              <Skeleton className="sk-w-68p sk-h-12 mt-2" />
              <div className="row gap-2 mt-4">
                <Skeleton className="sk-w-86 sk-h-28 radius-pill" />
              </div>
            </div>
          ))}
        </div>
      ) : null}

      {companyId && !overviewLoading && !dashboardError ? (
        <section className="section overview-block">
          <div className="mini-row row-baseline">
            <h3 className="m-0">Ahora</h3>
            <span className="upload-hint">Un siguiente paso claro y solo el contexto minimo para ejecutarlo.</span>
          </div>
          <div className="grid grid-autofit-220 mt-8 overview-focus-grid">
            <SummaryCard
              eyebrow="Siguiente paso"
              title={nextAction?.title || 'Selecciona una empresa gestionada'}
              detail={nextAction?.detail || copy.nextActionFallback}
              href={nextAction?.href}
              cta={nextAction?.cta}
              className="overview-card-hero"
              tone={heroTone}
            />
            <SummaryCard
              eyebrow="Estado oficial"
              title={officialStateCard.title}
              detail={officialStateCard.detail}
              href={workflowHref}
              cta={officialStateCard.cta}
              tone="primary"
            />
          </div>

          {quickSignal && !supportCard ? (
            <div className="overview-inline-note mt-12">
              <div className="overview-inline-note-copy">
                <div className="overview-inline-note-label">Foto rapida</div>
                <div className="overview-inline-note-text">{quickSignal}</div>
              </div>
            </div>
          ) : null}

          {supportCard ? (
            <div className="overview-inline-note mt-12">
              <div className="overview-inline-note-copy">
                <div className="overview-inline-note-label">{supportCard.eyebrow}</div>
                <div className="overview-inline-note-text">
                  <strong>{supportCard.title}.</strong> {supportCard.detail}
                </div>
              </div>
              {supportCard.href && supportCard.cta ? (
                <Link className="badge" to={supportCard.href}>
                  {supportCard.cta}
                </Link>
              ) : null}
            </div>
          ) : null}
        </section>
      ) : null}

    </div>
  )
}


