// @ts-nocheck
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useEffect, useMemo, useRef, useState } from 'react'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import Icon from '../components/ui/Icon'
import {
  getCompanyMapping,
  getPipelineFiles,
  getPipelineSummary,
  getUserRole,
  retryPipelineFile,
  saveCompanyMapping,
  scanPipeline,
  updatePipelineFileKind,
  updatePipelineFilePeriod,
  type PipelineFileDto
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import { useToast } from '../components/ui/ToastProvider'
import { EMPTY_ACTIVITY_TEXT, EMPTY_MESSAGE_TEXT, formatDateTime } from '../utils/format'
import { importStatusBadgeTone, pipelineKindLabel, pipelineModuleLabel } from '../utils/presentation'
import { getWorkPeriod } from '../utils/workPeriod'

function canRetryRow(row: PipelineFileDto) {
  return String(row.detectedKind || '').toUpperCase() === 'TRANSACTIONS' && !!row.period
}

function canEditKind(_row: PipelineFileDto) {
  return true
}

function moduleHref(row: PipelineFileDto) {
  const kind = String(row.detectedKind || '').toUpperCase()
  if (kind === 'TRIBUNAL') return '/tribunal'
  if (kind === 'UNIVERSAL') return '/universal'
  return '/dashboard'
}

function pipelineRowNextStep(row: PipelineFileDto, isAdmin: boolean, copy: ReturnType<typeof pipelineRoleCopy>) {
  const status = String(row.status || '').toUpperCase()
  const kind = normalizeKind(row.detectedKind)

  if (kind === 'UNKNOWN') {
    return {
      title: isAdmin ? copy.classifyButton : 'Esperar clasificación',
      detail: isAdmin
        ? 'El sistema no sabe aún a qué módulo pertenece este fichero.'
        : 'Este fichero necesita que la consultora lo clasifique antes de procesarlo.'
    }
  }

  if (!row.period) {
    return {
      title: isAdmin ? 'Completar periodo' : 'Pendiente de periodo',
      detail: isAdmin
        ? 'Sin periodo no queda bien enlazado al workflow mensual.'
        : 'Falta asociarlo a un periodo antes de que entre bien en el circuito.'
    }
  }

  if (status === 'ERROR') {
    return {
      title: row.actionLabel || copy.retryButton,
      detail: 'El envío a cola falló o quedó interrumpido. Revisa y relanza desde aquí.'
    }
  }

  if (status === 'SKIPPED') {
    return {
      title: isAdmin ? 'Revisar naming o reglas' : 'Pendiente de revisión',
      detail: 'La bandeja lo detectó, pero todavía no ha podido convertirlo en trabajo procesable.'
    }
  }

  if (status === 'PENDING' || status === 'PROCESSING') {
    return {
      title: 'Vigilar procesamiento',
      detail: 'Ya hay movimiento en curso. Solo hace falta confirmar que termine bien o refrescar estado.'
    }
  }

  return {
    title: pipelineModuleLabel(row),
    detail: 'El fichero ya es interpretable. Desde aquí puedes abrir el módulo relacionado si necesitas contexto.'
  }
}

function renderState(title: string, detail: string, locked = false) {
  return (
    <div className={`panel-state ${locked ? 'panel-state-locked' : ''}`}>
      <div className="panel-state-title">{title}</div>
      <div className="panel-state-detail">{detail}</div>
    </div>
  )
}

type PipelineKind = 'TRANSACTIONS' | 'TRIBUNAL' | 'UNIVERSAL' | 'UNKNOWN'

type PipelineRule = {
  matchText: string
  pathContains: string
  kind: PipelineKind
}

type ViewerRole = 'ADMIN' | 'CONSULTOR' | 'CLIENTE'

function normalizeViewerRole(role?: string | null): ViewerRole {
  const normalized = String(role || '').toUpperCase()
  if (normalized === 'ADMIN' || normalized === 'CLIENTE') return normalized
  return 'CONSULTOR'
}

function pipelineRoleCopy(role: ViewerRole) {
  if (role === 'ADMIN') {
    return {
      subtitle: 'La bandeja operativa donde la consultora gobierna clasificación, reproceso y trazabilidad de entrada por cliente.',
      howItWorks: 'Deja ficheros en `storage/inbox/{companyId}`. El sistema ya puede absorber subcarpetas, reglas por nombre y ruta, y un tipo base por empresa para mantener control operativo.',
      rulesHint: 'Configuran cómo se interpreta la casuística real de cada cliente y reducen intervención manual futura.',
      summaryHint: 'Cuando empiecen a entrar ficheros, aquí verás si la operación está fluyendo o si hace falta intervenir.',
      footerHint: 'Ya puedes operar con subcarpetas reales por cliente, reglas por nombre y ruta, tipo por defecto, corrección manual y reproceso desde un único punto de control.',
      refreshButton: 'Actualizar estado',
      scanButton: 'Lanzar escaneo',
      classifyButton: 'Corregir clasificación',
      retryButton: 'Relanzar a cola',
      viewModuleButton: 'Abrir módulo',
      noCompanyAlert: 'Selecciona una empresa para gobernar el pipeline operativo.'
    }
  }
  if (role === 'CLIENTE') {
    return {
      subtitle: 'Visión ejecutiva de cómo entran, se clasifican y avanzan los ficheros del cliente dentro del circuito operativo.',
      howItWorks: 'Deja ficheros en `storage/inbox/{companyId}`. El sistema puede interpretarlos por carpetas, nombre y reglas de empresa para acelerar el paso a operación.',
      rulesHint: 'Estas reglas explican cómo se traduce el naming real del cliente a un flujo procesable y trazable.',
      summaryHint: 'Cuando empiecen a entrar ficheros, aquí verás si la operación está fluyendo o si hay puntos que revisar.',
      footerHint: 'Ya existe un circuito único para entrada, clasificación y reproceso, con menos trabajo manual y más trazabilidad.',
      refreshButton: 'Actualizar lectura',
      scanButton: 'Escanear bandeja',
      classifyButton: 'Revisar clasificación',
      retryButton: 'Ver reproceso',
      viewModuleButton: 'Ver módulo',
      noCompanyAlert: 'Selecciona una empresa para seguir el pipeline de entrada.'
    }
  }
  return {
    subtitle: 'La bandeja operativa donde la consultora convierte ficheros dispersos en trabajo clasificado, trazable y reprocesable.',
    howItWorks: 'Deja ficheros en `storage/inbox/{companyId}`. El sistema ya puede leer subcarpetas, reglas por nombre, reglas por ruta y un tipo por defecto por empresa para absorber la operativa real del cliente.',
    rulesHint: 'Afinan cómo se interpreta el naming y la estructura real de carpetas de cada cliente.',
    summaryHint: 'Cuando empiecen a entrar ficheros, aquí verás si la operativa está fluyendo o si hace falta intervenir.',
    footerHint: 'Ya puedes operar con subcarpetas reales por cliente, reglas por nombre y ruta, tipo por defecto, corrección manual y reproceso desde un único punto de control.',
    refreshButton: 'Refrescar',
    scanButton: 'Escanear bandeja',
    classifyButton: 'Clasificar',
    retryButton: 'Reprocesar',
    viewModuleButton: 'Abrir módulo',
    noCompanyAlert: 'Selecciona una empresa arriba para ver y operar el pipeline.'
  }
}

function normalizeKind(value: any): PipelineKind {
  const normalized = String(value || 'TRANSACTIONS').toUpperCase()
  return ['TRANSACTIONS', 'TRIBUNAL', 'UNIVERSAL', 'UNKNOWN'].includes(normalized)
    ? (normalized as PipelineKind)
    : 'TRANSACTIONS'
}

function sanitizeRules(payload: any): { defaultKind: PipelineKind; rules: PipelineRule[] } {
  const raw = Array.isArray(payload?.rules) ? payload.rules : []
  const rules = raw
    .map((rule: any) => ({
      matchText: String(rule?.matchText || '').trim(),
      pathContains: String(rule?.pathContains || '').trim(),
      kind: normalizeKind(rule?.kind)
    }))
    .filter((rule: PipelineRule) => rule.matchText || rule.pathContains)
  return {
    defaultKind: normalizeKind(payload?.defaultKind),
    rules
  }
}

export default function PipelineCenterPage() {
  const { id: companyId, plan } = useCompanySelection()
  const viewerRole = normalizeViewerRole(getUserRole())
  const isAdmin = viewerRole === 'ADMIN'
  const copy = pipelineRoleCopy(viewerRole)
  const toast = useToast()
  const queryClient = useQueryClient()
  const workPeriod = getWorkPeriod(companyId)
  const workflowHref = workPeriod ? `/monthly-close?period=${encodeURIComponent(workPeriod)}` : '/monthly-close'
  const [editingFileId, setEditingFileId] = useState<number | null>(null)
  const [periodDraft, setPeriodDraft] = useState('')
  const [kindDraft, setKindDraft] = useState<PipelineKind>('TRANSACTIONS')
  const [defaultKindDraft, setDefaultKindDraft] = useState<PipelineKind>('TRANSACTIONS')
  const [ruleDrafts, setRuleDrafts] = useState<PipelineRule[]>([])
  const boardRef = useRef<HTMLDivElement | null>(null)

  const summaryQuery = useQuery({
    queryKey: ['pipeline-summary', companyId],
    queryFn: () => getPipelineSummary(companyId as number),
    enabled: !!companyId,
    retry: 1
  })

  const filesQuery = useQuery({
    queryKey: ['pipeline-files', companyId],
    queryFn: () => getPipelineFiles(companyId as number),
    enabled: !!companyId,
    retry: 1
  })

  const rulesQuery = useQuery({
    queryKey: ['pipeline-rules', companyId],
    queryFn: () => getCompanyMapping(companyId as number, 'pipeline.rules'),
    enabled: !!companyId,
    retry: 1
  })

  useEffect(() => {
    const sanitized = sanitizeRules(rulesQuery.data)
    setRuleDrafts(sanitized.rules)
    setDefaultKindDraft(sanitized.defaultKind)
  }, [rulesQuery.data])

  const scanMutation = useMutation({
    mutationFn: () => scanPipeline(companyId as number),
    onSuccess: async (data) => {
      toast.push({
        tone: 'success',
        title: 'Pipeline actualizado',
        message: `Escaneo completado: ${data.discovered} detectados, ${data.imported} enviados a cola.`
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['pipeline-summary', companyId] }),
        queryClient.invalidateQueries({ queryKey: ['pipeline-files', companyId] }),
        queryClient.invalidateQueries({ queryKey: ['imports', companyId] }),
        queryClient.invalidateQueries({ queryKey: ['ingestion-status', companyId] })
      ])
    },
    onError: (error: any) => {
      toast.push({
        tone: 'danger',
        title: 'Error en el escaneo',
        message: error?.message || 'No se pudo escanear la bandeja de entrada.'
      })
    }
  })

  const retryMutation = useMutation({
    mutationFn: ({ fileId }: { fileId: number }) => retryPipelineFile(companyId as number, fileId),
    onSuccess: async () => {
      toast.push({
        tone: 'success',
        title: 'Fichero relanzado',
        message: 'El fichero se ha reenviado a cola desde Pipeline Center.'
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['pipeline-summary', companyId] }),
        queryClient.invalidateQueries({ queryKey: ['pipeline-files', companyId] }),
        queryClient.invalidateQueries({ queryKey: ['imports', companyId] }),
        queryClient.invalidateQueries({ queryKey: ['overview-ingestion', companyId] })
      ])
    },
    onError: (error: any) => {
      toast.push({
        tone: 'danger',
        title: 'No se pudo reprocesar',
        message: error?.message || 'El fichero no se pudo reenviar a cola.'
      })
    }
  })

  const periodMutation = useMutation({
    mutationFn: ({ fileId, period }: { fileId: number; period: string }) => updatePipelineFilePeriod(companyId as number, fileId, period),
    onSuccess: async () => {
      toast.push({
        tone: 'success',
        title: 'Periodo actualizado',
        message: 'Ya puedes relanzar el fichero desde Pipeline Center.'
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['pipeline-summary', companyId] }),
        queryClient.invalidateQueries({ queryKey: ['pipeline-files', companyId] })
      ])
    },
    onError: (error: any) => {
      toast.push({
        tone: 'danger',
        title: 'No se pudo actualizar el periodo',
        message: error?.message || 'Revisa el formato YYYY-MM.'
      })
    }
  })

  const kindMutation = useMutation({
    mutationFn: ({ fileId, kind }: { fileId: number; kind: string }) => updatePipelineFileKind(companyId as number, fileId, kind),
    onSuccess: async () => {
      toast.push({
        tone: 'success',
        title: 'Tipo actualizado',
        message: 'La clasificación manual ya está guardada en el Pipeline Center.'
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['pipeline-summary', companyId] }),
        queryClient.invalidateQueries({ queryKey: ['pipeline-files', companyId] })
      ])
    },
    onError: (error: any) => {
      toast.push({
        tone: 'danger',
        title: 'No se pudo actualizar el tipo',
        message: error?.message || 'Revisa el tipo seleccionado.'
      })
    }
  })

  const rulesMutation = useMutation({
    mutationFn: () =>
      saveCompanyMapping(companyId as number, 'pipeline.rules', {
        defaultKind: defaultKindDraft,
        rules: ruleDrafts
          .map((rule) => ({
            matchText: rule.matchText.trim(),
            pathContains: rule.pathContains.trim(),
            kind: rule.kind
          }))
          .filter((rule) => rule.matchText || rule.pathContains)
      }),
    onSuccess: async () => {
      toast.push({
        tone: 'success',
        title: 'Reglas guardadas',
        message: 'La clasificación automática de esta empresa ya queda afinada para próximos escaneos.'
      })
      await queryClient.invalidateQueries({ queryKey: ['pipeline-rules', companyId] })
    },
    onError: (error: any) => {
      toast.push({
        tone: 'danger',
        title: 'No se pudieron guardar las reglas',
        message: error?.message || 'Revisa las coincidencias y vuelve a intentarlo.'
      })
    }
  })

  const summary = summaryQuery.data
  const files = filesQuery.data || []
  const activeRow = useMemo(() => files.find((row) => row.id === editingFileId) || null, [editingFileId, files])
  const summaryHeadline = summary?.headline || 'Resumen operativo'
  const summaryDetail = summary?.detail || copy.summaryHint
  const attentionRows = useMemo(() => {
    const scoreRow = (row: PipelineFileDto) => {
      const status = String(row.status || '').toUpperCase()
      if (status === 'ERROR') return 5
      if (status === 'SKIPPED' || normalizeKind(row.detectedKind) === 'UNKNOWN' || !row.period) return 4
      if (status === 'PENDING') return 3
      if (status === 'PROCESSING') return 2
      if (row.badgeTone === 'warn' || row.badgeTone === 'err') return 1
      return 0
    }

    return [...files]
      .filter((row) => scoreRow(row) > 0)
      .sort((a, b) => {
        const diff = scoreRow(b) - scoreRow(a)
        if (diff !== 0) return diff
        return new Date(b.detectedAt).getTime() - new Date(a.detectedAt).getTime()
      })
  }, [files])
  const attentionCounts = useMemo(() => {
    const blocked = attentionRows.filter((row) => String(row.status || '').toUpperCase() === 'ERROR').length
    const review = attentionRows.filter((row) => normalizeKind(row.detectedKind) === 'UNKNOWN' || !row.period || String(row.status || '').toUpperCase() === 'SKIPPED').length
    const processing = attentionRows.filter((row) => {
      const status = String(row.status || '').toUpperCase()
      return status === 'PENDING' || status === 'PROCESSING'
    }).length
    return { blocked, review, processing }
  }, [attentionRows])
  const pipelineDecisionState = useMemo(() => {
    if (!companyId) {
      return {
        title: 'Selecciona una empresa gestionada',
        detail: 'Sin empresa no hay bandeja operativa ni trazabilidad que revisar.'
      }
    }

    if (summaryQuery.isPending || filesQuery.isPending) {
      return {
        title: 'Leyendo la bandeja',
        detail: 'Estamos comprobando si han entrado ficheros nuevos y si alguno necesita intervención.'
      }
    }

    if (attentionCounts.blocked) {
      return {
        title: 'Hay ficheros bloqueados o fallidos',
        detail: 'Conviene corregir clasificación, periodo o reproceso antes de seguir el cierre del mes.'
      }
    }

    if (attentionCounts.review) {
      return {
        title: 'Hay ficheros por clasificar o completar',
        detail: 'La entrada existe, pero todavía falta dejarla interpretable para que el sistema la procese bien.'
      }
    }

    if (attentionCounts.processing) {
      return {
        title: 'La bandeja está procesando',
        detail: 'Ya hay movimiento en cola. Solo hace falta vigilar que los ficheros terminen bien.'
      }
    }

    if (summary?.totalFiles) {
      return {
        title: 'Bandeja estable',
        detail: 'No se ve intervención manual pendiente. Desde aquí ya toca volver al workflow del periodo.'
      }
    }

    return {
      title: 'Bandeja sin actividad',
      detail: 'Cuando entren ficheros, aquí verás solo los que realmente requieran intervención.'
    }
  }, [attentionCounts.blocked, attentionCounts.processing, attentionCounts.review, companyId, filesQuery.isPending, summary?.totalFiles, summaryQuery.isPending])
  const pipelineNextAction = useMemo(() => {
    if (!companyId) {
      return {
        label: 'Selecciona empresa',
        detail: 'Primero activa la empresa que quieres operar.',
        kind: 'idle' as const
      }
    }

    if (attentionRows.length) {
      return {
        label: isAdmin ? 'Abrir bandeja operativa' : 'Revisar pendientes',
        detail: 'Entra solo a los ficheros que hoy piden clasificación, periodo o reproceso.',
        kind: 'board' as const
      }
    }

    if (!summary?.totalFiles) {
      return {
        label: copy.scanButton,
        detail: 'Lanza un escaneo para detectar nuevos ficheros en la bandeja del cliente.',
        kind: 'scan' as const
      }
    }

    return {
      label: 'Volver al cierre mensual',
      detail: `El estado oficial del periodo se sigue desde Cierre mensual${workPeriod ? ` (${workPeriod})` : ''}.`,
      kind: 'route' as const,
      href: workflowHref
    }
  }, [attentionRows.length, companyId, copy.scanButton, isAdmin, summary?.totalFiles, workPeriod, workflowHref])

  return (
    <div>
      <PageHeader
        title="Pipeline"
        subtitle="Solo clasificar, reprocesar o volver al cierre."
        actions={<span className="badge">{String(plan || 'BRONZE').toUpperCase()}</span>}
      />

      {!companyId ? <Alert tone="warning">{copy.noCompanyAlert}</Alert> : null}

      <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">1. Estado de la bandeja</h3>
          <span className="upload-hint">La lectura mínima para saber si hay trabajo hoy.</span>
        </div>
        <div className="grid grid-autofit-220 mt-12">
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Situación</div>
            <div className="fw-800 mt-1">{pipelineDecisionState.title}</div>
            <div className="upload-hint mt-1">{pipelineDecisionState.detail}</div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Pendientes</div>
            <div className="fw-800 mt-1">{attentionRows.length}</div>
            <div className="upload-hint mt-1">
              {attentionRows.length
                ? `${attentionCounts.blocked} bloqueados · ${attentionCounts.review} por revisar`
                : 'No hay ficheros pendientes ahora mismo.'}
            </div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Siguiente paso</div>
            <div className="fw-800 mt-1">{pipelineNextAction.label}</div>
            <div className="upload-hint mt-1">{pipelineNextAction.detail}</div>
          </div>
        </div>
        <div className="row row-wrap gap-8 mt-12">
          <Button onClick={() => scanMutation.mutate()} disabled={!companyId} loading={scanMutation.isPending}>
            {copy.scanButton}
          </Button>
          {pipelineNextAction.kind === 'route' && pipelineNextAction.href ? (
            <Link className="badge" to={pipelineNextAction.href}>
              Abrir cierre
            </Link>
          ) : null}
        </div>
      </div>

      <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">2. Ficheros a revisar</h3>
          <span className="upload-hint">Solo los primeros pendientes.</span>
        </div>
        {!attentionRows.length ? (
          <div className="empty mt-12">Sin pendientes operativos.</div>
        ) : (
          <div className="stack gap-10 mt-12">
            {attentionRows.slice(0, 5).map((row) => {
              const next = pipelineRowNextStep(row, isAdmin, copy)
              return (
                <div key={row.id} className="card soft card-pad-sm">
                  <div className="row row-wrap row-center gap-8">
                    <span className={`badge ${importStatusBadgeTone(row.status)}`}>{row.status}</span>
                    <span className="fw-700">{row.filename}</span>
                  </div>
                  <div className="upload-hint mt-8">{next.detail}</div>
                  <div className="row row-wrap gap-8 mt-12">
                    {isAdmin ? (
                      <Button size="sm" variant="secondary" onClick={() => {
                        setEditingFileId(row.id)
                        setPeriodDraft(String(row.period || ''))
                        setKindDraft(normalizeKind(row.detectedKind))
                      }}>
                        Corregir
                      </Button>
                    ) : null}
                    {canRetryRow(row) ? (
                      <Button size="sm" variant="ghost" onClick={() => retryMutation.mutate(row.id)}>
                        Reprocesar
                      </Button>
                    ) : null}
                    <Link className="badge" to={moduleHref(row)}>
                      Abrir módulo
                    </Link>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )

  return (
    <div>
      <PageHeader
        title="Pipeline Center"
        subtitle={copy.subtitle}
        actions={
          <div className="mini-row mt-0 row-wrap row-center gap-8">
            <span className="badge">{String(plan || 'BRONZE').toUpperCase()}</span>
            <Button onClick={() => scanMutation.mutate()} disabled={!companyId} loading={scanMutation.isPending}>
              {copy.scanButton}
            </Button>
            <details className="maxw-260">
              <summary className="badge cursor-pointer">Opciones</summary>
              <div className="card soft card-pad-sm mt-8">
                <div className="row row-wrap gap-8">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      void queryClient.invalidateQueries({ queryKey: ['pipeline-summary', companyId] })
                      void queryClient.invalidateQueries({ queryKey: ['pipeline-files', companyId] })
                      void queryClient.invalidateQueries({ queryKey: ['pipeline-rules', companyId] })
                    }}
                    disabled={!companyId}
                  >
                    {copy.refreshButton}
                  </Button>
                  {companyId ? (
                    <Link className="badge" to={workflowHref}>
                      Cierre mensual
                    </Link>
                  ) : null}
                </div>
              </div>
            </details>
          </div>
        }
      />

      {!companyId ? <Alert tone="warning">{copy.noCompanyAlert}</Alert> : null}

      <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">Qué hacer aquí</h3>
          <span className="upload-hint">Solo clasificar, reprocesar o volver al cierre del periodo.</span>
        </div>
        <div className="grid grid-autofit-220 mt-12">
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Situación</div>
            <div className="fw-800 mt-1">{pipelineDecisionState.title}</div>
            <div className="upload-hint mt-1">{pipelineDecisionState.detail}</div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Pendientes visibles</div>
            <div className="fw-800 mt-1">{attentionRows.length ? `${attentionRows.length} fichero${attentionRows.length === 1 ? '' : 's'}` : 'Sin pendientes'}</div>
            <div className="upload-hint mt-1">
              {attentionRows.length
                ? `${attentionCounts.blocked} bloqueados · ${attentionCounts.review} para revisar · ${attentionCounts.processing} en proceso`
                : `El estado oficial sigue desde Cierre mensual${workPeriod ? ` (${workPeriod})` : ''}.`}
            </div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Siguiente paso</div>
            <div className="fw-800 mt-1">{pipelineNextAction.label}</div>
            <div className="upload-hint mt-1">{pipelineNextAction.detail}</div>
            {pipelineNextAction.kind === 'board' ? (
              <div className="mt-2">
                <Button size="sm" variant="secondary" onClick={() => boardRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })}>
                  Abrir bandeja
                </Button>
              </div>
            ) : null}
            {pipelineNextAction.kind === 'scan' ? (
              <div className="mt-2">
                <Button size="sm" variant="secondary" onClick={() => scanMutation.mutate()} disabled={!companyId} loading={scanMutation.isPending}>
                  {copy.scanButton}
                </Button>
              </div>
            ) : null}
            {pipelineNextAction.kind === 'route' && pipelineNextAction.href ? (
              <div className="mt-2">
                <Link className="badge" to={pipelineNextAction.href}>
                  {pipelineNextAction.label}
                </Link>
              </div>
            ) : null}
          </div>
        </div>
      </div>

      {isAdmin && editingFileId && activeRow ? (
        <div className="card soft card-pad-sm section">
          <div className="mini-row mt-0 row-center">
            <h3 className="m-0">Corregir clasificación</h3>
            <span className="upload-hint">{activeRow.filename}</span>
          </div>
          <div className="pipeline-edit-grid mt-12">
            <div className="pipeline-edit-block">
              <div className="upload-hint">Periodo</div>
              <div className="pipeline-edit-row mt-8">
                <input
                  value={periodDraft}
                  onChange={(e) => setPeriodDraft(e.target.value)}
                  placeholder="YYYY-MM"
                  className="pipeline-period-input"
                />
                <Button onClick={() => periodMutation.mutate({ fileId: activeRow.id, period: periodDraft })} loading={periodMutation.isPending}>
                  Guardar periodo
                </Button>
              </div>
            </div>
            <div className="pipeline-edit-block">
              <div className="upload-hint">Tipo</div>
              <div className="pipeline-edit-row mt-8">
                <select value={kindDraft} onChange={(e) => setKindDraft(normalizeKind(e.target.value))} className="pipeline-kind-input">
                  <option value="TRANSACTIONS">Caja / transacciones</option>
                  <option value="TRIBUNAL">Tribunal</option>
                  <option value="UNIVERSAL">Universal</option>
                  <option value="UNKNOWN">Sin clasificar</option>
                </select>
                <Button onClick={() => kindMutation.mutate({ fileId: activeRow.id, kind: kindDraft })} loading={kindMutation.isPending}>
                  Guardar tipo
                </Button>
              </div>
            </div>
            <div className="pipeline-edit-actions">
              <Button
                variant="ghost"
                onClick={() => {
                  setEditingFileId(null)
                  setPeriodDraft('')
                  setKindDraft('TRANSACTIONS')
                }}
              >
                Cerrar
              </Button>
            </div>
          </div>
          <div className="upload-hint mt-8">
            Corrige periodo o tipo cuando el nombre del fichero no ayude. Si lo conviertes en una pieza interpretable, el sistema ya puede reprocesarlo desde aquí mismo.
          </div>
        </div>
      ) : null}

      <div className="card section" ref={boardRef}>
        <div className="mini-row mt-0 row-center">
          <h3 className="m-0">Bandeja operativa</h3>
          <span className="upload-hint">Solo los ficheros que hoy requieren intervención.</span>
        </div>

        {!companyId ? (
          renderState('Empresa pendiente', 'Selecciona una empresa para ver qué ficheros necesitan acción.')
        ) : filesQuery.isPending ? (
          <div className="panel-state panel-state-loading mt-12">
            <div className="panel-state-title">Cargando bandeja</div>
            <div className="panel-state-detail">Estamos filtrando solo los ficheros que requieren clasificación, periodo o reproceso.</div>
          </div>
        ) : filesQuery.error ? (
          <div className="panel-state mt-12">
            <div className="panel-state-title">No se pudo cargar</div>
            <div className="panel-state-detail">{String((filesQuery.error as any)?.message || 'Error inesperado.')}</div>
          </div>
        ) : !attentionRows.length ? (
          <div className="empty mt-12">Sin pendientes. Vuelve al cierre mensual.</div>
        ) : (
          <div className="table-wrap mt-12">
            <table className="table table-fixed">
              <thead>
                <tr>
                  <th className="w-320">Fichero</th>
                  <th>Qué falta</th>
                  <th className="w-320">Siguiente paso</th>
                </tr>
              </thead>
              <tbody>
                {attentionRows.map((row: PipelineFileDto) => {
                  const nextStep = pipelineRowNextStep(row, isAdmin, copy)
                  return (
                    <tr key={row.id}>
                      <td>
                        <div className="fw-600">{row.filename}</div>
                        <div className="row row-wrap row-center gap-8 mt-8">
                          <span className="badge">{pipelineKindLabel(row.detectedKind)}</span>
                          <span className="badge">{row.period || 'Sin periodo'}</span>
                          <span className="upload-hint">Detectado {formatDateTime(row.detectedAt)}</span>
                        </div>
                      </td>
                      <td>
                        <span className={`badge ${row.badgeTone || importStatusBadgeTone(row.status)}`}>{row.statusTitle || row.status}</span>
                        <div className="upload-hint mt-8">{row.statusDetail || row.message || EMPTY_MESSAGE_TEXT}</div>
                        {row.importJobId != null ? <div className="upload-hint mt-8">Import #{row.importJobId}</div> : null}
                      </td>
                      <td className="nowrap">
                        <div className="fw-700">{nextStep.title}</div>
                        <div className="upload-hint mt-8">{nextStep.detail}</div>
                        <div className="pipeline-actions mt-8">
                          {isAdmin && canEditKind(row) ? (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => {
                                setEditingFileId(row.id)
                                setPeriodDraft(row.period || '')
                                setKindDraft(normalizeKind(row.detectedKind))
                              }}
                            >
                              {copy.classifyButton}
                            </Button>
                          ) : null}
                          {canRetryRow(row) ? (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => retryMutation.mutate({ fileId: row.id })}
                              loading={retryMutation.isPending && editingFileId == null}
                            >
                              {row.actionLabel || copy.retryButton}
                            </Button>
                          ) : null}
                          <Link className="badge" to={moduleHref(row)}>
                            {pipelineModuleLabel(row)}
                          </Link>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <details className="card section">
        <summary className="mini-row cursor-pointer mt-0">
          <strong>Estado general e histórico</strong>
          <span className="upload-hint">Trazabilidad completa y contexto histórico solo cuando necesites bajar un nivel.</span>
        </summary>

        <div className="grid section section-2cols mt-12">
          <div className="card">
            <div className="mini-row mt-0">
              <h3 className="m-0">{summaryHeadline}</h3>
              {summary?.lastDetectedAt ? <span className="upload-hint">Último detectado: {formatDateTime(summary.lastDetectedAt)}</span> : null}
            </div>

            {!companyId ? (
              renderState('Empresa pendiente', 'Selecciona una empresa para cargar el resumen del pipeline.')
            ) : summaryQuery.isPending ? (
              <div className="panel-state panel-state-loading">
                <div className="panel-state-title">Cargando resumen</div>
                <div className="panel-state-detail">Estamos leyendo el histórico y el estado actual de la bandeja.</div>
              </div>
            ) : summaryQuery.error ? (
              renderState('No se pudo cargar', String((summaryQuery.error as any)?.message || 'Error inesperado.'))
            ) : summary ? (
              <div className="insight-grid mt-12">
                <div className="metric-card">
                  <div className="metric-label">Ficheros</div>
                  <div className="metric-value">{summary.totalFiles}</div>
                  <div className="upload-hint">Histórico detectado</div>
                </div>
                <div className="metric-card ok">
                  <div className="metric-label">Enviados</div>
                  <div className="metric-value">{summary.doneFiles}</div>
                  <div className="upload-hint">Listos en cola o procesados</div>
                </div>
                <div className="metric-card warn">
                  <div className="metric-label">Saltados</div>
                  <div className="metric-value">{summary.skippedFiles}</div>
                  <div className="upload-hint">Necesitan clasificación o ajuste</div>
                </div>
                <div className="metric-card err">
                  <div className="metric-label">Errores</div>
                  <div className="metric-value">{summary.errorFiles}</div>
                  <div className="upload-hint">Pendientes de reproceso</div>
                </div>
              </div>
            ) : summary ? (
              renderState(summaryHeadline, summaryDetail)
            ) : (
              renderState(EMPTY_ACTIVITY_TEXT, 'Sin ficheros detectados.')
            )}
          </div>

          <div className="card">
            <h3 className="h3-reset">Lectura rápida</h3>
            {!summary ? (
              <div className="empty mt-12">Sin lectura operativa. {copy.summaryHint}</div>
            ) : (
              <div className="upload-hint mt-8">{summaryDetail}</div>
            )}

            <div className="mini-row mt-12">
              <span className={`badge ${summary?.badgeTone || ''}`}>{summary?.actionLabel || 'Clasificación automática'}</span>
              <span className="upload-hint">{isAdmin ? 'Subcarpetas, reglas y tipo por defecto' : 'Entrada, clasificación y reproceso desde un único punto'}</span>
            </div>
          </div>
        </div>

        <div className="mt-12">
          {!companyId ? (
            renderState('Empresa pendiente', 'Selecciona una empresa para ver su histórico de ficheros.')
          ) : filesQuery.isPending ? (
            <div className="panel-state panel-state-loading mt-12">
              <div className="panel-state-title">Cargando histórico</div>
              <div className="panel-state-detail">Estamos leyendo los últimos ficheros detectados y su estado de pipeline.</div>
            </div>
          ) : filesQuery.error ? (
            <div className="panel-state mt-12">
              <div className="panel-state-title">No se pudo cargar</div>
              <div className="panel-state-detail">{String((filesQuery.error as any)?.message || 'Error inesperado.')}</div>
            </div>
          ) : !files.length ? (
            <div className="empty mt-12">
              <div className="panel-state-title">Sin ficheros</div>
              <div className="panel-state-detail">
                Cuando entren CSV o XLSX en la bandeja, aquí verás trazabilidad, clasificación y paso a cola de importación.
              </div>
            </div>
          ) : (
            <div className="table-wrap mt-12">
              <table className="table table-fixed">
                <thead>
                  <tr>
                    <th className="w-320">Fichero</th>
                    <th>Qué pasó</th>
                    <th className="w-320">Siguiente paso</th>
                  </tr>
                </thead>
                <tbody>
                  {files.map((row: PipelineFileDto) => {
                    const nextStep = pipelineRowNextStep(row, isAdmin, copy)
                    return (
                      <tr key={row.id}>
                        <td>
                          <div className="fw-600">{row.filename}</div>
                          <div className="row row-wrap row-center gap-8 mt-8">
                            <span className="badge">{pipelineKindLabel(row.detectedKind)}</span>
                            <span className="badge">{row.period || 'Sin periodo'}</span>
                            <span className="upload-hint">Detectado {formatDateTime(row.detectedAt)}</span>
                          </div>
                        </td>
                        <td>
                          <span className={`badge ${row.badgeTone || importStatusBadgeTone(row.status)}`}>{row.statusTitle || row.status}</span>
                          <div className="upload-hint mt-8">{row.statusDetail || row.message || EMPTY_MESSAGE_TEXT}</div>
                          <div className="upload-hint mt-8">
                            {row.importJobId != null ? `Import #${row.importJobId}` : 'Sin import asociado todavía'}
                          </div>
                        </td>
                        <td className="nowrap">
                          <div className="fw-700">{nextStep.title}</div>
                          <div className="upload-hint mt-8">{nextStep.detail}</div>
                          <div className="pipeline-actions mt-8">
                            {isAdmin && canEditKind(row) ? (
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={() => {
                                  setEditingFileId(row.id)
                                  setPeriodDraft(row.period || '')
                                  setKindDraft(normalizeKind(row.detectedKind))
                                }}
                              >
                                {copy.classifyButton}
                              </Button>
                            ) : null}
                            {canRetryRow(row) ? (
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={() => retryMutation.mutate({ fileId: row.id })}
                                loading={retryMutation.isPending && editingFileId == null}
                              >
                                {row.actionLabel || copy.retryButton}
                              </Button>
                            ) : null}
                            <Link className="badge" to={moduleHref(row)}>
                              {pipelineModuleLabel(row)}
                            </Link>
                          </div>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </details>

      <details className="reports-history-details section">
        <summary>Cómo funciona esta bandeja</summary>
        <Alert tone="info" title="Entrada operativa">
          <div className="upload-hint mt-8">
            {copy.howItWorks.split('`storage/inbox/{companyId}`')[0]}
            <span className="mono">storage/inbox/{'{companyId}'}</span>
            {copy.howItWorks.split('`storage/inbox/{companyId}`')[1]}
          </div>
        </Alert>
      </details>

      {isAdmin ? (
        <details className="reports-history-details section">
          <summary>Reglas avanzadas de clasificación</summary>
          <div className="card section">
            <div className="mini-row mt-0 row-center">
              <h3 className="m-0">Reglas de clasificación por empresa</h3>
              <span className="upload-hint">{copy.rulesHint}</span>
            </div>

            {!companyId ? (
              renderState('Empresa pendiente', 'Selecciona una empresa para definir sus reglas de clasificación.')
            ) : rulesQuery.isPending ? (
              <div className="panel-state panel-state-loading mt-12">
                <div className="panel-state-title">Cargando reglas</div>
                <div className="panel-state-detail">Estamos leyendo la configuración actual de esta empresa.</div>
              </div>
            ) : rulesQuery.error ? (
              <div className="panel-state mt-12">
                <div className="panel-state-title">No se pudieron cargar las reglas</div>
                <div className="panel-state-detail">{String((rulesQuery.error as any)?.message || 'Error inesperado.')}</div>
              </div>
            ) : (
              <>
                <div className="pipeline-edit-grid mt-12">
                  <div className="pipeline-edit-block">
                    <div className="upload-hint">Tipo por defecto de la empresa</div>
                    <div className="upload-hint mt-8">
                      Se usa cuando ninguna regla especial encaja y el nombre no deja pistas claras como <span className="mono">tribunal</span> o <span className="mono">universal</span>.
                    </div>
                    <div className="pipeline-edit-row mt-8">
                      <select value={defaultKindDraft} onChange={(e) => setDefaultKindDraft(normalizeKind(e.target.value))} className="pipeline-kind-input">
                        <option value="TRANSACTIONS">Caja / transacciones</option>
                        <option value="TRIBUNAL">Tribunal</option>
                        <option value="UNIVERSAL">Universal</option>
                        <option value="UNKNOWN">Sin clasificar</option>
                      </select>
                    </div>
                  </div>
                </div>

                <div className="upload-hint mt-12">
                  Ejemplos útiles: nombre contiene <span className="mono">nominas</span>, o ruta contiene <span className="mono">/tribunal/</span>, <span className="mono">/universal/</span>, <span className="mono">/bancos/</span>.
                </div>
                <div className="table-wrap mt-12">
                  <table className="table table-fixed">
                    <thead>
                      <tr>
                        <th>Texto en nombre</th>
                        <th>Texto en ruta/carpeta</th>
                        <th className="w-180">Clasificar como</th>
                        <th className="w-110">Acción</th>
                      </tr>
                    </thead>
                    <tbody>
                      {ruleDrafts.map((rule, index) => (
                        <tr key={`rule-${index}`}>
                          <td>
                            <input
                              className="pipeline-period-input"
                              value={rule.matchText}
                              onChange={(e) =>
                                setRuleDrafts((current) =>
                                  current.map((item, itemIndex) => (itemIndex === index ? { ...item, matchText: e.target.value } : item))
                                )
                              }
                              placeholder="Ej. tribunal, nominas, bankinter"
                            />
                          </td>
                          <td>
                            <input
                              className="pipeline-period-input"
                              value={rule.pathContains}
                              onChange={(e) =>
                                setRuleDrafts((current) =>
                                  current.map((item, itemIndex) => (itemIndex === index ? { ...item, pathContains: e.target.value } : item))
                                )
                              }
                              placeholder="Ej. /tribunal/, /universal/, /bancos/"
                            />
                          </td>
                          <td>
                            <select
                              value={rule.kind}
                              onChange={(e) =>
                                setRuleDrafts((current) =>
                                  current.map((item, itemIndex) =>
                                    itemIndex === index ? { ...item, kind: normalizeKind(e.target.value) } : item
                                  )
                                )
                              }
                              className="pipeline-kind-input"
                            >
                              <option value="TRANSACTIONS">Caja / transacciones</option>
                              <option value="TRIBUNAL">Tribunal</option>
                              <option value="UNIVERSAL">Universal</option>
                              <option value="UNKNOWN">Sin clasificar</option>
                            </select>
                          </td>
                          <td>
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => setRuleDrafts((current) => current.filter((_, itemIndex) => itemIndex !== index))}
                            >
                              Quitar
                            </Button>
                          </td>
                        </tr>
                      ))}
                      {!ruleDrafts.length ? (
                        <tr>
                          <td colSpan={4}>
                            <div className="panel-state">
                              <div className="panel-state-title">Sin reglas</div>
                              <div className="panel-state-detail">
                                El pipeline ya funciona con reglas por defecto, pero aquí puedes adaptarlo al lenguaje real con el que trabaja cada cliente.
                              </div>
                            </div>
                          </td>
                        </tr>
                      ) : null}
                    </tbody>
                  </table>
                </div>

                <div className="mini-row mt-12">
                  <Button variant="ghost" onClick={() => setRuleDrafts((current) => [...current, { matchText: '', pathContains: '', kind: 'TRANSACTIONS' }])}>
                    Añadir regla
                  </Button>
                  <Button onClick={() => rulesMutation.mutate()} loading={rulesMutation.isPending}>
                    Guardar reglas
                  </Button>
                </div>
              </>
            )}
          </div>
        </details>
      ) : null}
    </div>
  )
}









