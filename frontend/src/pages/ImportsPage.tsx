import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getImports,
  previewImport,
  previewUniversalXlsx,
  retryImport,
  type ImportJob,
  type ImportPreviewDto,
  uploadImportSmart,
  uploadUniversalImport,
  type UniversalXlsxPreview
} from '../api'
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
  const [mode, setMode] = useState<'auto' | 'transactions' | 'universal'>('auto')
  const hasPlatinum = plan === 'PLATINUM'
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'

  const [period, setPeriod] = useState('2025-06')
  const [file, setFile] = useState<File | null>(null)
  const [message, setMessage] = useState('')
  const [tone, setTone] = useState<'info' | 'success' | 'danger'>('info')

  const [uploading, setUploading] = useState(false)
  const [xlsxPreview, setXlsxPreview] = useState<UniversalXlsxPreview | null>(null)
  const [xlsxLoading, setXlsxLoading] = useState(false)
  const [sheetIndex, setSheetIndex] = useState<number | null>(null)
  const [headerRow, setHeaderRow] = useState<number | null>(null)

  const [txPreview, setTxPreview] = useState<ImportPreviewDto | null>(null)
  const [txPreviewLoading, setTxPreviewLoading] = useState(false)
  const [txnDateCol, setTxnDateCol] = useState('')
  const [amountCol, setAmountCol] = useState('')
  const [descriptionCol, setDescriptionCol] = useState('')
  const [counterpartyCol, setCounterpartyCol] = useState('')
  const [balanceEndCol, setBalanceEndCol] = useState('')

  const isCsv = !file ? true : file.name.toLowerCase().endsWith('.csv')
  const isXlsx = !file ? false : file.name.toLowerCase().endsWith('.xlsx')
  const isAllowed = !file ? true : isCsv || isXlsx
  const canPreviewXlsx = !!companyId && hasPlatinum && (mode === 'universal' || mode === 'auto') && isXlsx && !!file

  const tribunalHint = useMemo(() => {
    const headers = txPreview?.headers || []
    if (!headers.length) return { score: 0, isLikely: false }
    const norm = headers.map((h) => String(h || '').toLowerCase())
    const hits = (k: string) => norm.some((h) => h.includes(k))
    let score = 0
    if (hits('cif')) score += 2
    if (hits('gestor') || hits('manager')) score += 2
    if (hits('minuta') || hits('minutas')) score += 2
    if (hits('carga') || hits('carga_de_trabajo')) score += 2
    if (hits('irpf') || hits('ddcc') || hits('libros')) score += 2
    if (hits('cont_modelos') || hits('contabilidad')) score += 2
    return { score, isLikely: score >= 4 }
  }, [txPreview?.headers])

  const autoTarget = useMemo(() => {
    if (mode !== 'auto') return null as null | 'transactions' | 'universal' | 'tribunal'
    if (!file || !txPreview) return null
    if (tribunalHint.isLikely) return 'tribunal'
    const conf = Number(txPreview.confidence || 0)
    if (conf >= 0.6 && txnDateCol && amountCol) return 'transactions'
    return 'universal'
  }, [mode, file, txPreview, tribunalHint.isLikely, txnDateCol, amountCol])

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
    setTxPreview(null)
    setTxnDateCol('')
    setAmountCol('')
    setDescriptionCol('')
    setCounterpartyCol('')
    setBalanceEndCol('')
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

  useEffect(() => {
    if (!companyId || !file || (mode !== 'transactions' && mode !== 'auto')) return
    let cancelled = false
    const t = window.setTimeout(async () => {
      setTxPreviewLoading(true)
      try {
        const prev = await previewImport(companyId as number, file, { sheetIndex: sheetIndex ?? undefined, headerRow: headerRow ?? undefined })
        if (cancelled) return
        setTxPreview(prev)
        const s = prev.suggestedMapping || {}
        setTxnDateCol(String(s.txn_date || ''))
        setAmountCol(String(s.amount || ''))
        setDescriptionCol(String(s.description || ''))
        setCounterpartyCol(String(s.counterparty || ''))
        setBalanceEndCol(String(s.balance_end || ''))
      } catch (e: any) {
        if (!cancelled) {
          setTone('danger')
          setMessage(e?.message || 'No se pudo analizar el fichero.')
        }
      } finally {
        if (!cancelled) setTxPreviewLoading(false)
      }
    }, 250)
    return () => {
      cancelled = true
      window.clearTimeout(t)
    }
  }, [companyId, file, mode, sheetIndex, headerRow])

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

      if (mode === 'auto') {
        if (autoTarget === 'tribunal') {
          if (!hasGold) {
            setTone('danger')
            setMessage('Este fichero parece de Tribunal (cumplimiento), pero requiere plan GOLD/PLATINUM.')
            return
          }
          toast.push({ tone: 'info', title: 'Auto', message: 'Parece un fichero de Tribunal. Te llevo a Cumplimiento (Tribunal).' })
          navigate('/tribunal')
          return
        }
        if (autoTarget === 'transactions') {
          if (!txnDateCol || !amountCol) {
            setTone('danger')
            setMessage('No se detectaron columnas de fecha/importe. Cambia a Universal o selecciona manualmente el modo Caja.')
            return
          }
          await uploadImportSmart(companyId, period, file, {
            txnDateCol,
            amountCol,
            descriptionCol: descriptionCol || undefined,
            counterpartyCol: counterpartyCol || undefined,
            balanceEndCol: balanceEndCol || undefined,
            sheetIndex: sheetIndex ?? undefined,
            headerRow: headerRow ?? undefined
          })
          await queryClient.invalidateQueries({ queryKey: ['imports', companyId] })
          setTone('success')
          setMessage('Import encolado (auto). Se procesará automáticamente y recalculará KPIs/alertas.')
          toast.push({ tone: 'success', title: 'Import (auto)', message: 'Import encolado para procesado.' })
          return
        }
        const opts =
          file.name.toLowerCase().endsWith('.xlsx') && hasPlatinum
            ? { sheetIndex: sheetIndex ?? xlsxPreview?.sheetIndex ?? undefined, headerRow: headerRow ?? xlsxPreview?.headerRow ?? undefined }
            : {}
        await uploadUniversalImport(companyId, file, opts)
        setTone('success')
        setMessage('Archivo analizado en Universal (auto). Ya puedes ver columnas, insights y el asesor.')
        toast.push({ tone: 'success', title: 'Universal (auto)', message: 'Archivo analizado correctamente.' })
        navigate('/universal')
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

      if (!txnDateCol || !amountCol) {
        setTone('danger')
        setMessage('Selecciona al menos la columna de fecha y la de importe para calcular caja.')
        return
      }

      await uploadImportSmart(companyId, period, file, {
        txnDateCol,
        amountCol,
        descriptionCol: descriptionCol || undefined,
        counterpartyCol: counterpartyCol || undefined,
        balanceEndCol: balanceEndCol || undefined,
        sheetIndex: sheetIndex ?? undefined,
        headerRow: headerRow ?? undefined
      })
      await queryClient.invalidateQueries({ queryKey: ['imports', companyId] })
      setTone('success')
      setMessage('Import encolado. Se procesará automáticamente y recalculará KPIs/alertas.')
      toast.push({ tone: 'success', title: 'Import', message: 'CSV subido y encolado para procesado.' })
    } catch (err: any) {
      setTone('danger')
      const msg = err?.message || 'No se pudo subir el fichero.'
      setMessage(msg)
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'No se pudo subir el CSV.' })
      if ((mode === 'transactions' || mode === 'auto') && String(msg).toLowerCase().includes('formato incorrecto')) {
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
        subtitle="Modo AUTO recomendado: sube cualquier fichero y EnterpriseIQ te guía (Caja/Universal/Tribunal)."
        actions={
          <span className="badge">
            {mode === 'auto'
              ? 'AUTO • detecta objetivo'
              : mode === 'transactions'
              ? 'Caja • txn_date + amount'
              : 'Universal • cualquier estructura'}
          </span>
        }
      />

      <div className="section">
        <div className="segmented" role="tablist" aria-label="Modo de carga">
          <Button
            type="button"
            variant={mode === 'auto' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setMode('auto')}
            aria-selected={mode === 'auto'}
          >
            Auto (recomendado)
          </Button>
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

        {mode === 'auto' ? (
          <div className="grid" style={{ marginTop: 14 }}>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Auto</h3>
              <p className="hero-sub">Sube un fichero y te sugerimos el destino: Caja (movimientos), Universal (cualquier dataset) o Tribunal.</p>
            </div>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Consejo</h3>
              <p className="hero-sub">Si tu fichero no son movimientos bancarios, casi siempre encaja mejor en Universal.</p>
            </div>
          </div>
        ) : mode === 'transactions' ? (
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

      {mode === 'transactions' && file && txPreview && Number(txPreview.confidence || 0) < 0.4 ? (
        <div className="section">
          <Alert tone="warning" title="Este fichero quizá no es de transacciones">
            La detección de columnas tiene poca confianza. Si es un presupuesto / ventas / inventario, usa el modo Universal.
            <div style={{ marginTop: 10 }}>
              <Button size="sm" variant="secondary" onClick={() => setMode('universal')}>
                Cambiar a Universal
              </Button>
            </div>
          </Alert>
        </div>
      ) : null}

      {mode === 'auto' && file && txPreview ? (
        <div className="section">
          <Alert tone="info" title="Sugerencia (auto)">
            {autoTarget === 'transactions'
              ? 'Parece un fichero de transacciones (Caja).'
              : autoTarget === 'tribunal'
              ? 'Parece un fichero de Tribunal (cumplimiento).'
              : 'Parece un dataset genérico. Recomendado: Universal.'}
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Confianza transacciones: {Math.round(Number(txPreview.confidence || 0) * 100)}% · Señales Tribunal: {tribunalHint.score}
            </div>
            <div style={{ marginTop: 10, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <Button size="sm" variant={autoTarget === 'transactions' ? 'secondary' : 'ghost'} onClick={() => setMode('transactions')}>
                Usar Caja
              </Button>
              <Button size="sm" variant={autoTarget === 'universal' ? 'secondary' : 'ghost'} onClick={() => setMode('universal')}>
                Usar Universal
              </Button>
              <Button
                size="sm"
                variant={autoTarget === 'tribunal' ? 'secondary' : 'ghost'}
                onClick={() => navigate('/tribunal')}
                disabled={!hasGold}
              >
                Ir a Tribunal
              </Button>
            </div>
          </Alert>
        </div>
      ) : null}

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
          {(mode === 'universal' || mode === 'auto') && hasPlatinum && isXlsx ? (
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
          {mode === 'transactions' && isXlsx ? (
            <details style={{ width: '100%' }}>
              <summary className="upload-hint" style={{ cursor: 'pointer' }}>
                Ajustes XLSX (opcional)
              </summary>
              <div className="upload-row" style={{ marginTop: 10 }}>
                <input
                  value={sheetIndex ?? ''}
                  onChange={(e) => setSheetIndex(e.target.value === '' ? null : Number(e.target.value))}
                  placeholder="sheetIndex"
                  inputMode="numeric"
                  style={{ width: 140 }}
                />
                <input
                  value={headerRow ?? ''}
                  onChange={(e) => setHeaderRow(e.target.value === '' ? null : Number(e.target.value))}
                  placeholder="headerRow"
                  inputMode="numeric"
                  style={{ width: 140 }}
                />
                {txPreviewLoading ? <span className="upload-hint">Analizando…</span> : null}
                {txPreview?.confidence != null ? (
                  <span className="upload-hint">Confianza: {(txPreview.confidence * 100).toFixed(0)}%</span>
                ) : null}
              </div>
            </details>
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

      {mode === 'transactions' && companyId && file && txPreview ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>Asistente de mapeo (Caja)</h3>
          <div className="upload-hint">
            Selecciona qué columnas significan <strong>fecha</strong> e <strong>importe</strong>. El resto es opcional.
          </div>
          <div className="upload-row" style={{ marginTop: 12 }}>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Fecha (txn_date)</span>
              <select value={txnDateCol} onChange={(e) => setTxnDateCol(e.target.value)}>
                <option value="">—</option>
                {txPreview.headers.map((h) => (
                  <option key={`d-${h}`} value={h}>
                    {h}
                  </option>
                ))}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Importe (amount)</span>
              <select value={amountCol} onChange={(e) => setAmountCol(e.target.value)}>
                <option value="">—</option>
                {txPreview.headers.map((h) => (
                  <option key={`a-${h}`} value={h}>
                    {h}
                  </option>
                ))}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Descripción</span>
              <select value={descriptionCol} onChange={(e) => setDescriptionCol(e.target.value)}>
                <option value="">(ninguna)</option>
                {txPreview.headers.map((h) => (
                  <option key={`ds-${h}`} value={h}>
                    {h}
                  </option>
                ))}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Contrapartida</span>
              <select value={counterpartyCol} onChange={(e) => setCounterpartyCol(e.target.value)}>
                <option value="">(ninguna)</option>
                {txPreview.headers.map((h) => (
                  <option key={`cp-${h}`} value={h}>
                    {h}
                  </option>
                ))}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Saldo fin</span>
              <select value={balanceEndCol} onChange={(e) => setBalanceEndCol(e.target.value)}>
                <option value="">(ninguna)</option>
                {txPreview.headers.map((h) => (
                  <option key={`be-${h}`} value={h}>
                    {h}
                  </option>
                ))}
              </select>
            </label>
          </div>

          {txPreview.sampleRows?.length ? (
            <div style={{ marginTop: 14, overflow: 'auto' }}>
              <table className="table">
                <thead>
                  <tr>
                    {txPreview.headers.slice(0, 8).map((h) => (
                      <th key={`h-${h}`}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {txPreview.sampleRows.slice(0, 6).map((r, idx) => (
                    <tr key={`r-${idx}`}>
                      {r.slice(0, 8).map((v, c) => (
                        <td key={`c-${idx}-${c}`} className="upload-hint">
                          {String(v || '').slice(0, 60)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="empty" style={{ marginTop: 12 }}>
              No se pudieron leer filas de muestra.
            </div>
          )}
        </div>
      ) : null}

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
