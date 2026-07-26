import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useCompanySelection } from '../hooks/useCompany'
import {
  listAutomationJobs,
  runPeriodCloseFlow,
  runRecomputeKpis,
  runSnapshotRecommendations,
  type AutomationJob
} from '../api'
import { useToast } from '../components/ui/ToastProvider'
import { EMPTY_VALUE, formatDateTime } from '../utils/format'
import { getWorkPeriod, nowYm } from '../utils/workPeriod'

function nowYmMinus(months: number) {
  const d = new Date()
  const ym = new Date(d.getFullYear(), d.getMonth() - months, 1)
  const y = ym.getFullYear()
  const m = String(ym.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

function normalize(value?: string | null) {
  return String(value || '').trim().toUpperCase()
}

function automationTypeMeta(type?: string | null) {
  const value = normalize(type)
  if (value.includes('RECOMPUTE') && value.includes('KPI')) {
    return { label: 'Recalcular KPIs', route: '/monthly-close', routeLabel: 'Abrir cierre mensual' }
  }
  if (value.includes('ORCHESTRATE') && value.includes('PERIOD')) {
    return { label: 'Cierre orquestado del periodo', route: '/monthly-close', routeLabel: 'Abrir cierre mensual' }
  }
  if (value.includes('MONTH') && value.includes('REPORT')) {
    return { label: 'Informe mensual', route: '/reports', routeLabel: 'Abrir entregables' }
  }
  if (value.includes('SNAPSHOT') && value.includes('RECOMMEND')) {
    return { label: 'Snapshot consultivo', route: '/advisor', routeLabel: 'Abrir asesor' }
  }
  return { label: String(type || EMPTY_VALUE), route: '/monthly-close', routeLabel: 'Abrir workflow' }
}

function automationStatusTone(status?: string | null) {
  const value = normalize(status)
  if (value === 'OK' || value === 'SUCCESS') return 'ok'
  if (value === 'WARNING' || value === 'PENDING' || value === 'RUNNING' || value === 'RETRY') return 'warn'
  if (value === 'ERROR' || value === 'DEAD' || value === 'BLOCKED') return 'err'
  return ''
}

function automationStatusLabel(status?: string | null) {
  const value = normalize(status)
  if (value === 'PENDING') return 'En cola'
  if (value === 'RUNNING') return 'En curso'
  if (value === 'RETRY') return 'Reintento programado'
  if (value === 'BLOCKED') return 'Bloqueado'
  if (value === 'OK' || value === 'SUCCESS') return 'Completado'
  if (value === 'WARNING') return 'Completado con aviso'
  if (value === 'ERROR') return 'Error tecnico'
  if (value === 'DEAD') return 'Sin salida valida'
  return String(status || EMPTY_VALUE)
}

function isAttentionJob(job: AutomationJob) {
  const value = normalize(job.status)
  return value === 'WARNING' || value === 'BLOCKED' || value === 'ERROR' || value === 'DEAD'
}

function isActiveJob(job: AutomationJob) {
  const value = normalize(job.status)
  return value === 'PENDING' || value === 'RUNNING' || value === 'RETRY'
}

function isHealthyJob(job: AutomationJob) {
  const value = normalize(job.status)
  return value === 'OK' || value === 'SUCCESS'
}

function automationPriority(job: AutomationJob) {
  const value = normalize(job.status)
  if (value === 'BLOCKED' || value === 'ERROR' || value === 'DEAD') return 0
  if (value === 'WARNING') return 1
  if (value === 'RUNNING' || value === 'PENDING' || value === 'RETRY') return 2
  if (value === 'OK' || value === 'SUCCESS') return 3
  return 4
}

function automationNarrative(job: AutomationJob) {
  const value = normalize(job.status)
  const updated = formatDateTime(job.updatedAt)
  const nextRun = formatDateTime(job.runAfter)
  if (value === 'PENDING') return nextRun !== EMPTY_VALUE ? `Queda en cola para ejecutarse a partir de ${nextRun}.` : 'Queda en cola para ejecutarse.'
  if (value === 'RUNNING') return 'Se esta ejecutando ahora mismo sobre la empresa seleccionada.'
  if (value === 'RETRY') {
    return nextRun !== EMPTY_VALUE
      ? `El sistema lo volvera a intentar a partir de ${nextRun}.`
      : 'El sistema ha programado un nuevo intento.'
  }
  if (value === 'BLOCKED') return 'Se ha detenido y necesita intervencion antes de poder continuar.'
  if (value === 'WARNING') return updated !== EMPTY_VALUE ? `Termino con avisos. Ultima actualizacion: ${updated}.` : 'Termino con avisos y conviene revisar la salida.'
  if (value === 'ERROR') return updated !== EMPTY_VALUE ? `Fallo en la ultima ejecucion (${updated}).` : 'Fallo en la ultima ejecucion.'
  if (value === 'DEAD') return 'Agoto los reintentos automaticos y necesita revision manual.'
  if (value === 'OK' || value === 'SUCCESS') return updated !== EMPTY_VALUE ? `Ultima ejecucion correcta: ${updated}.` : 'Ultima ejecucion correcta.'
  return 'Estado disponible para seguimiento operativo.'
}

function automationNextStep(job: AutomationJob) {
  const value = normalize(job.status)
  const meta = automationTypeMeta(job.type)
  if (value === 'BLOCKED') return `Revisa el bloqueo y, cuando la entrada ya sea valida, vuelve a lanzar ${meta.label.toLowerCase()}.`
  if (value === 'ERROR' || value === 'DEAD') return `Corrige la causa raiz y relanza ${meta.label.toLowerCase()} cuando la base ya este lista.`
  if (value === 'WARNING') return `${meta.routeLabel} y decide si hace falta regenerar la salida o dejarla por valida.`
  if (value === 'PENDING' || value === 'RUNNING' || value === 'RETRY') return 'Espera a que termine. Si tarda demasiado, revisa el detalle tecnico y la traza.'
  if (meta.route === '/reports') return 'Abre el entregable y valida el PDF antes de compartirlo con el cliente.'
  if (meta.route === '/advisor') return 'Abre el asesor y confirma que las recomendaciones publicadas son las correctas.'
  return 'Vuelve al cierre mensual y comprueba que la lectura del periodo ya refleja el cambio esperado.'
}

function sortJobs(rows: AutomationJob[]) {
  return [...rows].sort((a, b) => {
    const byPriority = automationPriority(a) - automationPriority(b)
    if (byPriority !== 0) return byPriority
    return new Date(b.updatedAt || b.createdAt || 0).getTime() - new Date(a.updatedAt || a.createdAt || 0).getTime()
  })
}

export default function AutomationPage() {
  const navigate = useNavigate()
  const { id: companyId, plan } = useCompanySelection()
  const toast = useToast()
  const [monthsBack, setMonthsBack] = useState(2)
  const [reportPeriod, setReportPeriod] = useState(() => getWorkPeriod(companyId) || nowYmMinus(1))
  const [recPeriod, setRecPeriod] = useState(() => getWorkPeriod(companyId) || nowYm())
  const [recObjective, setRecObjective] = useState<'GENERAL' | 'CASH' | 'COST' | 'MARGIN' | 'GROWTH' | 'RISK'>('GENERAL')

  useEffect(() => {
    if (!companyId) return
    setReportPeriod(getWorkPeriod(companyId) || nowYmMinus(1))
    setRecPeriod(getWorkPeriod(companyId) || nowYm())
  }, [companyId])

  const { data: jobs, error, refetch, isFetching } = useQuery({
    queryKey: ['automation-jobs', companyId],
    queryFn: () => listAutomationJobs(companyId as number),
    enabled: !!companyId
  })

  const rows = useMemo<AutomationJob[]>(
    () => (Array.isArray(jobs) ? jobs : Array.isArray(jobs?.value) ? jobs.value : []),
    [jobs]
  )

  const sortedRows = useMemo(() => sortJobs(rows), [rows])
  const attentionRows = useMemo(() => sortedRows.filter(isAttentionJob), [sortedRows])
  const activeRows = useMemo(() => sortedRows.filter(isActiveJob), [sortedRows])
  const healthyRows = useMemo(() => sortedRows.filter(isHealthyJob), [sortedRows])
  const visibleRows = useMemo(() => sortedRows.slice(0, 6), [sortedRows])
  const latestHealthyJob = healthyRows[0] || null
  const priorityJob = attentionRows[0] || activeRows[0] || sortedRows[0] || null

  const heroState = useMemo(() => {
    if (!companyId) {
      return {
        title: 'Selecciona una empresa gestionada',
        detail: 'La automatizacion trabaja por empresa. Primero elige una y despues ya podras relanzar procesos concretos.'
      }
    }
    if (!sortedRows.length) {
      return {
        title: 'Automatizacion en espera',
        detail: 'No hay ejecuciones recientes. Esta pantalla queda como apoyo para acelerar un cierre o recuperar un proceso puntual.'
      }
    }
    if (attentionRows.length) {
      return {
        title: 'Hay automatizaciones que requieren revision',
        detail: automationNextStep(attentionRows[0])
      }
    }
    if (activeRows.length) {
      return {
        title: 'La cola sigue trabajando',
        detail: `${activeRows.length} proceso(s) en cola o en ejecucion. No hace falta tocar nada salvo que veas un atasco real.`
      }
    }
    return {
      title: 'Automatizacion estable',
      detail: latestHealthyJob
        ? `${automationTypeMeta(latestHealthyJob.type).label} cerro correctamente y no hay bloqueos activos ahora mismo.`
        : 'No hay bloqueos activos y la pantalla queda disponible solo para apoyo operativo.'
    }
  }, [activeRows, attentionRows, companyId, latestHealthyJob, sortedRows])

  const suggestedAction = useMemo(() => {
    if (priorityJob) {
      const meta = automationTypeMeta(priorityJob.type)
      return {
        title: meta.label,
        detail: automationNextStep(priorityJob),
        cta: meta.routeLabel,
        href: meta.route
      }
    }
    return {
      title: 'Workflow principal del periodo',
      detail: 'Si no hay incidencias tecnicas, el siguiente paso natural no esta aqui sino en el cierre mensual y en los entregables.',
      cta: 'Abrir cierre mensual',
      href: '/monthly-close'
    }
  }, [priorityJob])

  const copyText = async (label: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      toast.push({ tone: 'success', title: 'Copiado', message: `${label} copiado.` })
    } catch {
      toast.push({ tone: 'warning', title: 'Copia manual', message: 'No se pudo copiar automaticamente.' })
    }
  }

  const recompute = useMutation({
    mutationFn: () => runRecomputeKpis(companyId as number, monthsBack),
    onSuccess: async () => {
      toast.push({ tone: 'success', title: 'Automatizacion', message: 'Job de KPIs encolado.' })
      await refetch()
    },
    onError: (e: Error) => toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo encolar.' })
  })

  const closeFlow = useMutation({
    mutationFn: () => runPeriodCloseFlow(companyId as number, reportPeriod),
    onSuccess: async () => {
      toast.push({ tone: 'success', title: 'Automatizacion', message: 'Job de cierre orquestado encolado.' })
      await refetch()
    },
    onError: (e: Error) => toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo encolar.' })
  })

  const snapshot = useMutation({
    mutationFn: () => runSnapshotRecommendations(companyId as number, recPeriod, recObjective),
    onSuccess: async () => {
      toast.push({ tone: 'success', title: 'Automatizacion', message: 'Job de recomendaciones encolado.' })
      await refetch()
    },
    onError: (e: Error) => toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo encolar.' })
  })

  return (
    <div className="automation-page">
      <PageHeader
        title="Control de automatizacion"
        subtitle="Una vista de apoyo para acelerar, recuperar o relanzar el flujo oficial del periodo cuando la operacion necesita supervision."
        actions={<span className="pill">Plan: {String(plan || 'BRONZE').toUpperCase()}</span>}
      />

      {!companyId ? (
        <Alert tone="warning" className="mb-3" title="Selecciona una empresa">
          La automatizacion se opera por empresa gestionada.
        </Alert>
      ) : null}

      {error ? (
        <Alert tone="danger" className="mb-3" title="No se pudieron cargar las automatizaciones">
          {String((error as Error)?.message || error)}
        </Alert>
      ) : null}

      {companyId ? (
        <>
          <div className="card section soft automation-hero-shell">
            <div className="automation-hero-grid">
              <div>
                <div className="overview-card-eyebrow">Estado operativo</div>
                <div className="automation-hero-title mt-1">{heroState.title}</div>
                <div className="automation-hero-detail mt-1">{heroState.detail}</div>
                <div className="automation-hero-metrics mt-12">
                  <div className="automation-metric-card">
                    <span className="upload-hint">En atencion</span>
                    <strong>{attentionRows.length}</strong>
                    <span className="upload-hint">{attentionRows.length ? 'Procesos con bloqueo, aviso o error.' : 'Sin incidencias activas.'}</span>
                  </div>
                  <div className="automation-metric-card">
                    <span className="upload-hint">En cola o en curso</span>
                    <strong>{activeRows.length}</strong>
                    <span className="upload-hint">{activeRows.length ? 'Trabajo todavia en marcha.' : 'Nada pendiente ahora mismo.'}</span>
                  </div>
                  <div className="automation-metric-card">
                    <span className="upload-hint">Ultimo OK</span>
                    <strong>{latestHealthyJob ? automationTypeMeta(latestHealthyJob.type).label : 'Sin OK reciente'}</strong>
                    <span className="upload-hint">
                      {latestHealthyJob ? formatDateTime(latestHealthyJob.updatedAt) : 'Todavia no hay una ejecucion correcta registrada.'}
                    </span>
                  </div>
                </div>
              </div>

              <div className="automation-side-card">
                <div className="upload-hint">Siguiente intervencion sugerida</div>
                <div className="annual-stage-title mt-1">{suggestedAction.title}</div>
                <div className="automation-hero-detail mt-1">{suggestedAction.detail}</div>
                <div className="automation-side-list mt-12">
                  {(attentionRows.length ? attentionRows : activeRows.length ? activeRows : visibleRows).slice(0, 3).map((job) => {
                    const meta = automationTypeMeta(job.type)
                    return (
                      <div key={`focus-${job.id}`} className="automation-side-item">
                        <div className="mini-row mt-0 row-between row-baseline">
                          <strong>{meta.label}</strong>
                          <span className={`badge ${automationStatusTone(job.status)}`.trim()}>{automationStatusLabel(job.status)}</span>
                        </div>
                        <div className="upload-hint mt-1">{automationNarrative(job)}</div>
                      </div>
                    )
                  })}
                </div>
                <div className="row row-wrap gap-10 mt-12">
                  <Button size="sm" variant="secondary" onClick={() => navigate(suggestedAction.href)}>
                    {suggestedAction.cta}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => refetch()} loading={isFetching}>
                    Refrescar estado
                  </Button>
                </div>
              </div>
            </div>
          </div>

          <div className="grid section automation-lane-grid">
            <div className="card soft automation-lane-card">
              <div className="overview-card-eyebrow">1. Base operativa</div>
              <div className="automation-lane-title mt-1">Recalcular KPIs</div>
              <div className="automation-lane-detail mt-1">
                Usalo despues de una nueva carga o cuando quieras refrescar varios meses sin esperar al planificador.
              </div>
              <div className="automation-field-grid mt-12">
                <label className="field">
                  <span className="field-label">Meses hacia atras</span>
                  <input
                    type="number"
                    min={1}
                    max={24}
                    value={monthsBack}
                    onChange={(e) => setMonthsBack(Math.max(1, Math.min(24, Number(e.target.value) || 1)))}
                  />
                  <span className="field-hint">Rehace historico reciente para dejar KPIs coherentes.</span>
                </label>
              </div>
              <div className="row row-wrap gap-10 mt-12">
                <Button onClick={() => recompute.mutate()} disabled={!companyId} loading={recompute.isPending}>
                  Recalcular ahora
                </Button>
                <Button size="sm" variant="ghost" onClick={() => navigate('/monthly-close')}>
                  Abrir cierre
                </Button>
              </div>
            </div>

            <div className="card soft automation-lane-card">
              <div className="overview-card-eyebrow">2. Flujo principal</div>
              <div className="automation-lane-title mt-1">Orquestar cierre del periodo</div>
              <div className="automation-lane-detail mt-1">
                Encola el recorrido oficial del periodo: revision automatica segura, informe mensual, snapshot consultivo y cierre si todo queda en regla.
              </div>
              <div className="automation-field-grid mt-12">
                <label className="field">
                  <span className="field-label">Periodo</span>
                  <input value={reportPeriod} onChange={(e) => setReportPeriod(e.target.value)} placeholder="YYYY-MM" />
                  <span className="field-hint">Usa el periodo activo cuando quieras relanzar el flujo completo.</span>
                </label>
              </div>
              <div className="row row-wrap gap-10 mt-12">
                <Button onClick={() => closeFlow.mutate()} disabled={!companyId} loading={closeFlow.isPending}>
                  Orquestar ahora
                </Button>
                <Button size="sm" variant="ghost" onClick={() => navigate('/monthly-close')}>
                  Abrir workflow
                </Button>
              </div>
            </div>

            <div className="card soft automation-lane-card">
              <div className="overview-card-eyebrow">3. Seguimiento consultivo</div>
              <div className="automation-lane-title mt-1">Generar snapshot consultivo</div>
              <div className="automation-lane-detail mt-1">
                Publica una nueva foto de recomendaciones para dejar preparado el seguimiento del periodo y la conversacion con cliente.
              </div>
              <div className="automation-field-grid mt-12">
                <label className="field">
                  <span className="field-label">Objetivo</span>
                  <select value={recObjective} onChange={(e) => setRecObjective(e.target.value as typeof recObjective)}>
                    <option value="GENERAL">General</option>
                    <option value="CASH">Caja</option>
                    <option value="COST">Costes</option>
                    <option value="MARGIN">Margen</option>
                    <option value="GROWTH">Crecimiento</option>
                    <option value="RISK">Riesgo</option>
                  </select>
                  <span className="field-hint">Enfoca el snapshot segun la conversacion que quieres abrir.</span>
                </label>
                <label className="field">
                  <span className="field-label">Periodo</span>
                  <input value={recPeriod} onChange={(e) => setRecPeriod(e.target.value)} placeholder="YYYY-MM" />
                  <span className="field-hint">Suele coincidir con el periodo ya validado en cierre mensual.</span>
                </label>
              </div>
              <div className="row row-wrap gap-10 mt-12">
                <Button onClick={() => snapshot.mutate()} disabled={!companyId} loading={snapshot.isPending}>
                  Publicar snapshot
                </Button>
                <Button size="sm" variant="ghost" onClick={() => navigate('/advisor')}>
                  Abrir asesor
                </Button>
              </div>
            </div>
          </div>

          <div className="card section soft">
            <div className="mini-row mt-0 row-between row-baseline">
              <h3 className="m-0">Cola operativa</h3>
              <span className="upload-hint">Las ultimas automatizaciones utiles para decidir si seguimos, reintentamos o dejamos correr.</span>
            </div>

            {!visibleRows.length ? (
              <div className="empty mt-12">Todavia no hay ejecuciones registradas para esta empresa.</div>
            ) : (
              <>
                <div className="automation-job-list mt-12">
                  {visibleRows.map((job) => {
                    const meta = automationTypeMeta(job.type)
                    const stateClass = isAttentionJob(job) ? 'is-attention' : isActiveJob(job) ? 'is-live' : 'is-ok'
                    return (
                      <article key={job.id} className={`automation-job-card ${stateClass}`.trim()}>
                        <div className="mini-row mt-0 row-between row-baseline">
                          <div>
                            <div className="overview-card-eyebrow">JOB {job.id}</div>
                            <div className="fw-800 mt-1">{meta.label}</div>
                          </div>
                          <span className={`badge ${automationStatusTone(job.status)}`.trim()}>{automationStatusLabel(job.status)}</span>
                        </div>
                        <div className="automation-job-detail mt-8">{automationNarrative(job)}</div>
                        <div className="automation-job-meta mt-12">
                          <span>Actualizado: {formatDateTime(job.updatedAt)}</span>
                          <span>
                            Intentos {job.attempts}/{job.maxAttempts}
                          </span>
                          <span>Prox. ejecucion: {formatDateTime(job.runAfter)}</span>
                        </div>
                        <div className="automation-job-next mt-12">
                          <span className="field-label">Siguiente paso</span>
                          <div className="automation-job-detail mt-1">{automationNextStep(job)}</div>
                        </div>
                        <div className="row row-wrap gap-10 mt-12">
                          <Button size="sm" variant={isAttentionJob(job) ? 'secondary' : 'ghost'} onClick={() => navigate(meta.route)}>
                            {meta.routeLabel}
                          </Button>
                        </div>
                      </article>
                    )
                  })}
                </div>

                <details className="mt-12">
                  <summary className="upload-hint cursor-pointer">Ver detalle tecnico de cola y trazas</summary>
                  <div className="table-wrap mt-12">
                    <table className="table table-fixed">
                      <thead>
                        <tr>
                          <th className="w-56">ID</th>
                          <th className="w-220">Proceso</th>
                          <th className="w-110">Estado</th>
                          <th className="w-110">Intentos</th>
                          <th className="w-160">Prox. ejecucion</th>
                          <th className="w-180">Trace</th>
                          <th className="w-120" />
                        </tr>
                      </thead>
                      <tbody>
                        {sortedRows.slice(0, 20).map((job) => {
                          const trace = String(job.traceId || '')
                          return (
                            <tr key={`tech-${job.id}`}>
                              <td className="mono">{job.id}</td>
                              <td title={String(job.type || '')}>{automationTypeMeta(job.type).label}</td>
                              <td>
                                <span className={`badge ${automationStatusTone(job.status)}`.trim()}>{automationStatusLabel(job.status)}</span>
                              </td>
                              <td className="mono">
                                {job.attempts}/{job.maxAttempts}
                              </td>
                              <td className="upload-hint mono" title={formatDateTime(job.runAfter)}>
                                {formatDateTime(job.runAfter)}
                              </td>
                              <td className="upload-hint mono" title={trace}>
                                {trace ? trace.slice(0, 12) : EMPTY_VALUE}
                              </td>
                              <td className="text-right nowrap">
                                {trace ? (
                                  <Button size="sm" variant="ghost" onClick={() => copyText('Trace', trace)}>
                                    Copiar
                                  </Button>
                                ) : null}
                              </td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
                  </div>
                </details>

                {attentionRows.some((job) => job.lastError) ? (
                  <details className="mt-12">
                    <summary className="upload-hint cursor-pointer">Ver incidencias recientes</summary>
                    <div className="card soft card-pad-sm mt-2">
                      {attentionRows
                        .filter((job) => job.lastError)
                        .slice(0, 3)
                        .map((job) => (
                          <div key={`err-${job.id}`} className="mb-2">
                            <div className="mini-row mt-0 row-between row-baseline">
                              <span className="badge err">JOB {job.id}</span>
                              <span className="upload-hint">{automationTypeMeta(job.type).label}</span>
                            </div>
                            <div className="upload-hint mt-1">{automationNarrative(job)}</div>
                            <div className="upload-hint mono mt-1 pre-wrap">{String(job.lastError || '').slice(0, 600)}</div>
                          </div>
                        ))}
                      <div className="upload-hint">Usa la traza solo si necesitas bajar al detalle de logs o auditoria operativa.</div>
                    </div>
                  </details>
                ) : null}
              </>
            )}
          </div>
        </>
      ) : null}
    </div>
  )
}
