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
      <h2>Imports</h2>
      <div className="card" style={{ marginBottom: 16 }}>
        <input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="YYYY-MM" />
        <input type="file" accept=".csv" onChange={(e) => setFile(e.target.files?.[0] || null)} />
        <button onClick={handleUpload}>Subir CSV</button>
        {message && <p>{message}</p>}
      </div>
      <div className="card">
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
                <td><span className="badge">{imp.status}</span></td>
                <td>{imp.warningCount ?? 0}</td>
                <td>{imp.errorCount ?? 0}</td>
                <td>{imp.errorSummary}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
