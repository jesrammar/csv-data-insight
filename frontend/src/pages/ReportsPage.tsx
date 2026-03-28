import { useQuery, useQueryClient } from '@tanstack/react-query'
import { generateReport, getReportContent, getReports, getUserRole } from '../api'
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
          <table className="table">
            <thead>
              <tr>
                <th>Periodo</th>
                {!isClient ? <th>ID</th> : null}
                {!isClient ? <th>Formato</th> : null}
                <th>Estado</th>
                <th>Acciones</th>
              </tr>
            </thead>
            <tbody>
              {(data || []).map((rep: any) => (
                <tr key={rep.id}>
                  <td>{rep.period}</td>
                  {!isClient ? <td>{rep.id}</td> : null}
                  {!isClient ? <td>{rep.format}</td> : null}
                  <td>{rep.status}</td>
                  <td>
                    <Button variant="secondary" size="sm" onClick={() => handleView(rep.id)}>
                      Abrir
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
