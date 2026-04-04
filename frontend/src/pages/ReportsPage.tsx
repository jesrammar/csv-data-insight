import { useQuery, useQueryClient } from '@tanstack/react-query'
import { downloadReportPdf, generateReport, getReportContent, getReports, getUserRole } from '../api'
import { useState } from 'react'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'

export default function ReportsPage() {
  const { id: companyId, plan } = useCompanySelection()
  const isClient = getUserRole() === 'CLIENTE'
  const queryClient = useQueryClient()
  const toast = useToast()
  const { data } = useQuery({
    queryKey: ['reports', companyId],
    queryFn: () => getReports(companyId as number),
    enabled: !!companyId
  })

  const [period, setPeriod] = useState('2025-06')
  const [html, setHtml] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [showAllVersions, setShowAllVersions] = useState(false)

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

  async function handleGenerate() {
    if (!companyId) return
    setError('')
    setSuccess('')
    try {
      await generateReport(companyId, period)
      await queryClient.invalidateQueries({ queryKey: ['reports', companyId] })

      // Auto-abrir el último informe del periodo para que se vea el resultado al instante.
      try {
        const list = await getReports(companyId)
        const rep = (list || []).find((r: any) => String(r?.period || '') === period) || (list || [])[0]
        if (rep?.id) {
          const content = await getReportContent(companyId, rep.id)
          setHtml(content)
        }
      } catch {}

      setSuccess('Reporte generado. Puedes previsualizarlo o descargarlo como PDF.')
      toast.push({ tone: 'success', title: 'Reporte', message: `Generado para ${period}.` })
    } catch (err: any) {
      setError(err.message)
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'No se pudo generar el reporte.' })
    }
  }

  async function handleView(reportId: number) {
    if (!companyId) return
    try {
      const content = await getReportContent(companyId, reportId)
      setHtml(content)
    } catch (err: any) {
      const msg = String(err?.message || err || 'No se pudo abrir el informe.')
      setError(
        msg.toLowerCase().includes('retención') || msg.toLowerCase().includes('no está disponible')
          ? 'Este informe fue limpiado por retención de ficheros. Genera de nuevo el reporte para ese periodo.'
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
          ? 'Este informe fue limpiado por retención de ficheros. Genera de nuevo el reporte para ese periodo.'
          : msg
      )
      toast.push({ tone: 'danger', title: 'Error', message: 'No se pudo descargar el PDF.' })
    }
  }

  return (
    <div>
      <PageHeader
        title={isClient ? 'Informes' : 'Reportes mensuales'}
        subtitle={
          isClient
            ? 'Entregables listos para revisar o descargar.'
            : 'Genera informes HTML listos para compartir o exportar a PDF.'
        }
        actions={<span className="badge">{(plan || 'BRONZE').toUpperCase()}</span>}
      />

      {!isClient ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>Generación</h3>
          {!companyId ? (
            <Alert tone="warning" title="Falta seleccionar empresa">
              Selecciona una empresa arriba para generar reportes.
            </Alert>
          ) : null}
          <div className="upload-row">
            <input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="YYYY-MM" inputMode="numeric" />
            <Button onClick={handleGenerate} disabled={!companyId}>
              Generar reporte
            </Button>
          </div>
          {error ? (
            <div style={{ marginTop: 12 }}>
              <Alert tone="danger">{error}</Alert>
            </div>
          ) : null}
          {success ? (
            <div style={{ marginTop: 12 }}>
              <Alert tone="success">{success}</Alert>
            </div>
          ) : null}
        </div>
      ) : (
        <div className="card section">
          <Alert tone="info" title="Solo lectura">
            Tu consultora prepara los informes. Aquí puedes revisarlos cuando estén listos.
          </Alert>
          <div style={{ marginTop: 12 }}>
            <Button variant="ghost" size="sm" onClick={() => queryClient.invalidateQueries({ queryKey: ['reports', companyId] })}>
              Refrescar
            </Button>
          </div>
        </div>
      )}

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>{isClient ? 'Disponibles' : 'Historial'}</h3>
        {!data?.length ? (
          <div className="empty">No hay reportes todavía.</div>
        ) : (
          <>
            {!isClient ? (
              <div
                className="upload-hint"
                style={{ display: 'flex', gap: 10, alignItems: 'center', marginBottom: 10, flexWrap: 'wrap' }}
              >
                <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
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
                      <div style={{ fontWeight: 700 }}>{rep.period}</div>
                      {!isClient ? <div className="upload-hint">ID: {rep.id}</div> : null}
                    </td>
                    <td className="upload-hint">{rep.createdAt ? new Date(rep.createdAt).toLocaleString() : '-'}</td>
                    <td>{rep.status}</td>
                    <td>
                      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
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

      {html && (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>Vista HTML</h3>
          <div className="upload-hint" style={{ marginBottom: 10 }}>
            Esto es una vista previa del informe. Para enviarlo al cliente usa “Descargar PDF”.
          </div>
          <iframe
            className="report-frame"
            title="Reporte"
            sandbox=""
            referrerPolicy="no-referrer"
            srcDoc={html}
          />
        </div>
      )}
    </div>
  )
}
