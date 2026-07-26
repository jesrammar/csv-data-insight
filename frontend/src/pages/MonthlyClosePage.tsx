// @ts-nocheck
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import {
  closePeriodWorkflow,
  generateReport,
  getAlerts,
  getChecklist,
  getImportQuality,
  getImports,
  getPeriodWorkflow,
  getRecommendationFollowUps,
  getRecommendationSnapshotByPeriod,
  getReports,
  getTribunalStatus,
  getUserRole,
  listUniversalViews,
  reviewPeriodWorkflow,
  runPeriodCloseFlow,
  updateRecommendationFollowUpStatus,
  type AdvisorActionFollowUp,
  type AdvisorRecommendationSnapshot,
  type ImportJob,
  type ImportQualityDto,
  type PeriodWorkflowDto,
  type PortfolioWorkflowStepDto,
  type TribunalImportDto,
  type UniversalViewDto
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import { getWorkPeriod, nowYm, setWorkPeriod } from '../utils/workPeriod'
import { EMPTY_VALUE, formatDateTime } from '../utils/format'
import {
  followUpBadgeTone,
  followUpStatusLabel,
  importStatusBadgeTone,
  portfolioStepActionLabel,
  portfolioStepBadgeTone,
  workflowBadgeTone
} from '../utils/presentation'
import { universalAggregationExecutiveLine, universalAggregationModeLabel } from '../utils/universalAggregation'
import { useEffect, useMemo, useState } from 'react'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'

type ExceptionInboxEntry = {
  severity: 'HIGH' | 'MEDIUM' | 'LOW'
  code: string
  title: string
  detail: string
  action: string
  evidence?: string
}

type WorkflowGap = {
  tone: 'danger' | 'warning' | 'info' | 'success'
  title: string
  detail: string
}

type PriorityStep =
  | 'resolve_exceptions'
  | 'review'
  | 'report'
  | 'followups'
  | 'portfolio'
  | 'close'
  | 'closed'

type ViewerRole = 'ADMIN' | 'CONSULTOR' | 'CLIENTE'

function stepDone(current?: string | null, target?: string) {
  const order = ['PENDING_DATA', 'INGESTING', 'EXCEPTIONS', 'READY_FOR_REVIEW', 'REVIEWED', 'REPORT_GENERATING', 'REPORT_READY', 'CLOSED']
  const currentIdx = order.indexOf(String(current || ''))
  const targetIdx = order.indexOf(String(target || ''))
  return currentIdx >= 0 && targetIdx >= 0 && currentIdx >= targetIdx
}

function severityBadgeClass(severity: string) {
  const normalized = String(severity || '').toUpperCase()
  return normalized === 'HIGH' ? 'err' : normalized === 'MEDIUM' ? 'warn' : 'ok'
}

function formatRate(value: number | null | undefined, total: number | null | undefined) {
  const safeValue = Number(value || 0)
  const safeTotal = Number(total || 0)
  if (!safeTotal) return null
  return `${Math.round((safeValue / safeTotal) * 100)}%`
}

function blockingMeta(code: string | null | undefined) {
  switch (String(code || '').toUpperCase()) {
    case 'DUPLICATE_CONTENT_HASH':
      return {
        title: 'El fichero ya se había cargado exactamente igual.',
        action: 'No lo reproceses. Revisa si esa versión ya está aplicada y sube solo una variante real.'
      }
    case 'DUPLICATE_NORMALIZED_HASH':
      return {
        title: 'Los datos efectivos coinciden con una versión ya aplicada.',
        action: 'Evita repetir el mismo periodo. Si hay cambios reales, sube una versión corregida.'
      }
    case 'MIN_VALID_ROWS':
      return {
        title: 'No hay suficientes filas válidas para confiar en la carga.',
        action: 'Reexporta el Excel/CSV, revisa cabeceras y valida fecha e importe antes de volver a subirlo.'
      }
    case 'HIGH_WARNING_RATE':
      return {
        title: 'La tasa de incidencias supera el umbral de calidad.',
        action: 'Limpia el origen o corrige el fichero antes de regenerar el periodo.'
      }
    case 'OUTSIDE_PERIOD_ROWS':
      return {
        title: 'Hay movimientos fuera del periodo declarado.',
        action: 'Separa por meses o corrige el periodo objetivo antes de reimportar.'
      }
    case 'HIGH_DUPLICATE_RATE':
      return {
        title: 'El fichero trae demasiados duplicados.',
        action: 'Depura duplicados en origen para no inflar KPIs, saldos y tendencias.'
      }
    default:
      return {
        title: 'El import quedó bloqueado por reglas de ingestión.',
        action: 'Revisa la causa y vuelve a subir una versión corregida del fichero.'
      }
  }
}

function qualityIssueAction(code: string | null | undefined) {
  switch (String(code || '').toUpperCase()) {
    case 'MISSING_TXN_DATE':
    case 'DATE_PARSE_ERRORS':
      return 'Normaliza la columna de fecha y asegúrate de que todas las filas usan un formato consistente.'
    case 'MISSING_AMOUNT':
    case 'AMOUNT_PARSE_ERRORS':
      return 'Convierte importes a número limpio, sin texto embebido ni separadores ambiguos.'
    case 'OUTSIDE_PERIOD_ROWS':
      return 'Saca del fichero los asientos de otros meses o cambia el periodo de carga.'
    case 'DUPLICATE_ROWS':
      return 'Elimina movimientos repetidos para no contaminar KPIs ni saldos.'
    case 'MISSING_COUNTERPARTY':
      return 'Completa la contraparte si quieres una lectura más útil de clientes y proveedores.'
    case 'BALANCE_END_MISMATCH':
      return 'Revisa el saldo final informado porque no cuadra con la secuencia de movimientos.'
    default:
      return 'Revisa esta incidencia antes de dar por buena la carga o cerrar el periodo.'
  }
}

function qualitySummary(q: ImportQualityDto | null | undefined) {
  if (!q) return null
  const issues = Array.isArray(q.issues) ? q.issues : []
  const high = issues.filter((i) => String(i.severity).toUpperCase() === 'HIGH').length
  const med = issues.filter((i) => String(i.severity).toUpperCase() === 'MEDIUM').length
  const low = issues.filter((i) => String(i.severity).toUpperCase() === 'LOW').length
  const badge = high ? 'err' : med ? 'warn' : low ? 'warn' : 'ok'
  const label = high ? `${high} crítico` : med ? `${med} medio` : low ? `${low} leve` : 'OK'
  return { badge, label }
}

function buildImportExceptionEntries(imp: ImportJob): ExceptionInboxEntry[] {
  const entries: ExceptionInboxEntry[] = []
  const warningRate = formatRate((imp.rowsReceived ?? 0) - (imp.rowsValid ?? 0), imp.rowsReceived)

  if (imp.status === 'BLOCKED') {
    const meta = blockingMeta(imp.blockingCode)
    entries.push({
      severity: 'HIGH',
      code: imp.blockingCode || 'BLOCKED',
      title: meta.title,
      detail: imp.blockingReason || 'La carga incumple una regla dura de ingestión para este periodo.',
      action: meta.action,
      evidence:
        [
          imp.versionNo ? `Versión ${imp.versionNo}` : null,
          imp.rowsValid != null && imp.rowsReceived != null ? `${imp.rowsValid}/${imp.rowsReceived} filas válidas` : null,
          imp.duplicateOfImportId ? `Duplica al import #${imp.duplicateOfImportId}` : null
        ]
          .filter(Boolean)
          .join(' · ') || undefined
    })
  }

  if (imp.status === 'ERROR' || imp.status === 'DEAD') {
    entries.push({
      severity: 'HIGH',
      code: imp.status,
      title: 'La ingestión no pudo completar el periodo.',
      detail: imp.errorSummary || imp.lastError || 'El proceso terminó con error técnico y no dejó una base utilizable.',
      action: 'Revisa el fichero fuente, corrige el problema y vuelve a subirlo.',
      evidence: imp.attempts != null ? `Intentos ${imp.attempts}/${imp.maxAttempts ?? 3}` : undefined
    })
  }

  if (imp.status === 'WARNING') {
    entries.push({
      severity: 'MEDIUM',
      code: 'WARNING_IMPORT',
      title: 'El import terminó, pero dejó incidencias que merecen revisión.',
      detail:
        imp.errorSummary ||
        imp.lastError ||
        'La carga se procesó con avisos y puede introducir ruido en la lectura del periodo.',
      action: 'Valida las incidencias y decide si basta con aceptar la carga o conviene rehacerla.',
      evidence:
        [
          imp.warningCount ? `${imp.warningCount} warnings` : null,
          warningRate ? `${warningRate} de filas con incidencias` : null,
          imp.supersedesImportId ? `Sustituye al import #${imp.supersedesImportId}` : null
        ]
          .filter(Boolean)
          .join(' · ') || undefined
    })
  }

  return entries
}

function normalizeViewerRole(role?: string | null): ViewerRole {
  const normalized = String(role || '').toUpperCase()
  if (normalized === 'ADMIN' || normalized === 'CLIENTE') return normalized
  return 'CONSULTOR'
}

function monthlyRoleCopy(role: ViewerRole) {
  if (role === 'ADMIN') {
    return {
      pageSubtitle: 'Estado oficial del periodo y siguiente paso.',
      periodHint: 'Un periodo, un estado y una lectura clara.',
      priorityHint: 'La acción que más acerca el periodo al cierre.',
      gapsHint: 'Lo mínimo para ver qué falta.',
      snapshotHint: 'Qué contar y qué seguir.',
      exceptionsHint: 'Bloqueos y avisos del periodo.',
      actionsHint: 'Secuencia corta del cierre.',
      traceHint: 'Resumen rápido del periodo.',
      reviewButton: 'Validar cierre',
      reportButton: 'Materializar entregable',
      closeButton: 'Consolidar cierre',
      openOverview: 'Supervisar overview',
      openReports: 'Supervisar informes',
      openImports: 'Supervisar imports',
      openSnapshot: 'Ver seguimiento',
      openPortfolio: 'Revisar cartera',
      noCompanyAlert: 'Selecciona una empresa para supervisar el cierre mensual.',
      workflowMissingTitle: 'Workflow pendiente de gobierno',
      workflowMissingDetail: 'Primero hace falta una carga válida para este periodo.',
      qualityErrorTitle: 'No se pudo revisar la calidad',
      emptyExceptions: 'Cuando exista un import, aquí aparecerán bloqueos y avisos.',
      cleanExceptions: 'Sin excepciones abiertas.',
      snapshotMissingTitle: 'Aún no hay snapshot consultivo enlazado'
    }
  }
  if (role === 'CLIENTE') {
    return {
      pageSubtitle: 'Qué está listo, qué falta y qué sigue.',
      periodHint: 'El estado ejecutivo del periodo en una sola pantalla.',
      priorityHint: 'La acción más clara para avanzar.',
      gapsHint: 'Lo que hoy frena el cierre.',
      snapshotHint: 'Qué significa el periodo.',
      exceptionsHint: 'Incidencias visibles del periodo.',
      actionsHint: 'Secuencia corta del cierre.',
      traceHint: 'Resumen rápido del periodo.',
      reviewButton: 'Ver revisión',
      reportButton: 'Ver entregable',
      closeButton: 'Ver estado de cierre',
      openOverview: 'Ver overview',
      openReports: 'Ver informes',
      openImports: 'Ver imports',
      openSnapshot: 'Ver lectura consultiva',
      openPortfolio: 'Ver cartera',
      noCompanyAlert: 'Selecciona una empresa para seguir el cierre mensual.',
      workflowMissingTitle: 'Cierre aún no iniciado',
      workflowMissingDetail: 'Primero hace falta una carga válida para este periodo.',
      qualityErrorTitle: 'No se pudo mostrar la calidad',
      emptyExceptions: 'Cuando exista un import, aquí verás incidencias del periodo.',
      cleanExceptions: 'Sin incidencias abiertas.',
      snapshotMissingTitle: 'Aún no hay lectura consultiva enlazada'
    }
  }
  return {
    pageSubtitle: 'Estado oficial del periodo y siguiente paso.',
    periodHint: 'Todo el cierre en una sola pantalla.',
    priorityHint: 'La acción que más valor desbloquea ahora.',
    gapsHint: 'Lo mínimo para ver qué falta.',
    snapshotHint: 'Qué contar y qué mover después.',
    exceptionsHint: 'Bloqueos y avisos del mes.',
    actionsHint: 'Secuencia corta del cierre.',
    traceHint: 'Resumen rápido del periodo.',
    reviewButton: 'Marcar revisado',
    reportButton: 'Generar entregable',
    closeButton: 'Cerrar periodo',
    openOverview: 'Abrir overview',
    openReports: 'Abrir informes',
    openImports: 'Abrir imports',
    openSnapshot: 'Abrir seguimiento',
    openPortfolio: 'Abrir cartera',
    noCompanyAlert: 'Selecciona una empresa arriba para trabajar el cierre mensual.',
    workflowMissingTitle: 'Workflow aún no disponible',
    workflowMissingDetail: 'Primero hace falta una carga válida para este periodo.',
    qualityErrorTitle: 'No se pudo calcular la calidad',
    emptyExceptions: 'Cuando exista un import, aquí aparecerán bloqueos y avisos.',
    cleanExceptions: 'Sin excepciones abiertas.',
    snapshotMissingTitle: 'Aún no hay snapshot consultivo enlazado'
  }
}

export default function MonthlyClosePage() {
  const { id: companyId, plan } = useCompanySelection()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const [period, setPeriod] = useState(() => searchParams.get('period') || getWorkPeriod(companyId) || nowYm())
  const [selectedReportUniversalViewId, setSelectedReportUniversalViewId] = useState('')
  const role = getUserRole()
  const viewerRole = normalizeViewerRole(role)
  const copy = monthlyRoleCopy(viewerRole)
  const canManageFollowUps = role === 'CONSULTOR' || role === 'ADMIN'

  useEffect(() => {
    const fromQuery = searchParams.get('period')
    if (fromQuery) setPeriod(fromQuery)
  }, [searchParams])

  useEffect(() => {
    if (!companyId) return
    setWorkPeriod(companyId, period)
  }, [companyId, period])

  useEffect(() => {
    setSelectedReportUniversalViewId('')
  }, [companyId])

  const workflowQuery = useQuery({
    queryKey: ['period-workflow', companyId, period],
    queryFn: () => getPeriodWorkflow(companyId as number, period),
    enabled: !!companyId && !!period,
    retry: false
  })

  const importsQuery = useQuery({
    queryKey: ['monthly-close-imports', companyId],
    queryFn: () => getImports(companyId as number),
    enabled: !!companyId
  })

  const reportsQuery = useQuery({
    queryKey: ['monthly-close-reports', companyId],
    queryFn: () => getReports(companyId as number),
    enabled: !!companyId
  })
  const universalViewsQuery = useQuery({
    queryKey: ['monthly-close-universal-views', companyId],
    queryFn: () => listUniversalViews(companyId as number),
    enabled: !!companyId && viewerRole !== 'CLIENTE'
  })

  const checklistQuery = useQuery({
    queryKey: ['monthly-close-checklist', companyId, period],
    queryFn: () => getChecklist(companyId as number, period),
    enabled: !!companyId && !!period
  })

  const alertsQuery = useQuery({
    queryKey: ['monthly-close-alerts', companyId, period],
    queryFn: () => getAlerts(companyId as number, period),
    enabled: !!companyId && !!period
  })
  const tribunalQuery = useQuery({
    queryKey: ['monthly-close-tribunal-status', companyId],
    queryFn: () => getTribunalStatus(companyId as number),
    enabled: !!companyId
  })
  const recommendationQuery = useQuery({
    queryKey: ['monthly-close-recommendation-snapshot', companyId, period],
    queryFn: () => getRecommendationSnapshotByPeriod(companyId as number, period),
    enabled: !!companyId && !!period
  })
  const followUpsQuery = useQuery({
    queryKey: ['monthly-close-recommendation-followups', companyId, period],
    queryFn: () => getRecommendationFollowUps(companyId as number, period),
    enabled: !!companyId && !!period
  })

  const workflow = workflowQuery.data || null
  const summary = {
    title: workflow?.statusTitle || 'Sin workflow',
    detail: workflow?.statusDetail || 'Sin workflow para este periodo.'
  }
  const importForPeriod = useMemo(() => {
    return ((importsQuery.data || []) as ImportJob[])
      .filter((item) => String(item.period || '') === String(period || ''))
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0] || null
  }, [importsQuery.data, period])

  const reportForPeriod = useMemo(() => {
    return ((reportsQuery.data || []) as any[])
      .filter((item) => String(item?.period || '') === String(period || ''))
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0] || null
  }, [reportsQuery.data, period])
  const qualityQuery = useQuery({
    queryKey: ['monthly-close-import-quality', companyId, importForPeriod?.id],
    queryFn: () => getImportQuality(companyId as number, importForPeriod?.id as number),
    enabled: !!companyId && !!importForPeriod?.id
  })

  const checklistDone = (checklistQuery.data?.items || []).filter((item) => item.done).length
  const checklistTotal = (checklistQuery.data?.items || []).length
  const alertsCount = Array.isArray(alertsQuery.data) ? alertsQuery.data.length : 0
  const importExceptions = useMemo(() => {
    if (!importForPeriod) return []
    return buildImportExceptionEntries(importForPeriod)
  }, [importForPeriod])
  const qualityExceptions = useMemo(() => {
    const quality = qualityQuery.data
    if (!quality) return []
    return (Array.isArray(quality.issues) ? quality.issues : []).map((issue) => ({
      severity: String(issue.severity || '').toUpperCase() === 'HIGH' ? 'HIGH' : String(issue.severity || '').toUpperCase() === 'MEDIUM' ? 'MEDIUM' : 'LOW',
      code: issue.code,
      title: issue.title,
      detail: issue.detail,
      action: qualityIssueAction(issue.code)
    })) as ExceptionInboxEntry[]
  }, [qualityQuery.data])
  const workflowExceptions = useMemo(() => {
    const entries: ExceptionInboxEntry[] = []
    if (workflow?.blockingReason || workflow?.status === 'EXCEPTIONS') {
      entries.push({
        severity: 'HIGH',
        code: String(workflow?.status || 'EXCEPTIONS'),
        title: 'El workflow del periodo está bloqueado por excepciones.',
        detail: workflow?.blockingReason || 'Todavía hay incidencias abiertas antes de poder revisar o cerrar el periodo.',
        action: 'Resuelve primero la carga o la calidad del periodo y vuelve a revisar el workflow.',
        evidence: workflow?.exceptionCount ? `${workflow.exceptionCount} incidencias agregadas` : undefined
      })
    }
    return entries
  }, [workflow])
  const exceptionEntries = useMemo(() => {
    return [...workflowExceptions, ...importExceptions, ...qualityExceptions].sort((a, b) => {
      const rank = (severity: string) => (severity === 'HIGH' ? 3 : severity === 'MEDIUM' ? 2 : 1)
      return rank(b.severity) - rank(a.severity)
    })
  }, [workflowExceptions, importExceptions, qualityExceptions])
  const exceptionSummary = useMemo(() => {
    const high = exceptionEntries.filter((entry) => entry.severity === 'HIGH').length
    const medium = exceptionEntries.filter((entry) => entry.severity === 'MEDIUM').length
    const low = exceptionEntries.filter((entry) => entry.severity === 'LOW').length
    return { high, medium, low }
  }, [exceptionEntries])
  const portfolioStep = workflow?.portfolioStep || null
  const tribunalStepState = useMemo(() => {
    return {
      applicable: !!portfolioStep?.applicable,
      status: String(portfolioStep?.status || 'NOT_APPLICABLE'),
      title: portfolioStep?.title || 'Estado de cartera',
      detail: portfolioStep?.detail || 'El workflow mostrara aqui el estado oficial de cartera para este periodo.',
      actionLabel: portfolioStepActionLabel(portfolioStep),
      badge: portfolioStepBadgeTone(portfolioStep)
    }
  }, [portfolioStep])
  const workflowGaps = useMemo<WorkflowGap[]>(() => {
    const gaps: WorkflowGap[] = []
    if (!importForPeriod) {
      gaps.push({
        tone: 'danger',
        title: 'Falta la base del periodo',
        detail: 'Todavia no existe un import valido para este mes. Sin esa base no hay cierre posible.'
      })
    } else if (!['OK', 'WARNING'].includes(String(importForPeriod.status || ''))) {
      gaps.push({
        tone: 'danger',
        title: `El import sigue en ${importForPeriod.status}`,
        detail: 'Hay que corregir o reintentar la ingestion antes de seguir con revision, informe o cierre.'
      })
    }

    if (exceptionEntries.length) {
      gaps.push({
        tone: exceptionSummary.high ? 'danger' : 'warning',
        title: `${exceptionEntries.length} excepciones abiertas`,
        detail: 'La bandeja de excepciones sigue teniendo incidencias operativas o de calidad que conviene resolver.'
      })
    }

    if (!workflow?.reviewedAt) {
      gaps.push({
        tone: checklistTotal && checklistDone < checklistTotal ? 'warning' : 'info',
        title: 'Falta la validacion del periodo',
        detail: checklistTotal
          ? `Checklist: ${checklistDone}/${checklistTotal}. Marca revision cuando la lectura funcional este cerrada.`
          : 'Todavia falta confirmar que la lectura del periodo es correcta antes de cerrar.'
      })
    }

    if (!reportForPeriod) {
      gaps.push({
        tone: 'info',
        title: 'Falta materializar el entregable',
        detail: 'Aun no existe informe para este periodo. Cuando lo generes, el workflow avanzara automaticamente.'
      })
    }

    if (!workflow?.recommendationSnapshotId) {
      gaps.push({
        tone: reportForPeriod ? 'warning' : 'info',
        title: 'La recomendacion consultiva aun no esta colgada al cierre',
        detail: 'El snapshot consultivo se enlaza con el workflow al dejar listo el informe o al cerrar el periodo.'
      })
    }

    if (portfolioStep?.applicable && portfolioStep.status !== 'LOADED') {
      gaps.push({
        tone: portfolioStep.status === 'PENDING' ? 'danger' : 'warning',
        title: portfolioStep.title,
        detail:
          portfolioStep.status === 'PENDING'
            ? 'El cierre todavia no tiene cartera cargada en Tribunal para este cliente.'
            : 'La cartera existe, pero ha quedado por detras del resto del cierre y conviene refrescarla.'
      })
    }

    if (!workflow?.closedAt) {
      gaps.push({
        tone: workflow?.reviewedAt && reportForPeriod ? 'info' : 'warning',
        title: 'El periodo sigue abierto',
        detail: workflow?.reviewedAt && reportForPeriod
          ? 'Si el informe quedo listo despues de la revision, el periodo deberia autocerrarse. Si no, puedes cerrarlo manualmente.'
          : 'Todavia quedan pasos pendientes antes de dar el periodo por cerrado.'
      })
    }

    return gaps
  }, [checklistDone, checklistTotal, exceptionEntries.length, exceptionSummary.high, importForPeriod, portfolioStep, reportForPeriod, workflow])
  const closureConsequences = useMemo(() => {
    const tribunal = tribunalQuery.data as TribunalImportDto | null | undefined
    return [
      {
        label: 'Recomendacion consultiva',
        ready: !!workflow?.recommendationSnapshotId,
        title: workflow?.recommendationSnapshotId ? 'Snapshot enlazado al workflow' : 'Pendiente de enlazar',
        detail: workflow?.recommendationSummary || 'Cuando el cierre madura, el workflow cuelga aqui la recomendacion del periodo.'
      },
      {
        label: 'Estado de cartera',
        ready: portfolioStep?.status === 'LOADED' || !portfolioStep?.applicable,
        title: portfolioStep?.title || 'Estado de cartera',
        detail: portfolioStep?.detail || (tribunal?.id
          ? `${tribunal.filename} · ${formatDateTime(tribunal.createdAt)}`
          : 'El workflow mostrara aqui el estado oficial de cartera para este periodo.')
      }
    ]
  }, [portfolioStep, tribunalQuery.data, workflow?.recommendationSnapshotId, workflow?.recommendationSummary])
  const recommendationActions = useMemo(() => {
    const snapshot = recommendationQuery.data as AdvisorRecommendationSnapshot | undefined
    return Array.isArray(snapshot?.actions) ? snapshot.actions.slice(0, 3) : []
  }, [recommendationQuery.data])
  const availableUniversalViews = (universalViewsQuery.data || []) as UniversalViewDto[]
  const selectedReportUniversalView = useMemo(() => {
    return availableUniversalViews.find((view) => String(view.id) === selectedReportUniversalViewId) || null
  }, [availableUniversalViews, selectedReportUniversalViewId])
  const recommendationFollowUps = useMemo(() => {
    return (followUpsQuery.data || []).slice(0, 3) as AdvisorActionFollowUp[]
  }, [followUpsQuery.data])
  const openFollowUps = useMemo(() => {
    return recommendationFollowUps.filter((item) => String(item.status || '').toUpperCase() !== 'RESOLVED')
  }, [recommendationFollowUps])
  const supportPanelOpen = !importForPeriod || workflow?.status === 'EXCEPTIONS' || exceptionSummary.high > 0

  useEffect(() => {
    if (!selectedReportUniversalViewId) return
    if (!availableUniversalViews.some((view) => String(view.id) === selectedReportUniversalViewId)) {
      setSelectedReportUniversalViewId('')
    }
  }, [availableUniversalViews, selectedReportUniversalViewId])

  const refreshAll = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['period-workflow', companyId, period] }),
      queryClient.invalidateQueries({ queryKey: ['monthly-close-imports', companyId] }),
      queryClient.invalidateQueries({ queryKey: ['monthly-close-reports', companyId] }),
      queryClient.invalidateQueries({ queryKey: ['monthly-close-checklist', companyId, period] }),
      queryClient.invalidateQueries({ queryKey: ['monthly-close-alerts', companyId, period] }),
      queryClient.invalidateQueries({ queryKey: ['monthly-close-recommendation-snapshot', companyId, period] }),
      queryClient.invalidateQueries({ queryKey: ['monthly-close-recommendation-followups', companyId, period] }),
      queryClient.invalidateQueries({ queryKey: ['reports', companyId] }),
      queryClient.invalidateQueries({ queryKey: ['imports', companyId] }),
      queryClient.invalidateQueries({ queryKey: ['portfolio-ops'] })
    ])
  }

  const reviewMutation = useMutation({
    mutationFn: () => reviewPeriodWorkflow(companyId as number, period),
    onSuccess: async () => {
        toast.push({ tone: 'success', title: 'Periodo revisado', message: `El workflow de ${period} ya quedó marcado como revisado.` })
      await refreshAll()
    },
    onError: (error: any) => {
      toast.push({ tone: 'danger', title: 'No se pudo revisar', message: error?.message || 'El workflow no se pudo revisar.' })
    }
  })

  const closeMutation = useMutation({
    mutationFn: () => closePeriodWorkflow(companyId as number, period),
    onSuccess: async () => {
        toast.push({ tone: 'success', title: 'Periodo cerrado', message: `El cierre mensual de ${period} ya quedó completado.` })
      await refreshAll()
    },
    onError: (error: any) => {
      toast.push({ tone: 'danger', title: 'No se pudo cerrar', message: error?.message || 'El workflow no se pudo cerrar.' })
    }
  })

  const reportMutation = useMutation({
    mutationFn: () => generateReport(companyId as number, period, selectedReportUniversalView ? selectedReportUniversalView.id : null),
    onSuccess: async () => {
      await refreshAll()
      let message = `Ya existe un entregable para ${period}.`
      try {
        const nextWorkflow = await getPeriodWorkflow(companyId as number, period)
        if (nextWorkflow?.status === 'CLOSED') {
          message = `Informe generado y periodo autocerrado para ${period}.`
        } else if (nextWorkflow?.recommendationSnapshotId) {
            message = `Informe generado y recomendación enlazada al cierre de ${period}.`
        }
      } catch {
        // ignore post-refresh lookup errors
      }
      if (selectedReportUniversalView?.name) {
        message += ` Vista Universal: ${selectedReportUniversalView.name}.`
      }
      toast.push({ tone: 'success', title: 'Informe generado', message })
    },
    onError: (error: any) => {
      toast.push({ tone: 'danger', title: 'No se pudo generar el informe', message: error?.message || 'Error generando el entregable.' })
    }
  })
  const orchestrateMutation = useMutation({
    mutationFn: () => runPeriodCloseFlow(companyId as number, period),
    onSuccess: async () => {
      toast.push({
        tone: 'success',
        title: 'Cierre automatico en cola',
        message: `La orquestacion oficial de ${period} ya ha quedado lanzada.`
      })
      await refreshAll()
    },
    onError: (error: any) => {
      const message = error?.message || 'No se pudo lanzar la orquestacion del periodo.'
      toast.push({
        tone: String(message).toLowerCase().includes('ya existe') ? 'warning' : 'danger',
        title: 'No se pudo automatizar',
        message
      })
    }
  })
  const followUpStatusMutation = useMutation({
    mutationFn: ({ followUpId, status }: { followUpId: number; status: 'PENDING' | 'IN_PROGRESS' | 'RESOLVED' }) =>
      updateRecommendationFollowUpStatus(companyId as number, followUpId, status),
    onSuccess: async (_, variables) => {
      const labels: Record<string, string> = {
        PENDING: 'pendiente',
        IN_PROGRESS: 'en curso',
        RESOLVED: 'resuelta'
      }
      toast.push({
        tone: 'success',
        title: 'Seguimiento actualizado',
        message: `La acción consultiva ya queda marcada como ${labels[variables.status]}.`
      })
      await refreshAll()
    },
    onError: (error: any) => {
      toast.push({ tone: 'danger', title: 'No se pudo actualizar la acción', message: error?.message || 'Error guardando el seguimiento.' })
    }
  })

  const canReview = workflow?.status === 'READY_FOR_REVIEW' || workflow?.status === 'REPORT_GENERATING' || workflow?.status === 'REPORT_READY'
  const canClose = workflow?.status === 'REPORT_READY' || workflow?.status === 'CLOSED'
  const canRunOrchestration = !!companyId && viewerRole !== 'CLIENTE' && !!workflow?.orchestrationRunnable
  const canRegenerateReport = !!companyId && !!workflow && !['PENDING_DATA', 'INGESTING', 'EXCEPTIONS'].includes(String(workflow.status || ''))
  const reportActionLabel = reportForPeriod ? 'Regenerar informe' : 'Generar informe'
  const priorityStep = useMemo<PriorityStep>(() => {
    if (workflow?.status === 'CLOSED') return 'closed'
    if (!importForPeriod || exceptionEntries.length || workflow?.status === 'EXCEPTIONS') return 'resolve_exceptions'
    if (!workflow?.reviewedAt) return 'review'
    if (!reportForPeriod || !workflow?.recommendationSnapshotId) return 'report'
    if (openFollowUps.length) return 'followups'
    if (portfolioStep?.applicable && portfolioStep.status !== 'LOADED') return 'portfolio'
    if (canClose && workflow?.status !== 'CLOSED') return 'close'
    return 'closed'
  }, [
    canClose,
    exceptionEntries.length,
    importForPeriod,
    openFollowUps.length,
    portfolioStep,
    reportForPeriod,
    workflow?.recommendationSnapshotId,
    workflow?.reviewedAt,
    workflow?.status
  ])
  const nextStepGuide = useMemo(() => {
    if (priorityStep === 'resolve_exceptions') {
      return {
        title: 'Resolver bloqueos',
        detail: exceptionEntries.length
          ? `Hay ${exceptionEntries.length} incidencia${exceptionEntries.length === 1 ? '' : 's'} abierta${exceptionEntries.length === 1 ? '' : 's'} que impiden un cierre fiable.`
          : 'Todavía no existe una base lo bastante fiable para seguir con el cierre.',
        cta: copy.openImports,
        href: '/imports'
      }
    }
    if (priorityStep === 'review') {
      return {
        title: 'Validar la lectura',
        detail: checklistTotal
          ? `Checklist ${checklistDone}/${checklistTotal}. Cuando la lectura sea defendible, marca el periodo como revisado.`
          : 'La carga ya parece operativa; toca confirmar que la lectura del periodo es correcta.',
        cta: copy.openOverview,
        href: '/overview'
      }
    }
    if (priorityStep === 'report') {
      return {
        title: 'Materializar el entregable',
        detail: reportForPeriod
          ? 'El entregable ya existe, pero puedes regenerarlo si necesitas rehacer el cierre con la última lectura disponible.'
          : 'El siguiente avance natural es generar el informe para dejar el periodo materializado y trazable.',
        cta: copy.openReports,
        href: '/reports'
      }
    }
    if (priorityStep === 'followups') {
      return {
        title: 'Revisar el seguimiento consultivo',
        detail: openFollowUps.length === 1
          ? 'Queda 1 acción consultiva abierta antes de dar por aprovechado el cierre.'
          : `Quedan ${openFollowUps.length} acciones consultivas abiertas antes de cerrar el ciclo de valor.`,
        cta: copy.openSnapshot,
        href: '#snapshot-consultivo'
      }
    }
    if (priorityStep === 'portfolio') {
      return {
        title: 'Alinear cartera',
        detail: tribunalStepState.detail,
        cta: viewerRole === 'CLIENTE' ? copy.openPortfolio : tribunalStepState.actionLabel,
        href: tribunalStepState.applicable ? '/tribunal' : '/imports?mode=auto'
      }
    }
    if (priorityStep === 'close') {
      return {
        title: 'Materializar el cierre',
        detail: 'El periodo ya tiene base, lectura y entregable suficientes para cerrarse oficialmente.',
        cta: copy.closeButton,
        href: '/reports'
      }
    }
    return {
      title: 'Periodo encarrilado',
      detail: 'El cierre ya está materializado. Desde aquí toca revisar entregable, cartera y seguimiento con perspectiva.',
      cta: copy.openReports,
      href: '/reports'
    }
  }, [
    checklistDone,
    checklistTotal,
    copy.closeButton,
    copy.openImports,
    copy.openOverview,
    copy.openPortfolio,
    copy.openReports,
    copy.openSnapshot,
    exceptionEntries.length,
    openFollowUps.length,
    priorityStep,
    reportForPeriod,
    tribunalStepState.actionLabel,
    tribunalStepState.applicable,
    tribunalStepState.detail,
    viewerRole
  ])

  return (
    <div className="monthly-close-page">
      <PageHeader
        title="Cierre mensual"
        subtitle="Estado oficial del periodo y siguiente paso."
        actions={<span className="badge">{period}</span>}
      />

      {!companyId ? <Alert tone="warning">{copy.noCompanyAlert}</Alert> : null}
      {workflowQuery.error ? (
        <Alert tone="warning" title={copy.workflowMissingTitle}>
          {String((workflowQuery.error as any)?.message || copy.workflowMissingDetail)}
        </Alert>
      ) : null}

      <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">1. Estado oficial</h3>
          <span className="upload-hint">Solo lo importante.</span>
        </div>
        <div className="grid grid-autofit-220 mt-12">
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Workflow</div>
            <div className="fw-800 mt-1">{workflow?.statusTitle || 'Pendiente'}</div>
            <div className="upload-hint mt-1">{summary.detail}</div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Carga</div>
            <div className="fw-800 mt-1">{importForPeriod ? `Import ${importForPeriod.status}` : 'Sin carga'}</div>
            <div className="upload-hint mt-1">{importForPeriod?.filename || 'Sin base para este periodo.'}</div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Entregable</div>
            <div className="fw-800 mt-1">{reportForPeriod ? 'Generado' : 'Pendiente'}</div>
            <div className="upload-hint mt-1">{reportForPeriod ? `Creado el ${formatDateTime(reportForPeriod.createdAt)}` : 'Se generará al validar la lectura.'}</div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Cartera</div>
            <div className="fw-800 mt-1">{tribunalStepState.title}</div>
            <div className="upload-hint mt-1">{tribunalStepState.detail}</div>
          </div>
        </div>
        <div className="row row-wrap gap-8 mt-12">
          <Link className="badge" to={nextStepGuide.href}>
            {nextStepGuide.cta}
          </Link>
          {canRunOrchestration ? (
            <Button size="sm" variant="secondary" onClick={() => orchestrateMutation.mutate()} loading={orchestrateMutation.isPending}>
              {workflow?.orchestrationActionLabel || 'Automatizar cierre'}
            </Button>
          ) : null}
        </div>
      </div>

      {exceptionEntries.length ? (
        <div className="card section soft">
          <div className="mini-row row-baseline">
            <h3 className="m-0">2. Excepción principal</h3>
            <span className="upload-hint">Una sola incidencia visible por defecto.</span>
          </div>
          <div className="card soft card-pad-sm mt-12">
            <div className="row row-wrap row-center gap-8">
              <span className={`badge ${severityBadgeClass(exceptionEntries[0].severity)}`}>{exceptionEntries[0].severity}</span>
              <span className="fw-700">{exceptionEntries[0].title}</span>
            </div>
            <div className="upload-hint mt-8">{exceptionEntries[0].detail}</div>
            <div className="upload-hint mt-8">
              <strong>Siguiente paso:</strong> {exceptionEntries[0].action}
            </div>
            <div className="row row-wrap gap-8 mt-12">
              <Link className="badge" to="/imports">
                {copy.openImports}
              </Link>
            </div>
          </div>
        </div>
      ) : null}

      {openFollowUps.length ? (
        <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">3. Seguimiento</h3>
          <span className="upload-hint">Solo lo que sigue abierto.</span>
        </div>
          <div className="stack gap-10 mt-12">
            {openFollowUps.slice(0, 3).map((item) => (
              <div key={item.id} className="card soft card-pad-sm">
                <div className="row row-wrap row-center gap-8">
                  <span className={`badge ${followUpBadgeTone(item.status)}`}>{followUpStatusLabel(item.status)}</span>
                  <span className="fw-700">{item.title}</span>
                </div>
                <div className="upload-hint mt-8">{item.detail || EMPTY_VALUE}</div>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  )

  return (
    <div className="monthly-close-page">
      <PageHeader
        title="Cierre mensual"
        subtitle={copy.pageSubtitle}
        actions={<span className="badge">{String(plan || 'BRONZE').toUpperCase()}</span>}
      />

      {!companyId ? <Alert tone="warning">{copy.noCompanyAlert}</Alert> : null}

      <div className="card section soft monthly-close-toolbar">
        <div className="mini-row row-between row-center row-wrap gap-8">
          <div>
            <div className="fw-700">Periodo en trabajo</div>
            <div className="upload-hint">{copy.periodHint}</div>
          </div>
          <div className="row row-center row-wrap gap-8">
            <input
              value={period}
              onChange={(e) => setPeriod(e.target.value)}
              onBlur={() => setSearchParams((prev) => {
                const next = new URLSearchParams(prev)
                next.set('period', period)
                return next
              })}
              placeholder="YYYY-MM"
              className="pipeline-period-input"
            />
            <Button variant="ghost" size="sm" onClick={() => refreshAll()} disabled={!companyId}>
              Refrescar
            </Button>
          </div>
        </div>
      </div>

      <div className="card section soft monthly-close-hero">
        <div className="mini-row row-between row-center row-wrap gap-8">
          <div>
            <h3 className="m-0">Siguiente paso oficial</h3>
            <div className="upload-hint">{nextStepGuide.detail}</div>
          </div>
          <span className={`badge ${priorityStep === 'closed' ? 'ok' : priorityStep === 'resolve_exceptions' ? 'err' : 'warn'}`}>
            {nextStepGuide.title}
          </span>
        </div>
        <div className="row row-wrap gap-8 mt-12">
          {nextStepGuide.href.startsWith('#') ? (
            <a className="badge" href={nextStepGuide.href}>
              {nextStepGuide.cta}
            </a>
          ) : (
            <Link className="badge" to={nextStepGuide.href}>
              {nextStepGuide.cta}
            </Link>
          )}
          {canRunOrchestration ? (
            <Button size="sm" variant="secondary" onClick={() => orchestrateMutation.mutate()} loading={orchestrateMutation.isPending}>
              {workflow?.orchestrationActionLabel || 'Automatizar cierre'}
            </Button>
          ) : null}
        </div>
        {workflow?.orchestrationDetail && canRunOrchestration ? (
          <div className="upload-hint mt-8">{workflow.orchestrationDetail}</div>
        ) : null}
      </div>

      <div id="snapshot-consultivo" className="card section soft monthly-close-workflow">
        <div className="mini-row row-between row-center row-wrap gap-8">
          <div>
            <div className="fw-700">{summary.title}</div>
            <div className="upload-hint">{summary.detail}</div>
          </div>
          <span className={`badge ${workflow?.statusBadgeTone || workflowBadgeTone(workflow?.status)}`}>{workflow?.statusTitle || 'Workflow pendiente'}</span>
        </div>

        {workflowQuery.error ? (
          <Alert tone="warning" title={copy.workflowMissingTitle}>
            {String((workflowQuery.error as any)?.message || copy.workflowMissingDetail)}
          </Alert>
        ) : null}

        <div className="grid grid-autofit-220 mt-12 monthly-close-stage-grid">
          <div className="card soft card-pad-sm monthly-close-stage-card">
            <div className="upload-hint">1. Datos</div>
            <div className="fw-800 mt-1">{stepDone(workflow?.status, 'READY_FOR_REVIEW') ? 'Base lista' : 'Pendiente'}</div>
            <div className="upload-hint mt-1">
              {importForPeriod
                  ? `Import ${importForPeriod.status}${importForPeriod.versionNo ? ` · v${importForPeriod.versionNo}` : ''}`
                  : 'Sin base para este periodo.'}
            </div>
          </div>
          <div className="card soft card-pad-sm monthly-close-stage-card">
              <div className="upload-hint">2. Revisión</div>
              <div className="fw-800 mt-1">{workflow?.reviewedAt ? 'Revisado' : 'Pendiente de revisión'}</div>
            <div className="upload-hint mt-1">
              {checklistTotal ? `${checklistDone}/${checklistTotal} comprobaciones completadas` : 'Contrasta lectura, alertas y calidad antes de dar el mes por bueno.'}
            </div>
          </div>
          <div className="card soft card-pad-sm monthly-close-stage-card">
            <div className="upload-hint">3. Entregable</div>
              <div className="fw-800 mt-1">{reportForPeriod ? 'Informe disponible' : 'Todavía sin informe'}</div>
            <div className="upload-hint mt-1">
                {reportForPeriod ? `Reporte ${reportForPeriod.status} generado el ${formatDateTime(reportForPeriod.createdAt)}` : 'Genera el entregable cuando la lectura ya soporte una conversación con cliente.'}
            </div>
          </div>
          <div className={`card soft card-pad-sm monthly-close-stage-card ${tribunalStepState.badge}`}>
            <div className="upload-hint">4. Cartera</div>
            <div className="fw-800 mt-1">{tribunalStepState.title}</div>
            <div className="upload-hint mt-1">{tribunalStepState.detail}</div>
          </div>
        </div>
      </div>

      <div id="bandeja-excepciones" className="card section soft monthly-close-priority">
        <div className="mini-row row-between row-center row-wrap gap-8">
          <div>
            <div className="fw-700">Prioridad del cierre</div>
            <div className="upload-hint">{copy.priorityHint}</div>
          </div>
          <span className={`badge ${priorityStep === 'closed' ? 'ok' : priorityStep === 'resolve_exceptions' ? 'err' : 'warn'}`}>
            {priorityStep === 'closed' ? 'Cierre resuelto' : 'Acción prioritaria'}
          </span>
        </div>

        {priorityStep === 'resolve_exceptions' ? (
          <div className="card soft card-pad-sm mt-12 monthly-close-action-card monthly-close-action-danger">
            <div className="fw-800">Resolver bloqueo</div>
            <div className="upload-hint mt-8">
              {exceptionEntries.length
                ? `Hay ${exceptionEntries.length} incidencia${exceptionEntries.length === 1 ? '' : 's'} abierta${exceptionEntries.length === 1 ? '' : 's'} entre import, calidad y workflow. Resolverlas ahora evita un cierre engañoso.`
                : 'Todavía no hay una base fiable para el periodo y conviene corregir la carga antes de seguir.'}
            </div>
            <div className="row row-wrap gap-8 mt-12">
              <Link className="badge" to="/imports">{copy.openImports}</Link>
            </div>
          </div>
        ) : null}

        {priorityStep === 'review' ? (
          <div className="card soft card-pad-sm mt-12 monthly-close-action-card monthly-close-action-primary">
            <div className="fw-800">Marcar revisado</div>
            <div className="upload-hint mt-8">
              {checklistTotal
                ? `Checklist ${checklistDone}/${checklistTotal}. Si la lectura funcional ya está validada, deja el periodo revisado y fija criterio oficial.`
                : 'La carga ya parece limpia. Marca revisión cuando tengas claro que la lectura del periodo es sólida.'}
            </div>
            <div className="mt-12">
              <Button size="sm" onClick={() => reviewMutation.mutate()} disabled={!canReview} loading={reviewMutation.isPending}>
                {copy.reviewButton}
              </Button>
            </div>
          </div>
        ) : null}

        {priorityStep === 'report' ? (
          <div className="card soft card-pad-sm mt-12 monthly-close-action-card monthly-close-action-primary">
            <div className="fw-800">{reportForPeriod ? 'Regenerar informe' : 'Generar informe'}</div>
            <div className="upload-hint mt-8">
              {workflow?.recommendationSnapshotId
                ? 'El entregable ya existe, pero puedes regenerarlo si necesitas rehacer el cierre con la última lectura consolidada.'
                : 'Al generar el informe dejas materializado el entregable y el primer activo consultivo del periodo.'}
            </div>
            {viewerRole !== 'CLIENTE' ? (
              availableUniversalViews.length ? (
                <div className="report-view-picker mt-12">
                  <div className="upload-hint">Vista Universal del entregable</div>
                  <select value={selectedReportUniversalViewId} onChange={(e) => setSelectedReportUniversalViewId(e.target.value)}>
                    <option value="">Automática: última guardada</option>
                    {availableUniversalViews.map((view) => (
                      <option key={view.id} value={view.id}>
                        {`${view.name} · ${universalAggregationModeLabel(view.aggregationMode)}`}
                      </option>
                    ))}
                  </select>
                  <div className="upload-hint">
                    {selectedReportUniversalView
                      ? `${universalAggregationExecutiveLine(selectedReportUniversalView.aggregationMode)} Entrará "${selectedReportUniversalView.name}".`
                      : 'Si no eliges una concreta, el cierre usará la última vista Universal guardada.'}
                  </div>
                </div>
              ) : (
                <div className="upload-hint mt-12">Sin vistas Universal guardadas.</div>
              )
            ) : null}
            <div className="mt-12">
              <Button size="sm" variant="secondary" onClick={() => reportMutation.mutate()} disabled={!canRegenerateReport} loading={reportMutation.isPending}>
                {viewerRole === 'CLIENTE' ? 'Ver entregable' : reportActionLabel === 'Generar informe' ? copy.reportButton : 'Regenerar informe'}
              </Button>
            </div>
          </div>
        ) : null}

        {priorityStep === 'followups' ? (
          <div className="card soft card-pad-sm mt-12 monthly-close-action-card monthly-close-action-primary">
            <div className="fw-800">Ver acción consultiva</div>
            <div className="upload-hint mt-8">
              {openFollowUps.length === 1
                ? 'Queda 1 acción consultiva abierta antes de dar el ciclo por realmente aprovechado.'
                : `Quedan ${openFollowUps.length} acciones consultivas abiertas antes de convertir el cierre en seguimiento real.`}
            </div>
            <div className="mt-12">
              <a className="badge" href="#snapshot-consultivo">{copy.openSnapshot}</a>
            </div>
          </div>
        ) : null}

        {priorityStep === 'portfolio' ? (
          <div className="card soft card-pad-sm mt-12 monthly-close-action-card monthly-close-action-warning">
            <div className="fw-800">{portfolioStep?.status === 'PENDING' ? 'Cargar cartera' : 'Actualizar cartera'}</div>
            <div className="upload-hint mt-8">
              {tribunalStepState.detail}
            </div>
            <div className="mt-12">
              <Link className="badge" to={tribunalStepState.applicable ? '/tribunal' : '/imports?mode=auto'}>
                {viewerRole === 'CLIENTE' ? copy.openPortfolio : tribunalStepState.actionLabel}
              </Link>
            </div>
          </div>
        ) : null}

        {priorityStep === 'close' ? (
          <div className="card soft card-pad-sm mt-12 monthly-close-action-card monthly-close-action-success">
            <div className="fw-800">Cerrar periodo</div>
            <div className="upload-hint mt-8">
              El informe ya está listo y el cierre puede quedar materializado desde aquí.
            </div>
            <div className="mt-12">
              <Button size="sm" onClick={() => closeMutation.mutate()} disabled={!canClose || workflow?.status === 'CLOSED'} loading={closeMutation.isPending}>
                {workflow?.primaryActionLabel || copy.closeButton}
              </Button>
            </div>
          </div>
        ) : null}

        {priorityStep === 'closed' ? (
          <div className="card soft card-pad-sm mt-12 ok monthly-close-action-card monthly-close-action-success">
            <div className="fw-800">Cierre ya materializado</div>
            <div className="upload-hint mt-8">
              El periodo ya quedó cerrado. Desde aquí el valor está en revisar entregable, cartera y seguimiento consultivo con perspectiva.
            </div>
            <div className="mt-12">
              <a className="badge" href="#snapshot-consultivo">{copy.openSnapshot}</a>
            </div>
          </div>
        ) : null}
      </div>

      <div className="card section soft monthly-close-snapshot">
        <div className="mini-row mt-0 row-between row-center row-wrap gap-8">
          <div>
            <h3 className="m-0">Snapshot consultivo</h3>
            <div className="upload-hint">{copy.snapshotHint}</div>
          </div>
          <span className={`badge ${workflow?.recommendationSnapshotId ? 'ok' : 'warn'}`}>
            {workflow?.recommendationSnapshotId ? 'Snapshot disponible' : 'Pendiente'}
          </span>
        </div>

        {recommendationQuery.isFetching || followUpsQuery.isFetching ? <div className="upload-hint mt-12">Cargando recomendación del periodo...</div> : null}

        {!workflow?.recommendationSnapshotId && !workflow?.recommendationSummary ? (
          <Alert tone="info" title={copy.snapshotMissingTitle}>
            Aparecerá aquí cuando el workflow deje materializada la recomendación del periodo como consecuencia natural del cierre.
          </Alert>
        ) : (
          <>
            <div className="card soft card-pad-sm mt-12 monthly-close-summary-card">
              <div className="upload-hint">Resumen ejecutivo</div>
              <div className="mt-8">{recommendationQuery.data?.summary || workflow?.recommendationSummary || 'Sin resumen consultivo disponible todavía.'}</div>
              {recommendationQuery.data?.createdAt ? (
                <div className="upload-hint mt-8">Generado el {formatDateTime(recommendationQuery.data.createdAt)}</div>
              ) : workflow?.recommendationCreatedAt ? (
                <div className="upload-hint mt-8">Generado el {formatDateTime(workflow.recommendationCreatedAt)}</div>
              ) : null}
            </div>

            {recommendationFollowUps.length ? (
              <div className="grid grid-autofit-220 mt-12 monthly-close-followup-grid">
                {recommendationFollowUps.map((action) => (
                  <div key={action.id} className="card soft card-pad-sm monthly-close-followup-card">
                    <div className="row row-center row-wrap gap-8">
                      <span className={`badge ${String(action.priority || '').toUpperCase() === 'HIGH' ? 'err' : 'warn'}`}>
                        {action.priority || 'Prioridad'}
                      </span>
                      <span className={`badge ${followUpBadgeTone(action.status)}`}>{followUpStatusLabel(action.status)}</span>
                      <span className="upload-hint">{action.horizon || 'Próximo paso'}</span>
                    </div>
                    <div className="fw-800 mt-8">{action.title}</div>
                    <div className="upload-hint mt-8">{action.detail || 'Sin detalle adicional para esta acción.'}</div>
                    <div className="upload-hint mt-8">
                      <strong>KPI:</strong> {action.kpi || 'Sin KPI asociado'}
                    </div>
                    {action.carriedOver && action.originPeriod ? (
                      <div className="upload-hint mt-8">Arrastrada desde {action.originPeriod}</div>
                    ) : null}
                {canManageFollowUps ? (
                      <div className="row row-wrap gap-8 mt-12">
                        <Button
                          size="sm"
                          variant={String(action.status || '').toUpperCase() === 'PENDING' ? 'secondary' : 'ghost'}
                          disabled={followUpStatusMutation.isPending}
                          onClick={() => followUpStatusMutation.mutate({ followUpId: action.id, status: 'PENDING' })}
                        >
                          Pendiente
                        </Button>
                        <Button
                          size="sm"
                          variant={String(action.status || '').toUpperCase() === 'IN_PROGRESS' ? 'secondary' : 'ghost'}
                          disabled={followUpStatusMutation.isPending}
                          onClick={() => followUpStatusMutation.mutate({ followUpId: action.id, status: 'IN_PROGRESS' })}
                        >
                          En curso
                        </Button>
                        <Button
                          size="sm"
                          variant={String(action.status || '').toUpperCase() === 'RESOLVED' ? 'secondary' : 'ghost'}
                          disabled={followUpStatusMutation.isPending}
                          onClick={() => followUpStatusMutation.mutate({ followUpId: action.id, status: 'RESOLVED' })}
                        >
                          Resuelta
                        </Button>
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
            ) : recommendationActions.length ? (
              <div className="grid grid-autofit-220 mt-12 monthly-close-followup-grid">
                {recommendationActions.map((action, idx) => (
                  <div key={`${action.title}-${idx}`} className="card soft card-pad-sm monthly-close-followup-card">
                    <div className="row row-center row-wrap gap-8">
                      <span className={`badge ${String(action.priority || '').toUpperCase() === 'HIGH' ? 'err' : 'warn'}`}>
                        {action.priority || 'Prioridad'}
                      </span>
                      <span className="badge warn">Pendiente de seguimiento</span>
                      <span className="upload-hint">{action.horizon || 'Próximo paso'}</span>
                    </div>
                    <div className="fw-800 mt-8">{action.title}</div>
                    <div className="upload-hint mt-8">{action.detail}</div>
                    <div className="upload-hint mt-8">
                      <strong>KPI:</strong> {action.kpi}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="upload-hint mt-12">Sin acciones abiertas.</div>
            )}
          </>
        )}
      </div>

      <details id="bandeja-excepciones" className="monthly-close-details" open={supportPanelOpen}>
        <summary>
          <div>
            <div className="fw-700">Diagnóstico y trazabilidad</div>
            <div className="upload-hint">Bloqueos, calidad, señales y evidencia técnica solo cuando hacen falta.</div>
          </div>
          <div className="row row-center row-wrap gap-8">
            <span className={`badge ${workflowGaps.length ? 'warn' : 'ok'}`}>{workflowGaps.length ? `${workflowGaps.length} pendientes` : 'Sin pendientes'}</span>
            <span className={`badge ${exceptionSummary.high ? 'err' : exceptionEntries.length ? 'warn' : 'ok'}`}>{exceptionEntries.length} excepciones</span>
          </div>
        </summary>
        <div className="monthly-close-details-body">
          <div className="card section soft monthly-close-gaps">
            <div className="mini-row row-between row-center row-wrap gap-8">
              <div>
                <h3 className="m-0">Qué falta exactamente</h3>
                <div className="upload-hint">{copy.gapsHint}</div>
              </div>
              <span className={`badge ${workflowGaps.length ? 'warn' : 'ok'}`}>{workflowGaps.length ? `${workflowGaps.length} pendientes` : 'Listo para cierre'}</span>
            </div>

            {!workflowGaps.length ? (
              <Alert tone="success" title="Periodo encarrilado">
                No queda ningún bloqueo operativo visible. Si el informe ya está listo, el periodo está prácticamente listo para convertirse en cierre y servicio.
              </Alert>
            ) : (
              <div className="stack gap-8 mt-12">
                {workflowGaps.map((gap, idx) => (
                  <Alert key={`gap-${idx}`} tone={gap.tone} title={gap.title}>
                    {gap.detail}
                  </Alert>
                ))}
              </div>
            )}

            <div className="grid grid-autofit-220 mt-12 monthly-close-consequence-grid">
              {closureConsequences.map((item) => (
                <div key={item.label} className={`card soft card-pad-sm monthly-close-consequence-card ${item.ready ? 'ok' : ''}`}>
                  <div className="upload-hint">{item.label}</div>
                  <div className="fw-800 mt-1">{item.title}</div>
                  <div className="upload-hint mt-1">{item.detail}</div>
                </div>
              ))}
            </div>
          </div>

          <div className="section monthly-close-ops-grid">
            <div className="card monthly-close-signals">
              <div className="mini-row mt-0 row-between row-center">
                <h3 className="m-0">Señales del periodo</h3>
                <span className={`badge ${alertsCount ? 'warn' : 'ok'}`}>{alertsCount} alertas</span>
              </div>
              <div className="grid grid-autofit-220 mt-12">
                <div className="metric-card">
                  <div className="metric-label">Workflow</div>
                  <div className="metric-value">{workflow?.status || '—'}</div>
                  <div className="upload-hint">Estado actual del cierre</div>
                </div>
                <div className={`metric-card ${workflow?.exceptionCount ? 'err' : 'ok'}`}>
                  <div className="metric-label">Excepciones</div>
                  <div className="metric-value">{workflow?.exceptionCount ?? 0}</div>
                  <div className="upload-hint">Warnings y bloqueos agregados</div>
                </div>
                <div className={`metric-card ${checklistTotal && checklistDone < checklistTotal ? 'warn' : 'ok'}`}>
                  <div className="metric-label">Checklist</div>
                  <div className="metric-value">{checklistDone}/{checklistTotal || 0}</div>
                  <div className="upload-hint">Validación funcional del periodo</div>
                </div>
                <div className={`metric-card ${reportForPeriod ? 'ok' : 'warn'}`}>
                  <div className="metric-label">Entregable</div>
                  <div className="metric-value">{reportForPeriod ? 'READY' : 'PEND'}</div>
                  <div className="upload-hint">Disponibilidad del informe mensual</div>
                </div>
              </div>

              {workflow?.blockingReason ? (
                <div className="card soft card-pad-sm mt-12">
                  <div className="fw-700">Bloqueo actual</div>
                  <div className="upload-hint mt-8">{workflow.blockingReason}</div>
                </div>
              ) : null}
            </div>
          </div>

          <div className="card section soft monthly-close-exceptions">
            <div className="mini-row mt-0 row-between row-center row-wrap gap-8">
              <div>
                <h3 className="m-0">Bandeja de excepciones</h3>
                <div className="upload-hint">{copy.exceptionsHint}</div>
              </div>
              <div className="row row-center row-wrap gap-8">
                <span className={`badge ${exceptionSummary.high ? 'err' : 'ok'}`}>{exceptionSummary.high} críticas</span>
                <span className={`badge ${exceptionSummary.medium ? 'warn' : 'ok'}`}>{exceptionSummary.medium} medias</span>
                <span className={`badge ${exceptionSummary.low ? 'warn' : 'ok'}`}>{exceptionSummary.low} leves</span>
              </div>
            </div>

            {qualityQuery.error ? (
              <Alert tone="danger" title={copy.qualityErrorTitle}>
                {String((qualityQuery.error as any)?.message || 'Error recuperando la calidad del import activo.')}
              </Alert>
            ) : null}

            {!importForPeriod && !workflow?.blockingReason ? (
              <div className="empty mt-12">{copy.emptyExceptions}</div>
            ) : !exceptionEntries.length ? (
              <div className="empty mt-12">{copy.cleanExceptions}</div>
            ) : (
              <div className="stack gap-8 mt-12">
                {exceptionEntries.map((entry, idx) => (
                  <div key={`${entry.code}-${idx}`} className="card soft card-pad-sm monthly-close-exception-card">
                    <div className="row row-center row-wrap gap-8">
                      <span className={`badge ${severityBadgeClass(entry.severity)}`}>{entry.severity}</span>
                      {importForPeriod ? <span className={`badge ${importStatusBadgeTone(importForPeriod.status)}`}>{importForPeriod.status}</span> : null}
                      <span className="fw-700">{entry.title}</span>
                    </div>
                    <div className="upload-hint mt-8">{entry.detail}</div>
                    <div className="upload-hint mt-8">
                      <strong>Siguiente paso:</strong> {entry.action}
                    </div>
                    {entry.evidence ? <div className="upload-hint mt-8">{entry.evidence}</div> : null}
                  </div>
                ))}
              </div>
            )}

            {qualityQuery.isFetching ? <div className="upload-hint mt-12">Analizando calidad del import activo...</div> : null}

            {qualityQuery.data ? (
              <div className="mt-12">
                <div className="mini-row mt-0 row-between row-center row-wrap gap-8">
                  <div className="row row-center gap-8">
                    <span className="fw-700">Calidad del import activo</span>
                    <span className={`badge ${qualitySummary(qualityQuery.data)?.badge || ''}`}>{qualitySummary(qualityQuery.data)?.label || 'OK'}</span>
                  </div>
                  <div className="upload-hint">
                    {qualityQuery.data.minDate && qualityQuery.data.maxDate ? `Rango detectado: ${qualityQuery.data.minDate} → ${qualityQuery.data.maxDate}` : ''}
                  </div>
                </div>

                <div className="grid grid-autofit-180 mt-12 monthly-close-quality-grid">
                  <div className="card soft card-pad-sm monthly-close-quality-card">
                    <div className="upload-hint">Filas válidas</div>
                    <div className="fw-700">{qualityQuery.data.rowsParsed}</div>
                  </div>
                  <div className="card soft card-pad-sm monthly-close-quality-card">
                    <div className="upload-hint">Errores fecha/importe</div>
                    <div className="fw-700">
                      {qualityQuery.data.dateParseErrors}/{qualityQuery.data.amountParseErrors}
                    </div>
                  </div>
                  <div className="card soft card-pad-sm monthly-close-quality-card">
                    <div className="upload-hint">Fuera de periodo</div>
                    <div className="fw-700">{qualityQuery.data.outsidePeriodRows}</div>
                  </div>
                  <div className="card soft card-pad-sm monthly-close-quality-card">
                    <div className="upload-hint">Duplicados</div>
                    <div className="fw-700">{qualityQuery.data.duplicateRows}</div>
                  </div>
                  <div className="card soft card-pad-sm monthly-close-quality-card">
                    <div className="upload-hint">Sin contraparte</div>
                    <div className="fw-700">{qualityQuery.data.missingCounterpartyRows}</div>
                  </div>
                  <div className="card soft card-pad-sm monthly-close-quality-card">
                    <div className="upload-hint">Saldo no cuadra</div>
                    <div className="fw-700">{qualityQuery.data.balanceEndMismatchRows}</div>
                  </div>
                </div>

                {qualityQuery.data.examples?.length ? (
                  <details className="mt-12">
                    <summary className="upload-hint cursor-pointer">Ver evidencia y ejemplos</summary>
                    <div className="upload-hint mono pre-wrap mt-8">
                      {qualityQuery.data.examples.join('\n')}
                    </div>
                  </details>
                ) : null}
              </div>
            ) : null}
          </div>

          <div className="card section monthly-close-trace">
            <div className="mini-row mt-0 row-between row-center">
              <h3 className="m-0">Trazabilidad rápida</h3>
              <span className="upload-hint">{copy.traceHint}</span>
            </div>
            <table className="table mt-12">
              <thead>
                <tr>
                  <th>Bloque</th>
                  <th>Estado</th>
                  <th>Detalle</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td className="fw-700">Import asociado</td>
                  <td>{importForPeriod?.status || '—'}</td>
                  <td className="upload-hint">
                    {importForPeriod
                      ? `${importForPeriod.originalFilename || importForPeriod.storageRef || EMPTY_VALUE} · ${formatDateTime(importForPeriod.createdAt)}`
                      : 'Todavía no hay import para este periodo'}
                  </td>
                </tr>
                <tr>
                  <td className="fw-700">Checklist</td>
                  <td>{checklistDone}/{checklistTotal || 0}</td>
                  <td className="upload-hint">{checklistTotal ? 'Comprobaciones del periodo.' : 'Sin checklist.'}</td>
                </tr>
                <tr>
                  <td className="fw-700">Alertas</td>
                  <td>{alertsCount}</td>
                  <td className="upload-hint">{alertsCount ? 'Revisa se?ales antes de cerrar.' : 'Sin alertas.'}</td>
                </tr>
                <tr>
                  <td className="fw-700">Reporte</td>
                  <td>{reportForPeriod?.status || workflow?.reportStatus || '—'}</td>
                  <td className="upload-hint">
                    {reportForPeriod
                      ? `Informe ${reportForPeriod.id} generado el ${formatDateTime(reportForPeriod.createdAt)}`
                      : 'Todavía no existe entregable para este periodo'}
                  </td>
                </tr>
                <tr>
                  <td className="fw-700">Cartera (Tribunal)</td>
                  <td>{tribunalStepState.status}</td>
                  <td className="upload-hint">{tribunalStepState.detail}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </details>
    </div>
  )
}





