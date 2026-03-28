import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getImports, previewUniversalXlsx, retryImport, type ImportJob, uploadImport, uploadUniversalImport, type UniversalXlsxPreview } from '../api'
import { useEffect, useMemo, useState } from 'react'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'
import { useNavigate } from 'react-router-dom'

export default function ImportsPage() {
  const { id: companyId, plan } = useCompanySelection()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()
  const { data } = useQuery({
    queryKey: ['imports', companyId],
    queryFn: () => getImports(companyId as number),
    enabled: !!companyId
  })
  const [mode, setMode] = useState<'transactions' | 'universal'>('transactions')
  const hasPlatinum = plan === 'PLATINUM'

  const [period, setPeriod] = useState('2025-06')
  const [file, setFile] = useState<File | null>(null)
  const [message, setMessage] = useState('')
  const [tone, setTone] = useState<'info' | 'success' | 'danger'>('info')

  const [uploading, setUploading] = useState(false)
  const [xlsxPreview, setXlsxPreview] = useState<UniversalXlsxPreview | null>(null)
  const [xlsxLoading, setXlsxLoading] = useState(false)
  const [sheetIndex, setSheetIndex] = useState<number | null>(null)
  const [headerRow, setHeaderRow] = useState<number | null>(null)

  const isCsv = !file ? true : file.name.toLowerCase().endsWith('.csv')
  const isXlsx = !file ? false : file.name.toLowerCase().endsWith('.xlsx')
  const isAllowed = !file ? true : isCsv || isXlsx
  const canPreviewXlsx = !!companyId && hasPlatinum && mode === 'universal' && isXlsx && !!file

  const previewOpts = useMemo(() => {
    return {
      sheetIndex: sheetIndex ?? undefined,
      headerRow: headerRow ?? undefined
    }
  }, [sheetIndex, headerRow])

  useEffect(() => {
    setXlsxPreview(null)
    setSheetIndex(null)
    setHeaderRow(null)
    setXlsxLoading(false)
  }, [file?.name])

  useEffect(() => {
    if (!canPreviewXlsx || !file) return
    let cancelled = false
    const t = window.setTimeout(async () => {
      setXlsxLoading(true)
      try {
        const prev = await previewUniversalXlsx(companyId as number, file, previewOpts)
        if (cancelled) return
        setXlsxPreview(prev)
        if (sheetIndex == null && prev.sheetIndex != null) setSheetIndex(prev.sheetIndex)
        if (headerRow == null && prev.headerRow != null) setHeaderRow(prev.headerRow)
      } catch (e: any) {
        if (!cancelled) {
          setTone('danger')
          setMessage(e?.message || 'No se pudo previsualizar el XLSX.')
        }
      } finally {
        if (!cancelled) setXlsxLoading(false)
      }
    }, 250)
    return () => {
      cancelled = true
      window.clearTimeout(t)
    }
  }, [canPreviewXlsx, file, companyId, previewOpts, sheetIndex, headerRow])

  async function handleUpload() {
    if (!companyId || !file) return
    setMessage('')
    try {
      setUploading(true)
      if (!isAllowed) {
        setTone('danger')
        setMessage('Formato no soportado. Sube un CSV o XLSX.')
        return
      }

      if (mode === 'universal') {
        const opts =
          file.name.toLowerCase().endsWith('.xlsx') && hasPlatinum
            ? { sheetIndex: sheetIndex ?? xlsxPreview?.sheetIndex ?? undefined, headerRow: headerRow ?? xlsxPreview?.headerRow ?? undefined }
            : {}
        await uploadUniversalImport(companyId, file, opts)
        setTone('success')
        setMessage('Archivo analizado en Universal. Ya puedes ver columnas, insights y el asesor.')
        toast.push({ tone: 'success', title: 'Universal', message: 'Archivo analizado correctamente.' })
        navigate('/universal')
        return
      }

      await uploadImport(companyId, period, file)
      await queryClient.invalidateQueries({ queryKey: ['imports', companyId] })
      setTone('success')
      setMessage('Import encolado. Se procesará automáticamente y recalculará KPIs/alertas.')
      toast.push({ tone: 'success', title: 'Import', message: 'CSV subido y encolado para procesado.' })
    } catch (err: any) {
      setTone('danger')
      const msg = err?.message || 'No se pudo subir el fichero.'
      setMessage(msg)
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'No se pudo subir el CSV.' })
      if (mode === 'transactions' && String(msg).toLowerCase().includes('formato incorrecto')) {
        toast.push({ tone: 'info', title: 'Tip', message: 'Si no es un fichero de transacciones, usa el modo Universal.' })
      }
    } finally {
      setUploading(false)
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
        title="Cargar datos"
        subtitle="Dos modos: Caja (transacciones por periodo) o Universal (cualquier CSV/XLSX para análisis)."
        actions={
          <span className="badge">{mode === 'transactions' ? 'Caja • txn_date + amount' : 'Universal • cualquier estructura'}</span>
        }
      />

      <div className="section">
        <div className="segmented" role="tablist" aria-label="Modo de carga">
          <Button
            type="button"
            variant={mode === 'transactions' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setMode('transactions')}
            aria-selected={mode === 'transactions'}
          >
            Caja (transacciones)
          </Button>
          <Button
            type="button"
            variant={mode === 'universal' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setMode('universal')}
            aria-selected={mode === 'universal'}
          >
            Universal (cualquier CSV/XLSX)
          </Button>
        </div>

        {mode === 'transactions' ? (
          <div className="grid" style={{ marginTop: 14 }}>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Formato (Caja)</h3>
              <p className="hero-sub">
                Columnas: <strong>txn_date</strong>, <strong>amount</strong>. Opcionales: <strong>description</strong>,{' '}
                <strong>counterparty</strong>, <strong>balance_end</strong>.
              </p>
            </div>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Buenas prácticas</h3>
              <p className="hero-sub">Un periodo por fichero (YYYY-MM). Fechas ISO (YYYY-MM-DD) para evitar filas descartadas.</p>
            </div>
          </div>
        ) : (
          <div className="grid" style={{ marginTop: 14 }}>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Formato (Universal)</h3>
              <p className="hero-sub">
                Sube cualquier CSV/XLSX (presupuestos, salarios, inventario, ventas…). No requiere columnas fijas: EnterpriseIQ detecta tipos y genera
                resumen/insights.
              </p>
            </div>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>XLSX (PLATINUM)</h3>
              <p className="hero-sub">
                Si el encabezado no está en la primera fila o hay varias hojas, puedes ajustar hoja/encabezado (se previsualiza automáticamente).
              </p>
            </div>
          </div>
        )}
      </div>

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Subida</h3>
        {!companyId ? (
          <Alert tone="warning" title="Falta seleccionar empresa">
            Selecciona una empresa arriba para subir el fichero.
          </Alert>
        ) : null}
        <div className="upload-row">
          {mode === 'transactions' ? (
            <input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="YYYY-MM" inputMode="numeric" />
          ) : null}
          <input type="file" accept=".csv,.xlsx" onChange={(e) => setFile(e.target.files?.[0] || null)} />
          {mode === 'universal' && hasPlatinum && isXlsx ? (
            <>
              <input
                value={sheetIndex ?? ''}
                onChange={(e) => setSheetIndex(e.target.value === '' ? null : Number(e.target.value))}
                placeholder="sheetIndex"
                inputMode="numeric"
                style={{ width: 120 }}
              />
              <input
                value={headerRow ?? ''}
                onChange={(e) => setHeaderRow(e.target.value === '' ? null : Number(e.target.value))}
                placeholder="headerRow"
                inputMode="numeric"
                style={{ width: 120 }}
              />
              {xlsxLoading ? <span className="upload-hint">Previsualizando…</span> : null}
              {xlsxPreview?.headers?.length ? <span className="upload-hint">Headers: {xlsxPreview.headers.length}</span> : null}
            </>
          ) : null}
          <Button onClick={handleUpload} disabled={!companyId || !file || uploading} loading={uploading}>
            Subir fichero
          </Button>
        </div>
        {message ? (
          <div style={{ marginTop: 12 }}>
            <Alert tone={tone}>{message}</Alert>
          </div>
        ) : null}
        {mode === 'transactions' ? (
          <div className="upload-hint" style={{ marginTop: 10 }}>
            Si tu archivo no es de transacciones (por ejemplo presupuestos o datasets externos), usa <strong>Universal</strong>.
          </div>
        ) : (
          <div className="upload-hint" style={{ marginTop: 10 }}>
            Universal no recalcula KPIs de caja automáticamente; sirve para análisis exploratorio y asesoramiento.
          </div>
        )}
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
