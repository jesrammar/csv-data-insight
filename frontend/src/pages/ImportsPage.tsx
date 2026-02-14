import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getImports, uploadImport } from '../api'
import { useState } from 'react'

function getSelectedCompanyId(): number | null {
  const value = localStorage.getItem('companyId')
  return value ? Number(value) : null
}

export default function ImportsPage() {
  const companyId = getSelectedCompanyId()
  const queryClient = useQueryClient()
  const { data } = useQuery({
    queryKey: ['imports', companyId],
    queryFn: () => getImports(companyId as number),
    enabled: !!companyId
  })
  const [period, setPeriod] = useState('2025-06')
  const [file, setFile] = useState<File | null>(null)
  const [message, setMessage] = useState('')

  async function handleUpload() {
    if (!companyId || !file) return
    setMessage('')
    try {
      await uploadImport(companyId, period, file)
      await queryClient.invalidateQueries({ queryKey: ['imports', companyId] })
      setMessage('Import encolado, será procesado por el scheduler.')
    } catch (err: any) {
      setMessage(err.message)
    }
  }

  return (
    <div>
      <div className="hero">
        <div>
          <h1 className="hero-title">Importación de CSV</h1>
          <p className="hero-sub">Sube movimientos por periodo y deja que EnterpriseIQ valide y normalice.</p>
        </div>
        <div className="card soft">
          <h3 style={{ marginTop: 0 }}>Formato esperado</h3>
          <p className="hero-sub">
            Columnas: <strong>txn_date</strong>, <strong>amount</strong>, opcional <strong>description</strong>,{' '}
            <strong>counterparty</strong>, <strong>balance_end</strong>.
          </p>
        </div>
      </div>

      <div className="card section">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center' }}>
          <input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="YYYY-MM" />
          <input type="file" accept=".csv" onChange={(e) => setFile(e.target.files?.[0] || null)} />
          <button onClick={handleUpload}>Subir CSV</button>
        </div>
        {message && <p>{message}</p>}
      </div>

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Historial de imports</h3>
        {!data?.length ? (
          <div className="empty">No hay imports todavía.</div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Periodo</th>
                <th>Status</th>
                <th>Warnings</th>
                <th>Errors</th>
                <th>Resumen</th>
              </tr>
            </thead>
            <tbody>
              {(data || []).map((imp: any) => (
                <tr key={imp.id}>
                  <td>{imp.id}</td>
                  <td>{imp.period}</td>
                  <td>
                    <span
                      className={`badge ${
                        imp.status === 'OK'
                          ? 'ok'
                          : imp.status === 'WARNING'
                          ? 'warn'
                          : imp.status === 'ERROR'
                          ? 'err'
                          : ''
                      }`}
                    >
                      {imp.status}
                    </span>
                  </td>
                  <td>{imp.warningCount ?? 0}</td>
                  <td>{imp.errorCount ?? 0}</td>
                  <td>{imp.errorSummary}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
