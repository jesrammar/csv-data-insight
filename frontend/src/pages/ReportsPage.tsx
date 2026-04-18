import { useQuery, useQueryClient } from '@tanstack/react-query'
import { downloadReportPdf, generateReport, getReportContent, getReports, getUserRole } from '../api'
import { useEffect, useState } from 'react'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'
import { getWorkPeriod, nowYm } from '../utils/workPeriod'

export default function ReportsPage() {
  const { id: companyId, plan } = useCompanySelection()
  const isClient = getUserRole() === 'CLIENTE'
  const queryClient = useQueryClient()
  const toast = useToast()
  const { data, isLoading, error: reportsError, refetch } = useQuery({
    queryKey: ['reports', companyId],
    queryFn: () => getReports(companyId as number),
    enabled: !!companyId
  })

  const [period, setPeriod] = useState(() => getWorkPeriod(companyId) || nowYm())
  const [html, setHtml] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [showAllVersions, setShowAllVersions] = useState(false)
  const [previewReport, setPreviewReport] = useState<{ id: number; period?: string } | null>(null)

  const reportsForUi = (() => {
    const list = (data || []) as any[]
    if (showAllVersions) return list
    const seen = new Set<string>()
    const out: any[] = []
    for (const r of list) {
      const key = String(r?.period || '')
      if (!key) continue
      if (seen.has(key)) continue
      seen.add(key)
      out.push(r)
    }
    return out
  })()
  const latestReport = reportsForUi[0] || null
  const reportsCount = Array.isArray(data) ? data.length : 0

  useEffect(() => {
    if (!companyId) return
    setPeriod(getWorkPeriod(companyId) || nowYm())
  }, [companyId])

  function renderPanelState(title: string, detail?: string, tone: 'default' | 'loading' | 'locked' = 'default', className = 'mt-3') {
    return (
      <div className={`panel-state panel-state-${tone} ${className}`.trim()}>
        <div className="panel-state-title">{title}</div>
        {detail ? <div className="panel-state-detail">{detail}</div> : null}
      </div>
    )
  }

  function periodLabelForReport(reportId: number) {
    const rep = reportsForUi.find((item: any) => item.id === reportId)
    return rep?.period
  }

  async function handleGenerate() {
    if (!companyId) return
    setError('')
    setSuccess('')
    try {
      await generateReport(companyId, period)
      await queryClient.invalidateQueries({ queryKey: ['reports', companyId] })

      try {
        const list = await getReports(companyId)
        const rep = (list || []).find((r: any) => String(r?.period || '') === period) || (list || [])[0]
        if (rep?.id) {
          const content = await getReportContent(companyId, rep.id)
          setHtml(content)
          setPreviewReport({ id: rep.id, period: rep.period })
        }
      } catch {
        // ignore preview refresh errors after successful generation
      }

      setSuccess('Informe generado. Ya puedes revisarlo en pantalla o descargarlo en PDF.')
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
      setPreviewReport({ id: reportId, period: periodLabelForReport(reportId) })
    } catch (err: any) {
      const msg = String(err?.message || err || 'No se pudo abrir el informe.')
      setError(
        msg.toLowerCase().includes('retención') || msg.toLowerCase().includes('no está disponible')
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
      const a = document.createElement('a')
      a.href = url
      a.download = `enterpriseiq-report-${periodLabel || reportId}.pdf`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      toast.push({ tone: 'success', title: 'PDF', message: 'Descarga iniciada.' })
    } catch (err: any) {
      const msg = String(err?.message || err || 'No se pudo descargar el PDF.')
      setError(
        msg.toLowerCase().includes('retención') || msg.toLowerCase().includes('no está disponible')
          ? 'Este informe fue limpiado por la retención de ficheros. Genera uno nuevo para ese periodo.'
          : msg
      )
      toast.push({ tone: 'danger', title: 'Error', message: 'No se pudo descargar el PDF.' })
    }
  }

  return (
    <div>
      <PageHeader
        title={isClient ? 'Informes' : 'Entregables mensuales'}
        subtitle={
          isClient
            ? 'Entregables listos para revisar o descargar.'
            : 'Genera informes listos para compartir con cliente o exportar a PDF.'
        }
        actions={<span className="badge">{(plan || 'BRONZE').toUpperCase()}</span>}
      />

      <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">Ruta recomendada</h3>
          <span className="upload-hint">Genera, revisa y comparte siguiendo este orden.</span>
        </div>
        <div className="grid grid-autofit-220 mt-12">
          <div className="card soft card-pad-sm">
            <div className="upload-hint">{isClient ? '1. Espera el informe' : '1. Genera'}</div>
            <div className="fw-800 mt-1">{isClient ? 'Tu consultora lo prepara' : companyId ? `Periodo ${period}` : 'Selecciona empresa'}</div>
            <div className="upload-hint mt-1">
              {isClient
                ? 'Cuando esté listo aparecerá en el historial para abrirlo.'
                : companyId
                  ? 'Genera el entregable del periodo de trabajo con un clic.'
                  : 'Activa primero una empresa para empezar el flujo.'}
            </div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">2. Revisa</div>
            <div className="fw-800 mt-1">{previewReport?.period || latestReport?.period || 'Sin vista previa'}</div>
            <div className="upload-hint mt-1">
              {html
                ? 'La vista HTML ya está abierta abajo para validar el contenido.'
                : latestReport
                  ? 'Abre el último informe para comprobarlo antes de compartirlo.'
                  : 'Cuando exista un informe, podrás abrirlo desde el historial.'}
            </div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">3. Comparte</div>
            <div className="fw-800 mt-1">{reportsCount} {reportsCount === 1 ? 'informe' : 'informes'}</div>
            <div className="upload-hint mt-1">
              {previewReport
                ? 'Si todo está correcto, descarga el PDF y compártelo con el cliente.'
                : 'El cierre natural del flujo es descargar el PDF definitivo.'}
            </div>
            {previewReport ? (
              <div className="mt-2">
                <Button variant="ghost" size="sm" onClick={() => handleDownloadPdf(previewReport.id, previewReport.period)}>
                  Descargar PDF
                </Button>
              </div>
            ) : null}
          </div>
        </div>
      </div>

      {!isClient ? (
        <div className="section">
          <div className="mini-row row-baseline mb-12">
            <h3 className="m-0">Genera</h3>
            <span className="upload-hint">Crea el entregable del periodo de trabajo.</span>
          </div>
          <div className="card">
            <h3 className="h3-reset">Generación</h3>
            {!companyId ? renderPanelState('Falta seleccionar empresa', 'Elige una empresa arriba para generar y revisar informes.') : null}
            <div className="upload-row">
              <input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="YYYY-MM" inputMode="numeric" />
              <Button onClick={handleGenerate} disabled={!companyId}>
                Generar informe
              </Button>
            </div>
            {error ? (
              <div className="mt-12">
                <Alert tone="danger">{error}</Alert>
              </div>
            ) : null}
            {success ? (
              <div className="mt-12">
                <Alert tone="success">{success}</Alert>
              </div>
            ) : null}
          </div>
        </div>
      ) : (
        <div className="section">
          <div className="mini-row row-baseline mb-12">
            <h3 className="m-0">Revisa</h3>
            <span className="upload-hint">Tu consultora publica aquí los informes listos para cliente.</span>
          </div>
          <div className="card">
            <Alert tone="info" title="Solo lectura">
              Tu consultora prepara los informes. Aquí puedes revisarlos cuando estén listos.
            </Alert>
            <div className="mt-12">
              <Button variant="ghost" size="sm" onClick={() => queryClient.invalidateQueries({ queryKey: ['reports', companyId] })}>
                Refrescar
              </Button>
            </div>
          </div>
        </div>
      )}

      <div className="section">
        <div className="mini-row row-baseline mb-12">
          <h3 className="m-0">Revisa y comparte</h3>
          <span className="upload-hint">Abre la vista previa y descarga el PDF definitivo desde el historial.</span>
        </div>
        <div className="card">
          <h3 className="h3-reset">{isClient ? 'Disponibles' : 'Historial'}</h3>
          {!companyId ? (
            renderPanelState('Sin empresa seleccionada', 'Selecciona una empresa para ver su historial de informes.', 'default', 'mt-12')
          ) : isLoading ? (
            renderPanelState('Cargando informes', 'Estoy recuperando el historial para este cliente.', 'loading', 'mt-12')
          ) : reportsError ? (
            <div className="mt-12">
              <Alert tone="danger" title="No se pudo cargar el historial">
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
              isClient ? 'Aún no tienes informes disponibles' : 'Todavía no hay informes generados',
              isClient
                ? 'Tu consultora los verá aquí cuando estén listos para revisar o descargar.'
                : 'Genera el primer informe del periodo para empezar a compartir entregables.',
              'default',
              'mt-12'
            )
          ) : (
            <>
              {!isClient ? (
                <div className="upload-hint row row-center row-wrap gap-10 mb-10">
                  <label className="row row-center gap-8">
                    <input type="checkbox" checked={showAllVersions} onChange={(e) => setShowAllVersions(e.target.checked)} />
                    Mostrar versiones antiguas
                  </label>
                  <span>Por defecto se muestra solo el último informe de cada periodo.</span>
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
                  {reportsForUi.map((rep: any) => (
                    <tr key={rep.id}>
                      <td>
                        <div className="fw-700">{rep.period}</div>
                        {!isClient ? <div className="upload-hint">ID: {rep.id}</div> : null}
                      </td>
                      <td className="upload-hint">{rep.createdAt ? new Date(rep.createdAt).toLocaleString() : '-'}</td>
                      <td>{rep.status}</td>
                      <td>
                        <div className="row row-wrap gap-8">
                          <Button variant="secondary" size="sm" onClick={() => handleView(rep.id)}>
                            Vista previa
                          </Button>
                          <Button variant="ghost" size="sm" onClick={() => handleDownloadPdf(rep.id, rep.period)}>
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

      {html ? (
        <div className="section">
          <div className="mini-row row-baseline mb-12">
            <h3 className="m-0">Vista previa</h3>
            <span className="upload-hint">Última revisión antes de compartir el PDF con cliente.</span>
          </div>
          <div className="card">
            <div className="mini-row row-baseline mb-10">
              <h3 className="h3-reset m-0">Vista HTML</h3>
              {previewReport ? (
                <Button variant="ghost" size="sm" onClick={() => handleDownloadPdf(previewReport.id, previewReport.period)}>
                  Descargar PDF
                </Button>
              ) : null}
            </div>
            <div className="upload-hint mb-10">
              Esto es una vista previa del informe. Revisa contenido, formato y mensajes clave antes de compartirlo.
            </div>
            <iframe
              className="report-frame"
              title="Reporte"
              sandbox=""
              referrerPolicy="no-referrer"
              srcDoc={html}
            />
          </div>
        </div>
      ) : null}
    </div>
  )
}
