import { useQuery, useQueryClient } from '@tanstack/react-query'
import { generateReport, getReportContent, getReports } from '../api'
import { useState } from 'react'

function getSelectedCompanyId(): number | null {
  const value = localStorage.getItem('companyId')
  return value ? Number(value) : null
}

export default function ReportsPage() {
  const companyId = getSelectedCompanyId()
  const queryClient = useQueryClient()
  const { data } = useQuery({
    queryKey: ['reports', companyId],
    queryFn: () => getReports(companyId as number),
    enabled: !!companyId
  })

  const [period, setPeriod] = useState('2025-06')
  const [html, setHtml] = useState('')
  const [error, setError] = useState('')

  async function handleGenerate() {
    if (!companyId) return
    setError('')
    try {
      await generateReport(companyId, period)
      await queryClient.invalidateQueries({ queryKey: ['reports', companyId] })
    } catch (err: any) {
      setError(err.message)
    }
  }

  async function handleView(reportId: number) {
    if (!companyId) return
    const content = await getReportContent(companyId, reportId)
    setHtml(content)
  }

  return (
    <div>
      <div className="hero">
        <div>
          <h1 className="hero-title">Reportes mensuales</h1>
          <p className="hero-sub">Genera informes en HTML listos para exportar a PDF.</p>
        </div>
        <div className="card soft">
          <h3 style={{ marginTop: 0 }}>Estado del mes</h3>
          <p className="hero-sub">Configura el periodo y dispara la generación.</p>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            <input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="YYYY-MM" />
            <button onClick={handleGenerate}>Generar reporte</button>
          </div>
          {error && <p className="error">{error}</p>}
        </div>
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
                    <button className="secondary" onClick={() => handleView(rep.id)}>
                      Ver HTML
                    </button>
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
          <div dangerouslySetInnerHTML={{ __html: html }} />
        </div>
      )}
    </div>
  )
}
