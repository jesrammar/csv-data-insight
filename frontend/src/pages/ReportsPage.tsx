import { useQuery, useQueryClient } from '@tanstack/react-query'
import { generateReport, getReportContent, getReports } from '../api'
import { useState } from 'react'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'

export default function ReportsPage() {
  const { id: companyId, plan } = useCompanySelection()
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

  async function handleGenerate() {
    if (!companyId) return
    setError('')
    setSuccess('')
    try {
      await generateReport(companyId, period)
      await queryClient.invalidateQueries({ queryKey: ['reports', companyId] })
      setSuccess('Reporte generado. Puedes abrirlo o exportarlo desde la lista.')
      toast.push({ tone: 'success', title: 'Reporte', message: `Generado para ${period}.` })
    } catch (err: any) {
      setError(err.message)
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'No se pudo generar el reporte.' })
    }
  }

  async function handleView(reportId: number) {
    if (!companyId) return
    const content = await getReportContent(companyId, reportId)
    setHtml(content)
  }

  return (
    <div>
      <PageHeader
        title="Reportes mensuales"
        subtitle="Genera informes HTML listos para compartir o exportar a PDF."
        actions={<span className="badge">{(plan || 'BRONZE').toUpperCase()}</span>}
      />

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

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Historial</h3>
        {!data?.length ? (
          <div className="empty">No hay reportes todavía.</div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Periodo</th>
                <th>Formato</th>
                <th>Status</th>
                <th>Acciones</th>
              </tr>
            </thead>
            <tbody>
              {(data || []).map((rep: any) => (
                <tr key={rep.id}>
                  <td>{rep.id}</td>
                  <td>{rep.period}</td>
                  <td>{rep.format}</td>
                  <td>{rep.status}</td>
                  <td>
                    <Button variant="secondary" size="sm" onClick={() => handleView(rep.id)}>
                      Ver HTML
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {html && (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>Vista HTML</h3>
          <div className="upload-hint" style={{ marginBottom: 10 }}>
            Tip: si el HTML incluye tablas largas, exporta desde el navegador a PDF para el cliente.
          </div>
          <div dangerouslySetInnerHTML={{ __html: html }} />
        </div>
      )}
    </div>
  )
}
