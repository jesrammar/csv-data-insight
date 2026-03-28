import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getImports, retryImport, type ImportJob, uploadImport } from '../api'
import { useState } from 'react'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'

export default function ImportsPage() {
  const { id: companyId } = useCompanySelection()
  const queryClient = useQueryClient()
  const toast = useToast()
  const { data } = useQuery({
    queryKey: ['imports', companyId],
    queryFn: () => getImports(companyId as number),
    enabled: !!companyId
  })
  const [period, setPeriod] = useState('2025-06')
  const [file, setFile] = useState<File | null>(null)
  const [message, setMessage] = useState('')
  const [tone, setTone] = useState<'info' | 'success' | 'danger'>('info')

  async function handleUpload() {
    if (!companyId || !file) return
    setMessage('')
    try {
      await uploadImport(companyId, period, file)
      await queryClient.invalidateQueries({ queryKey: ['imports', companyId] })
      setTone('success')
      setMessage('Import encolado. Se procesará automáticamente y recalculará KPIs/alertas.')
      toast.push({ tone: 'success', title: 'Import', message: 'CSV subido y encolado para procesado.' })
    } catch (err: any) {
      setTone('danger')
      setMessage(err.message)
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'No se pudo subir el CSV.' })
    }
  }

  async function handleRetry(importId: number) {
    if (!companyId) return
    try {
      await retryImport(companyId, importId)
      await queryClient.invalidateQueries({ queryKey: ['imports', companyId] })
      toast.push({ tone: 'success', title: 'Reintento', message: `Import ${importId} reencolado.` })
    } catch (err: any) {
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'No se pudo reencolar el import.' })
    }
  }

  return (
    <div>
      <PageHeader
        title="Importación de transacciones"
        subtitle="Sube movimientos por periodo. EnterpriseIQ valida, normaliza y recalcula KPIs."
        actions={
          <span className="badge">CSV/XLSX • txn_date + amount</span>
        }
      />

      <div className="grid section">
        <div className="card soft">
          <h3 style={{ marginTop: 0 }}>Formato esperado</h3>
          <p className="hero-sub">
            Columnas: <strong>txn_date</strong>, <strong>amount</strong>. Opcionales: <strong>description</strong>,{' '}
            <strong>counterparty</strong>, <strong>balance_end</strong>.
          </p>
        </div>
        <div className="card soft">
          <h3 style={{ marginTop: 0 }}>Buenas prácticas</h3>
          <p className="hero-sub">
            Un periodo por fichero (YYYY-MM). Mantén fechas ISO (YYYY-MM-DD) para evitar filas descartadas.
          </p>
        </div>
      </div>

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Subida</h3>
        {!companyId ? (
          <Alert tone="warning" title="Falta seleccionar empresa">
            Selecciona una empresa arriba para subir el fichero.
          </Alert>
        ) : null}
        <div className="upload-row">
          <input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="YYYY-MM" inputMode="numeric" />
          <input type="file" accept=".csv,.xlsx" onChange={(e) => setFile(e.target.files?.[0] || null)} />
          <Button onClick={handleUpload} disabled={!companyId || !file}>
            Subir fichero
          </Button>
        </div>
        {message ? (
          <div style={{ marginTop: 12 }}>
            <Alert tone={tone}>{message}</Alert>
          </div>
        ) : null}
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
                <th>Intentos</th>
                <th>Warnings</th>
                <th>Errors</th>
                <th>Resumen</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {(data || []).map((imp: ImportJob) => (
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
                          : imp.status === 'ERROR' || imp.status === 'DEAD'
                          ? 'err'
                          : ''
                      }`}
                    >
                      {imp.status}
                    </span>
                  </td>
                  <td>
                    {typeof imp.attempts === 'number' || typeof imp.maxAttempts === 'number'
                      ? `${imp.attempts ?? 0}/${imp.maxAttempts ?? 3}`
                      : '-'}
                  </td>
                  <td>{imp.warningCount ?? 0}</td>
                  <td>{imp.errorCount ?? 0}</td>
                  <td>{imp.errorSummary || imp.lastError}</td>
                  <td style={{ textAlign: 'right' }}>
                    {imp.status === 'ERROR' || imp.status === 'DEAD' ? (
                      <Button size="sm" onClick={() => handleRetry(imp.id)}>
                        Reintentar
                      </Button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
