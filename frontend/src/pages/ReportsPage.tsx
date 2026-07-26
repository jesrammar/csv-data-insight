// @ts-nocheck
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  downloadReportPdf,
  generateReport,
  getChecklist,
  getImports,
  getPeriodWorkflow,
  getReportContent,
  getReports,
  getUserRole,
  listUniversalViews,
  type ReportDto,
  type UniversalViewDto
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'
import { EMPTY_ACTIVITY_TEXT, EMPTY_VALUE, formatDateTime } from '../utils/format'
import { universalAggregationExecutiveLine, universalAggregationModeLabel } from '../utils/universalAggregation'
import { getWorkPeriod, nowYm } from '../utils/workPeriod'

function reportsPlanBadge(planRaw?: string | null) {
  const normalized = String(planRaw || 'BRONZE').toUpperCase()
  if (normalized === 'PLATINUM') return 'Platinum'
  if (normalized === 'GOLD') return 'Gold'
  return 'Bronze'
}

export default function ReportsPage() {
  const { id: companyId, plan } = useCompanySelection()
  const isClient = getUserRole() === 'CLIENTE'
  const queryClient = useQueryClient()
  const toast = useToast()

  const [period, setPeriod] = useState(() => getWorkPeriod(companyId) || nowYm())
  const [html, setHtml] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [showAllVersions, setShowAllVersions] = useState(false)
  const [previewReport, setPreviewReport] = useState<ReportDto | null>(null)
  const [selectedUniversalViewId, setSelectedUniversalViewId] = useState('')

  const workflowHref = `/monthly-close?period=${encodeURIComponent(period)}`

  const { data, isLoading, error: reportsError, refetch } = useQuery({
    queryKey: ['reports', companyId],
    queryFn: () => getReports(companyId as number),
    enabled: !!companyId
  })

  const { data: imports } = useQuery({
    queryKey: ['reports-imports', companyId],
    queryFn: () => getImports(companyId as number),
    enabled: !!companyId
  })

  const { data: universalViews } = useQuery({
    queryKey: ['reports-universal-views', companyId],
    queryFn: () => listUniversalViews(companyId as number),
    enabled: !!companyId && !isClient
  })

  const { data: checklist } = useQuery({
    queryKey: ['reports-checklist', companyId, period],
    queryFn: () => getChecklist(companyId as number, period),
    enabled: !!companyId && !!period
  })

  const workflowQuery = useQuery({
    queryKey: ['reports-workflow', companyId, period],
    queryFn: () => getPeriodWorkflow(companyId as number, period),
    enabled: !!companyId && !!period,
    retry: false
  })

  const availableUniversalViews = (universalViews || []) as UniversalViewDto[]
  const selectedUniversalView = useMemo(
    () => availableUniversalViews.find((view) => String(view.id) === selectedUniversalViewId) || null,
    [availableUniversalViews, selectedUniversalViewId]
  )

  const reportsForUi = useMemo(() => {
    const list = (data || []) as ReportDto[]
    if (showAllVersions) return list
    const seen = new Set<string>()
    const output: ReportDto[] = []
    for (const report of list) {
      const key = String(report?.period || '')
      if (!key || seen.has(key)) continue
      seen.add(key)
      output.push(report)
    }
    return output
  }, [data, showAllVersions])

  const latestReport = reportsForUi[0] || null
  const reportsCount = Array.isArray(data) ? data.length : 0
  const workflow = workflowQuery.data

  const periodImport = useMemo(() => {
    return ((imports || []) as any[])
      .filter((item: any) => String(item?.period || '') === String(period || ''))
      .sort((a: any, b: any) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0] || null
  }, [imports, period])

  const currentPeriodReport = useMemo(() => {
    return ((data || []) as any[]).find((item: any) => String(item?.period || '') === String(period || '')) || null
  }, [data, period])

  const checklistDone = (checklist?.items || []).filter((item: any) => item.done).length
  const checklistTotal = (checklist?.items || []).length

  const periodState = useMemo(() => {
    const importReady = !!periodImport && ['OK', 'WARNING'].includes(String(periodImport.status || ''))
    const reviewReady = checklistTotal > 0 ? checklistDone === checklistTotal : !!currentPeriodReport
    const pdfReady = !!currentPeriodReport

    return {
      importStep: !periodImport
        ? { title: 'Sin carga válida', detail: 'Este periodo todavía no tiene una ingesta registrada.' }
        : importReady
          ? { title: `Import ${periodImport.status}`, detail: 'La base del periodo ya está lista para generar entregable.' }
          : { title: `Import ${periodImport.status}`, detail: 'Corrige el import antes de emitir el informe.' },
      reviewStep: reviewReady
        ? { title: 'Lectura preparada', detail: 'El periodo ya tiene validación suficiente para cerrar entregable.' }
        : {
            title: 'Lectura pendiente',
            detail: checklistTotal
              ? `${checklistDone}/${checklistTotal} comprobaciones completadas para este periodo.`
              : 'Valida la lectura del periodo antes de generar el PDF.'
          },
      pdfStep: pdfReady
        ? { title: 'PDF generado', detail: `Ya existe un informe disponible para ${period}.` }
        : { title: 'PDF pendiente', detail: 'Todavía no has generado el entregable de este periodo.' }
    }
  }, [checklistDone, checklistTotal, currentPeriodReport, period, periodImport])

  const reportFocus = useMemo(() => {
    if (!companyId) {
      return {
        title: 'Selecciona una empresa',
        detail: 'El entregable siempre se opera por empresa gestionada.',
        cta: null as string | null,
        action: null as 'view' | 'download' | 'generate' | null,
        href: null as string | null
      }
    }
    if (currentPeriodReport) {
      return {
        title: 'Entregable listo',
        detail: `El informe vigente de ${period} ya está disponible para revisión o descarga.`,
        cta: 'Abrir vista previa',
        action: 'view' as const,
        href: null
      }
    }
    if (isClient) {
      return {
        title: 'Pendiente de publicación',
        detail: 'Tu consultoría todavía no ha publicado el entregable de este periodo.',
        cta: null,
        action: null,
        href: null
      }
    }
    if (workflow?.status === 'EXCEPTIONS') {
      return {
        title: 'Falta corregir el periodo',
        detail: workflow.statusDetail || workflow.notes || 'Antes de emitir el informe, corrige las incidencias del workflow.',
        cta: 'Abrir cierre',
        action: null,
        href: workflowHref
      }
    }
    if (!periodImport) {
      return {
        title: 'Falta base del periodo',
        detail: 'Sin una carga válida no tiene sentido emitir entregable.',
        cta: 'Cargar datos',
        action: null,
        href: '/imports'
      }
    }
    if (workflow?.status === 'REPORT_READY' || workflow?.status === 'CLOSED') {
      return {
        title: 'Listo para emitir o revisar',
        detail: workflow.statusDetail || workflow.notes || 'El workflow ya ha dejado el periodo listo para entregable.',
        cta: 'Generar informe',
        action: 'generate' as const,
        href: null
      }
    }
    return {
      title: workflow?.statusTitle || 'Sigue el workflow oficial',
      detail: workflow?.statusDetail || workflow?.notes || 'La forma más fiable de llegar al PDF es seguir el cierre mensual del periodo.',
      cta: 'Abrir cierre',
      action: null,
      href: workflowHref
    }
  }, [companyId, currentPeriodReport, isClient, period, periodImport, workflow, workflowHref])

  const reportSupportOpen = !currentPeriodReport || workflow?.status === 'EXCEPTIONS'

  useEffect(() => {
    if (!companyId) return
    setPeriod(getWorkPeriod(companyId) || nowYm())
    setSelectedUniversalViewId('')
  }, [companyId])

  useEffect(() => {
    if (!selectedUniversalViewId) return
    if (!availableUniversalViews.some((view) => String(view.id) === selectedUniversalViewId)) {
      setSelectedUniversalViewId('')
    }
  }, [availableUniversalViews, selectedUniversalViewId])

  function renderPanelState(title: string, detail?: string, tone: 'default' | 'loading' | 'locked' = 'default', className = 'mt-3') {
    return (
      <div className={`panel-state panel-state-${tone} ${className}`.trim()}>
        <div className="panel-state-title">{title}</div>
        {detail ? <div className="panel-state-detail">{detail}</div> : null}
      </div>
    )
  }

  function findReport(reportId: number) {
    return (((data || []) as ReportDto[]).find((item) => item.id === reportId) || null) as ReportDto | null
  }

  async function handleGenerate() {
    if (!companyId) return
    setError('')
    setSuccess('')
    try {
      const created = await generateReport(companyId, period, selectedUniversalView ? selectedUniversalView.id : null)
      await queryClient.invalidateQueries({ queryKey: ['reports', companyId] })

      try {
        if (created?.id) {
          const content = await getReportContent(companyId, created.id)
          setHtml(content)
          setPreviewReport(created)
        }
      } catch {
        // ignore preview refresh errors after successful generation
      }

      const universalMessage = created?.selectedUniversalViewName ? ` Vista Universal: ${created.selectedUniversalViewName}.` : ''
      setSuccess(`Informe generado. Ya puedes revisarlo en pantalla o descargarlo en PDF.${universalMessage}`)
      toast.push({ tone: 'success', title: 'Informe', message: `Generado para ${period}.` })
    } catch (err: any) {
      setError(err.message)
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'No se pudo generar el informe.' })
    }
  }

  async function handleView(reportId: number) {
    if (!companyId) return
    try {
      const content = await getReportContent(companyId, reportId)
      setHtml(content)
      setPreviewReport(findReport(reportId))
    } catch (err: any) {
      const msg = String(err?.message || err || 'No se pudo abrir.')
      setError(
        msg.toLowerCase().includes('retencion') || msg.toLowerCase().includes('no esta disponible')
          ? 'Este informe fue limpiado por la retención de ficheros. Genera uno nuevo para ese periodo.'
          : msg
      )
      toast.push({ tone: 'danger', title: 'Error', message: 'No se pudo abrir el informe.' })
    }
  }

  async function handleDownloadPdf(reportId: number, periodLabel?: string) {
    if (!companyId) return
    try {
      const blob = await downloadReportPdf(companyId, reportId)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `enterpriseiq-report-${periodLabel || reportId}.pdf`
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
      toast.push({ tone: 'success', title: 'PDF', message: 'Descarga iniciada.' })
    } catch (err: any) {
      const msg = String(err?.message || err || 'No se pudo descargar el PDF.')
      setError(
        msg.toLowerCase().includes('retencion') || msg.toLowerCase().includes('no esta disponible')
          ? 'Este informe fue limpiado por la retención de ficheros. Genera uno nuevo para ese periodo.'
          : msg
      )
      toast.push({ tone: 'danger', title: 'Error', message: 'No se pudo descargar el PDF.' })
    }
  }

  return (
    <div className="reports-page">
      <PageHeader
        title={isClient ? 'Informes' : 'Entregables mensuales'}
        subtitle={isClient ? 'Una sola vista para revisar o descargar el entregable vigente.' : 'Genera, revisa y descarga el entregable vigente del periodo.'}
        actions={<span className="badge">{reportsPlanBadge(plan)}</span>}
      />

      {!companyId ? <Alert tone="warning">Selecciona una empresa.</Alert> : null}
      {error ? <Alert tone="danger" className="mb-3">{error}</Alert> : null}
      {success ? <Alert tone="success" className="mb-3">{success}</Alert> : null}

      <div className="card section soft reports-period-shell">
        <div className="mini-row row-between row-center row-wrap gap-8">
          <div>
            <h3 className="m-0">Entregable del periodo</h3>
            <div className="upload-hint mt-8">
              Periodo activo: <span className="badge">{period}</span>
            </div>
          </div>
          {workflow?.statusTitle ? <span className="badge">{workflow.statusTitle.replace('Workflow ', '')}</span> : null}
        </div>
        <div className="card soft card-pad-sm mt-12 reports-flow-card">
          <div className="fw-800">{reportFocus.title}</div>
          <div className="upload-hint mt-8">{reportFocus.detail}</div>
          {reportFocus.cta ? (
            <div className="row row-wrap gap-8 mt-12">
              {reportFocus.href ? (
                <Link className="badge" to={reportFocus.href}>
                  {reportFocus.cta}
                </Link>
              ) : reportFocus.action === 'view' && currentPeriodReport ? (
                <Button size="sm" variant="secondary" onClick={() => handleView(currentPeriodReport.id)}>
                  {reportFocus.cta}
                </Button>
              ) : reportFocus.action === 'generate' ? (
                <Button size="sm" variant="secondary" onClick={handleGenerate} disabled={!companyId}>
                  {reportFocus.cta}
                </Button>
              ) : null}
              {currentPeriodReport ? (
                <Button size="sm" variant="ghost" onClick={() => handleDownloadPdf(currentPeriodReport.id, currentPeriodReport.period)}>
                  Descargar PDF
                </Button>
              ) : null}
            </div>
          ) : null}
        </div>
      </div>

      <details className="reports-support-details" open={reportSupportOpen}>
        <summary>
          <div>
            <div className="fw-700">Contexto y versiones</div>
            <div className="upload-hint">Estado del periodo, emisión manual e histórico solo cuando hace falta profundizar.</div>
          </div>
          <div className="row row-center row-wrap gap-8">
            <span className={`badge ${currentPeriodReport ? 'ok' : 'warn'}`}>{currentPeriodReport ? 'PDF listo' : 'PDF pendiente'}</span>
            <span className="badge">{reportsCount} PDF</span>
          </div>
        </summary>
        <div className="reports-support-body">
          <div className="card section soft reports-period-shell">
            <div className="mini-row row-baseline">
              <h3 className="m-0">Lectura corta del periodo</h3>
              <span className="upload-hint">Solo el contexto mínimo para decidir si emitir, revisar o volver al workflow.</span>
            </div>
            <div className="upload-hint mt-8">
              Periodo activo: <span className="badge">{period}</span>
            </div>
            <div className="grid grid-autofit-220 mt-12 reports-period-grid">
              <div className="card soft card-pad-sm reports-flow-card">
                <div className="upload-hint">1. Datos</div>
                <div className="fw-800 mt-1">{periodState.importStep.title}</div>
                <div className="upload-hint mt-1">{periodState.importStep.detail}</div>
                {!isClient ? (
                  <div className="mt-2">
                    <Link className="badge" to="/imports">
                      Revisar imports
                    </Link>
                  </div>
                ) : null}
              </div>
              <div className="card soft card-pad-sm reports-flow-card">
                <div className="upload-hint">2. Validación</div>
                <div className="fw-800 mt-1">{periodState.reviewStep.title}</div>
                <div className="upload-hint mt-1">{periodState.reviewStep.detail}</div>
                {!isClient ? (
                  <div className="mt-2">
                    <Link className="badge" to={workflowHref}>
                      Abrir cierre
                    </Link>
                  </div>
                ) : null}
              </div>
              <div className="card soft card-pad-sm reports-flow-card">
                <div className="upload-hint">3. PDF</div>
                <div className="fw-800 mt-1">{periodState.pdfStep.title}</div>
                <div className="upload-hint mt-1">{periodState.pdfStep.detail}</div>
                {currentPeriodReport ? (
                  <div className="mt-2">
                    <Button variant="ghost" size="sm" onClick={() => handleDownloadPdf(currentPeriodReport.id, currentPeriodReport.period)}>
                      Descargar PDF
                    </Button>
                  </div>
                ) : null}
              </div>
            </div>
          </div>

          {!isClient ? (
            <div className="card section reports-emit-shell">
              <div className="mini-row row-baseline mb-12">
                <h3 className="m-0">Emisión manual</h3>
                <span className="upload-hint">Úsala solo cuando quieras forzar o rehacer el entregable del periodo.</span>
              </div>
              {!companyId ? renderPanelState('Falta seleccionar empresa gestionada', 'Elige una empresa gestionada arriba para generar y revisar informes.') : null}
              {companyId ? (
                availableUniversalViews.length ? (
                  <div className="report-view-picker">
                    <div className="upload-hint">Vista Universal del entregable</div>
                    <select value={selectedUniversalViewId} onChange={(event) => setSelectedUniversalViewId(event.target.value)}>
                      <option value="">Automática: última guardada</option>
                      {availableUniversalViews.map((view) => (
                        <option key={view.id} value={view.id}>
                          {`${view.name} · ${universalAggregationModeLabel(view.aggregationMode)}`}
                        </option>
                      ))}
                    </select>
                    <div className="upload-hint">
                      {selectedUniversalView
                        ? `${universalAggregationExecutiveLine(selectedUniversalView.aggregationMode)} Entrará "${selectedUniversalView.name}".`
                        : 'Si no eliges una concreta, el entregable usará la última vista Universal guardada.'}
                    </div>
                  </div>
                ) : (
                  <div className="upload-hint">No hay vistas Universal guardadas. El entregable se generará solo con cierre, KPIs y snapshot consultivo.</div>
                )
              ) : null}
              <div className="upload-row">
                <input value={period} onChange={(event) => setPeriod(event.target.value)} placeholder="YYYY-MM" inputMode="numeric" />
                <Button onClick={handleGenerate} disabled={!companyId}>
                  Generar informe
                </Button>
              </div>
            </div>
          ) : (
            <div className="card section reports-emit-shell">
              <Alert tone="info" title="Solo lectura">
                Tu consultora prepara y valida los informes. Aquí puedes revisarlos cuando estén listos para compartir.
              </Alert>
              <div className="mt-12">
                <Button variant="ghost" size="sm" onClick={() => queryClient.invalidateQueries({ queryKey: ['reports', companyId] })}>
                  Refrescar
                </Button>
              </div>
            </div>
          )}

          <div className="card section">
            <div className="mini-row row-baseline mb-12">
              <h3 className="m-0">{isClient ? 'Disponibles' : 'Historial'}</h3>
              <span className="upload-hint">Versiones y descargas cuando necesites revisar o recuperar un entregable anterior.</span>
            </div>
            {!companyId ? (
              renderPanelState('Sin empresa', 'Selecciona una empresa.', 'default', 'mt-12')
            ) : isLoading ? (
              renderPanelState('Cargando informes', 'Estoy recuperando el historial para esta empresa gestionada.', 'loading', 'mt-12')
            ) : reportsError ? (
              <div className="mt-12">
                <Alert tone="danger" title="No se pudo cargar">
                  <div className="row row-wrap gap-8 row-center">
                    <span>{String((reportsError as any)?.message || 'Inténtalo de nuevo en unos segundos.')}</span>
                    <Button variant="ghost" size="sm" onClick={() => refetch()}>
                      Reintentar
                    </Button>
                  </div>
                </Alert>
              </div>
            ) : !data?.length ? (
              renderPanelState(
                isClient ? 'Aún no tienes informes disponibles' : EMPTY_ACTIVITY_TEXT,
                isClient
                  ? 'Tu consultora los publicará aquí cuando el cierre del periodo quede listo para revisar o descargar.'
                  : 'Genera el primer entregable del periodo para dejar trazabilidad de la lectura y compartirla con el cliente final.',
                'default',
                'mt-12'
              )
            ) : (
              <>
                {!isClient ? (
                  <div className="upload-hint row row-center row-wrap gap-10 mb-10">
                    <label className="row row-center gap-8">
                      <input type="checkbox" checked={showAllVersions} onChange={(event) => setShowAllVersions(event.target.checked)} />
                      Mostrar historial completo
                    </label>
                    <span>Por defecto se muestra solo la última versión de cada periodo.</span>
                  </div>
                ) : null}

                <table className="table">
                  <thead>
                    <tr>
                      <th>Periodo</th>
                      <th>Generado</th>
                      <th>Estado</th>
                      <th>Acciones</th>
                    </tr>
                  </thead>
                  <tbody>
                    {reportsForUi.map((report: ReportDto) => (
                      <tr key={report.id}>
                        <td>
                          <div className="fw-700">{report.period}</div>
                          {!isClient ? <div className="upload-hint">ID: {report.id} · v{report.versionNo || 1}</div> : null}
                          {!isClient && report.selectedUniversalViewName ? (
                            <div className="upload-hint">
                              Universal: {report.selectedUniversalViewName}
                              {report.selectedUniversalAggregationMode ? ` · ${universalAggregationModeLabel(report.selectedUniversalAggregationMode)}` : ''}
                            </div>
                          ) : null}
                        </td>
                        <td className="upload-hint">{report.createdAt ? formatDateTime(report.createdAt) : EMPTY_VALUE}</td>
                        <td>{report.status}</td>
                        <td>
                          <div className="row row-wrap gap-8">
                            <Button variant="secondary" size="sm" onClick={() => handleView(report.id)}>
                              Vista previa
                            </Button>
                            <Button variant="ghost" size="sm" onClick={() => handleDownloadPdf(report.id, report.period)}>
                              Descargar PDF
                            </Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </div>
        </div>
      </details>

      {html ? (
        <details className="reports-preview-details" open>
          <summary>
            <div>
              <div className="fw-700">Vista previa del entregable</div>
              <div className="upload-hint">Abre, descarga u oculta la revisión del PDF sin más ruido alrededor.</div>
            </div>
            <div className="row row-center row-wrap gap-8">
              <span className="badge">{previewReport?.period || period}</span>
              <span className="badge">HTML</span>
            </div>
          </summary>
          <div className="reports-preview-body">
            <div className="card reports-preview-shell">
              <div className="mini-row row-baseline mb-10 reports-preview-toolbar">
                <div>
                  <div className="upload-hint">Previsualización lista para revisar antes de compartir.</div>
                  {previewReport?.selectedUniversalViewName ? (
                    <div className="upload-hint mt-8">
                      Universal: {previewReport.selectedUniversalViewName}
                      {previewReport.selectedUniversalAggregationMode ? ` · ${universalAggregationModeLabel(previewReport.selectedUniversalAggregationMode)}` : ''}
                    </div>
                  ) : null}
                </div>
                <div className="row row-wrap gap-8">
                  {previewReport ? (
                    <Button variant="ghost" size="sm" onClick={() => handleDownloadPdf(previewReport.id, previewReport.period)}>
                      Descargar PDF
                    </Button>
                  ) : null}
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      setHtml('')
                      setPreviewReport(null)
                    }}
                  >
                    Ocultar
                  </Button>
                </div>
              </div>
              <div className="report-frame-shell">
                <iframe className="report-frame" title="Reporte" sandbox="" referrerPolicy="no-referrer" srcDoc={html} />
              </div>
            </div>
          </div>
        </details>
      ) : null}
    </div>
  )
}
