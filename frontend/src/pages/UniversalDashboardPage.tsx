import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import KpiChart from '../components/KpiChart'
import {
  assistantChat,
  downloadUniversalNormalizedCsv,
  getUniversalSummary,
  getUniversalLatestRows,
  getReportContent,
  generateAdvisorReport,
  previewUniversalXlsx,
  uploadUniversalImport,
  type AdvisorAction,
  type AssistantMessage,
  type UniversalRows,
  type UniversalXlsxPreview
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'

export default function UniversalDashboardPage() {
  const { id: companyId, plan } = useCompanySelection()
  const hasPlatinum = plan === 'PLATINUM'
  const toast = useToast()
  const [file, setFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [uploadOk, setUploadOk] = useState<string | null>(null)
  const [xlsxPreview, setXlsxPreview] = useState<UniversalXlsxPreview | null>(null)
  const [xlsxLoading, setXlsxLoading] = useState(false)
  const [sheetIndex, setSheetIndex] = useState<number | null>(null)
  const [headerRow, setHeaderRow] = useState<number | null>(null)
  const [assistantMessages, setAssistantMessages] = useState<AssistantMessage[]>([
    { role: 'assistant', content: 'Soy tu asesor PLATINUM. Dime tu objetivo (margen, costes, caja o crecimiento) y te propongo un plan 30/60/90 días.' }
  ])
  const [assistantInput, setAssistantInput] = useState('')
  const [assistantLoading, setAssistantLoading] = useState(false)
  const [assistantActions, setAssistantActions] = useState<AdvisorAction[]>([])
  const [assistantPrompts, setAssistantPrompts] = useState<string[]>([])
  const [assistantQuestions, setAssistantQuestions] = useState<string[]>([])
  const [rowsPreview, setRowsPreview] = useState<UniversalRows | null>(null)
  const [rowsLoading, setRowsLoading] = useState(false)
  const [rowsError, setRowsError] = useState<string | null>(null)

  const { data, error, refetch } = useQuery({
    queryKey: ['universal-summary', companyId],
    queryFn: () => getUniversalSummary(companyId as number),
    enabled: !!companyId
  })

  async function handleUpload() {
    if (!companyId || !file) return
    setUploading(true)
    setUploadError(null)
    setUploadOk(null)
    try {
      const opts =
        file.name.toLowerCase().endsWith('.xlsx') && hasPlatinum
          ? { sheetIndex: sheetIndex ?? xlsxPreview?.sheetIndex ?? undefined, headerRow: headerRow ?? xlsxPreview?.headerRow ?? undefined }
          : {}
      await uploadUniversalImport(companyId, file, opts)
      await refetch()
      setFile(null)
      const kind = file.name.toLowerCase().endsWith('.xlsx') ? 'XLSX' : 'CSV'
      setUploadOk(`${kind} analizado correctamente.`)
      toast.push({ tone: 'success', title: 'Análisis', message: `${kind} analizado correctamente.` })
    } catch (err: any) {
      setUploadError(err?.message || 'Error subiendo archivo.')
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'Error subiendo archivo.' })
    } finally {
      setUploading(false)
    }
  }

  const summary = data as any
  const columns = summary?.columns || []
  const correlations = summary?.correlations || []
  const insights = summary?.insights || []
  const numericColumns = columns.filter((c: any) => c.detectedType === 'number').slice(0, 3)
  const dateColumns = columns.filter((c: any) => c.detectedType === 'date').slice(0, 2)
  const topCorrelations = correlations.slice(0, 5)

  const isCsv = !file ? true : file.name.toLowerCase().endsWith('.csv')
  const isXlsx = !file ? false : file.name.toLowerCase().endsWith('.xlsx')
  const isAllowed = !file ? true : isCsv || isXlsx

  const canPreviewXlsx = !!companyId && hasPlatinum && isXlsx && !!file

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
        if (!cancelled) setUploadError(e?.message || 'No se pudo previsualizar el XLSX.')
      } finally {
        if (!cancelled) setXlsxLoading(false)
      }
    }, 250)
    return () => {
      cancelled = true
      window.clearTimeout(t)
    }
  }, [canPreviewXlsx, file, companyId, previewOpts, sheetIndex, headerRow])

  async function sendAssistantMessage(text: string) {
    if (!companyId) return
    const trimmed = text.trim()
    if (!trimmed) return

    const nextMessages: AssistantMessage[] = [...assistantMessages, { role: 'user', content: trimmed }]
    setAssistantMessages(nextMessages)
    setAssistantLoading(true)
    try {
      const res = await assistantChat(companyId, nextMessages)
      setAssistantMessages((prev) => [...prev, { role: 'assistant', content: res.reply }])
      setAssistantActions(res.actions || [])
      setAssistantPrompts(res.suggestedPrompts || [])
      setAssistantQuestions(res.questions || [])
    } catch (e: any) {
      setAssistantMessages((prev) => [
        ...prev,
        { role: 'assistant', content: e?.message || 'No pude generar el asesoramiento.' }
      ])
    } finally {
      setAssistantLoading(false)
    }
  }

  async function handleDownloadNormalizedCsv() {
    if (!companyId) return
    const csv = await downloadUniversalNormalizedCsv(companyId)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `universal-normalized-${new Date().toISOString().slice(0, 10)}.csv`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  async function handleLoadRows(limit = 50) {
    if (!companyId) return
    setRowsLoading(true)
    setRowsError(null)
    try {
      const res = await getUniversalLatestRows(companyId, limit)
      setRowsPreview(res)
    } catch (e: any) {
      setRowsError(e?.message || 'No se pudo cargar la vista de filas.')
    } finally {
      setRowsLoading(false)
    }
  }

  async function handleGenerateAdvisorReport() {
    if (!companyId) return
    const rep = await generateAdvisorReport(companyId)
    const html = await getReportContent(companyId, rep.id)
    const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `informe-consultivo-${new Date().toISOString().slice(0, 10)}.html`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  return (
    <div>
      <PageHeader
        title="Análisis universal"
        subtitle="Sube un CSV o XLSX. El plan mejora la profundidad, las gráficas y el asesoramiento."
        actions={<span className="badge">{plan}</span>}
      />

      <div className="hero">
        <div>
          <div className="upload-row">
            <input
              type="file"
              accept=".csv,.xlsx"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
            <Button onClick={handleUpload} disabled={!file || uploading || !companyId || !isAllowed} loading={uploading}>
              Analizar
            </Button>
          </div>
          {!companyId && (
            <div style={{ marginTop: 12 }}>
              <Alert tone="warning">Selecciona una empresa para subir el archivo.</Alert>
            </div>
          )}
          {file && isXlsx && !hasPlatinum && (
            <p className="upload-hint">Tip: en PLATINUM puedes elegir hoja y fila de encabezado antes de analizar.</p>
          )}
          {file && isXlsx && hasPlatinum && (
            <div className="card soft" style={{ marginTop: 12 }}>
              <h3 style={{ marginTop: 0 }}>Opciones XLSX (PLATINUM)</h3>
              {xlsxLoading && <div className="upload-hint">Detectando estructura del Excel…</div>}
              {!!xlsxPreview?.sheets?.length && (
                <div className="upload-row" style={{ marginTop: 10 }}>
                  <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <span style={{ width: 110 }}>Hoja</span>
                    <select
                      value={sheetIndex ?? xlsxPreview.sheetIndex ?? 0}
                      onChange={(e) => setSheetIndex(Number(e.target.value))}
                      disabled={xlsxLoading}
                    >
                      {xlsxPreview.sheets.map((s, idx) => (
                        <option key={`${s}-${idx}`} value={idx}>
                          {idx + 1}. {s}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <span style={{ width: 110 }}>Encabezado</span>
                    <input
                      type="number"
                      min={1}
                      value={headerRow ?? xlsxPreview.headerRow ?? 1}
                      onChange={(e) => setHeaderRow(Number(e.target.value))}
                      disabled={xlsxLoading}
                      style={{ width: 90 }}
                    />
                    <small className="upload-hint">Fila (1-based)</small>
                  </label>
                </div>
              )}
              {!!xlsxPreview?.headers?.length && (
                <div style={{ marginTop: 10 }}>
                  <div className="upload-hint">Headers detectados: {xlsxPreview.headers.slice(0, 8).join(' · ')}{xlsxPreview.headers.length > 8 ? '…' : ''}</div>
                </div>
              )}
              {!!xlsxPreview?.sampleRows?.length && (
                <div style={{ marginTop: 10, overflow: 'auto' }}>
                  <table className="table">
                    <thead>
                      <tr>
                        {(xlsxPreview.headers || []).slice(0, 8).map((h) => (
                          <th key={h}>{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {xlsxPreview.sampleRows.slice(0, 5).map((row, idx) => (
                        <tr key={idx}>
                          {row.slice(0, 8).map((cell, cidx) => (
                            <td key={`${idx}-${cidx}`}>{cell}</td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
          {uploadError && (
            <div style={{ marginTop: 12 }}>
              <Alert tone="danger">{uploadError}</Alert>
            </div>
          )}
          {uploadOk && (
            <div style={{ marginTop: 12 }}>
              <Alert tone="success">{uploadOk}</Alert>
            </div>
          )}
        </div>
        <div className="card soft">
          <h3 style={{ marginTop: 0 }}>Resumen</h3>
          <div className="grid">
            <div className="kpi">
              <h4>Filas</h4>
              <strong>{summary?.rowCount ?? '-'}</strong>
            </div>
            <div className="kpi">
              <h4>Columnas</h4>
              <strong>{summary?.columnCount ?? '-'}</strong>
            </div>
            <div className="kpi">
              <h4>Archivo</h4>
              <strong style={{ fontSize: 14 }}>{summary?.filename ?? '-'}</strong>
            </div>
          </div>
        </div>
      </div>

      {error && <p className="error">{String((error as any).message)}</p>}

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Insights & asesoramiento</h3>
          {!insights.length ? (
            <div className="empty">Sin insights aun. Sube un archivo.</div>
          ) : (
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {insights.map((it: any, idx: number) => (
                <li key={`${it.title}-${idx}`} style={{ marginBottom: 8 }}>
                  <strong>{it.title}:</strong> {it.message}
                </li>
              ))}
            </ul>
          )}
          {plan === 'BRONZE' && (
            <div className="upload-hint" style={{ marginTop: 10 }}>
              En GOLD/PLATINUM se habilitan correlaciones, distribuciones completas y asesoramiento más accionable.
            </div>
          )}
        </div>
      </div>

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Asesor (PLATINUM)</h3>
          {!hasPlatinum ? (
            <div className="empty">Disponible en plan PLATINUM (consultoría 30/60/90 días).</div>
          ) : (
            <div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
                <Button variant="secondary" size="sm" onClick={handleDownloadNormalizedCsv}>Descargar CSV normalizado</Button>
                <Button variant="ghost" size="sm" onClick={() => handleLoadRows(50)} disabled={rowsLoading} loading={rowsLoading}>
                  Ver 50 filas
                </Button>
                <Button size="sm" onClick={handleGenerateAdvisorReport}>Descargar informe consultivo</Button>
              </div>
              {rowsError && <div className="error">{rowsError}</div>}
              {!!rowsPreview?.rows?.length && (
                <div style={{ marginBottom: 14, overflow: 'auto' }}>
                  <table className="table">
                    <thead>
                      <tr>
                        {rowsPreview.headers.slice(0, 10).map((h) => (
                          <th key={h}>{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {rowsPreview.rows.slice(0, 8).map((row, idx) => (
                        <tr key={idx}>
                          {row.slice(0, 10).map((cell, cidx) => (
                            <td key={`${idx}-${cidx}`}>{cell}</td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <div className="upload-hint" style={{ marginTop: 8 }}>
                    Preview de filas del CSV normalizado (drill-down). Para análisis completo usa la descarga.
                  </div>
                </div>
              )}

              <div style={{ maxHeight: 260, overflow: 'auto', paddingRight: 6 }}>
                {assistantMessages.map((m, idx) => (
                  <div key={idx} style={{ marginBottom: 10 }}>
                    <div className="badge" style={{ display: 'inline-block', marginBottom: 6 }}>
                      {m.role === 'user' ? 'Tú' : 'Asesor'}
                    </div>
                    <div style={{ whiteSpace: 'pre-wrap' }}>{m.content}</div>
                  </div>
                ))}
              </div>

              {!!assistantPrompts.length && (
                <div style={{ marginTop: 10, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  {assistantPrompts.slice(0, 6).map((p) => (
                    <Button key={p} variant="ghost" size="sm" onClick={() => sendAssistantMessage(p)} disabled={assistantLoading}>
                      {p}
                    </Button>
                  ))}
                </div>
              )}

              {!!assistantQuestions.length && (
                <div style={{ marginTop: 10 }}>
                  <div className="upload-hint">Preguntas para afinar:</div>
                  <div style={{ marginTop: 6, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    {assistantQuestions.slice(0, 6).map((q) => (
                      <Button key={q} variant="ghost" size="sm" onClick={() => sendAssistantMessage(q)} disabled={assistantLoading}>
                        {q}
                      </Button>
                    ))}
                  </div>
                </div>
              )}

              <div className="upload-row" style={{ marginTop: 10 }}>
                <input
                  value={assistantInput}
                  onChange={(e) => setAssistantInput(e.target.value)}
                  placeholder="Ej: Quiero mejorar margen este trimestre"
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault()
                      const t = assistantInput
                      setAssistantInput('')
                      sendAssistantMessage(t)
                    }
                  }}
                />
                <Button
                  onClick={() => {
                    const t = assistantInput
                    setAssistantInput('')
                    sendAssistantMessage(t)
                  }}
                  disabled={assistantLoading || !assistantInput.trim()}
                >
                  Enviar
                </Button>
                <Button variant="secondary" onClick={() => sendAssistantMessage('Plan 30/60/90')} disabled={assistantLoading}>
                  Plan 30/60/90
                </Button>
              </div>

              {!!assistantActions.length && (
                <div style={{ marginTop: 14 }}>
                  <h4 style={{ margin: '0 0 8px' }}>Plan recomendado</h4>
                  <div className="grid">
                    {assistantActions.slice(0, 6).map((a, idx) => (
                      <div key={`${a.title}-${idx}`} className="kpi">
                        <h4>{a.title}</h4>
                        <div className="mini-row">
                          <span className="badge">{a.horizon}</span>
                          <span className="badge">{a.priority}</span>
                        </div>
                        <div style={{ marginTop: 6 }}>{a.detail}</div>
                        {a.kpi && <div className="upload-hint" style={{ marginTop: 8 }}>KPI: {a.kpi}</div>}
                        {!!a.evidence?.length && (
                          <div style={{ marginTop: 10 }}>
                            <div className="upload-hint">Evidencias</div>
                            <ul style={{ margin: '6px 0 0', paddingLeft: 18 }}>
                              {a.evidence.slice(0, 6).map((e, eidx) => {
                                const meta = [e.subtitle, e.metric].filter(Boolean).join(' · ')
                                return (
                                  <li key={`${e.type}-${e.title}-${eidx}`} style={{ marginBottom: 6 }}>
                                    <strong>{e.title}</strong>{meta ? <span className="upload-hint"> ({meta})</span> : null}
                                    {e.detail ? <div className="upload-hint">{e.detail}</div> : null}
                                  </li>
                                )
                              })}
                            </ul>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Columnas</h3>
          {!columns.length ? (
            <div className="empty">Aún no hay análisis. Sube un CSV.</div>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Columna</th>
                  <th>Tipo</th>
                  <th>Nulos</th>
                  <th>Unicos</th>
                  <th>Min</th>
                  <th>Max</th>
                  <th>Media</th>
                  <th>Mediana</th>
                  <th>P90</th>
                  <th>Fecha min</th>
                  <th>Fecha max</th>
                </tr>
              </thead>
              <tbody>
                {columns.map((c: any) => (
                  <tr key={c.name}>
                    <td>{c.name}</td>
                    <td>{c.detectedType}</td>
                    <td>{c.nullCount}</td>
                    <td>{c.uniqueCount}</td>
                    <td>{c.min ?? '-'}</td>
                    <td>{c.max ?? '-'}</td>
                    <td>{c.mean ?? '-'}</td>
                    <td>{c.median ?? '-'}</td>
                    <td>{c.p90 ?? '-'}</td>
                    <td>{c.dateMin ?? '-'}</td>
                    <td>{c.dateMax ?? '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Distribuciones numericas</h3>
          {!numericColumns.length ? (
            <div className="empty">No se detectaron columnas numericas.</div>
          ) : (
            <div className="grid">
              {numericColumns.map((c: any) => (
                <div key={`${c.name}-hist`} className="kpi">
                  <h4>{c.name}</h4>
                  {!c.histogram?.length ? (
                    <span className="badge">sin histograma</span>
                  ) : (
                    <div className="bar-stack">
                      {c.histogram.map((b: any, idx: number) => (
                        <div key={`${c.name}-bin-${idx}`} className="bar-row">
                          <span className="bar-label">{b.label}</span>
                          <div className="bar-track">
                            <div
                              className="bar-fill"
                              style={{ width: `${Math.min(100, (b.count / Math.max(...c.histogram.map((x: any) => x.count))) * 100)}%` }}
                            />
                          </div>
                          <span className="bar-count">{b.count}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Series de fechas</h3>
          {!dateColumns.length ? (
            <div className="empty">No se detectaron columnas de fecha.</div>
          ) : (
            <div className="grid">
              {dateColumns.map((c: any) => (
                <div key={`${c.name}-dates`} className="kpi">
                  <h4>{c.name}</h4>
                  {!c.dateSeries?.length ? (
                    <span className="badge">sin serie</span>
                  ) : (
                    <KpiChart
                      title="Conteo por mes"
                      points={c.dateSeries.map((d: any) => ({ label: d.label, value: Number(d.count) }))}
                    />
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Top valores (categorias)</h3>
          {!columns.length ? (
            <div className="empty">Sin datos todavia.</div>
          ) : (
            <div className="grid">
              {columns.map((c: any) => (
                <div key={`${c.name}-top`} className="kpi">
                  <h4>{c.name}</h4>
                  {!c.topValues?.length ? (
                    <span className="badge">sin valores</span>
                  ) : (
                    <div>
                      {c.topValues.map((v: any) => (
                        <div key={`${c.name}-${v.value}`} className="mini-row">
                          <span>{v.value}</span>
                          <span className="badge">{v.count}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Correlaciones (numericas)</h3>
          {!correlations.length ? (
            <div className="empty">
              {plan === 'BRONZE'
                ? 'Correlaciones disponibles en plan GOLD o superior.'
                : 'No hay correlaciones disponibles.'}
            </div>
          ) : (
            <div>
              <div className="insight-grid">
                {topCorrelations.map((c: any, idx: number) => (
                  <div key={`${c.columnA}-${c.columnB}-${idx}`} className="kpi">
                    <h4>{c.columnA} vs {c.columnB}</h4>
                    <strong>{c.correlation?.toFixed ? c.correlation.toFixed(3) : c.correlation}</strong>
                  </div>
                ))}
              </div>
              <div style={{ marginTop: 12 }} />
            <table className="table">
              <thead>
                <tr>
                  <th>Columna A</th>
                  <th>Columna B</th>
                  <th>Correlacion</th>
                </tr>
              </thead>
              <tbody>
                {correlations.map((c: any, idx: number) => (
                  <tr key={`${c.columnA}-${c.columnB}-${idx}`}>
                    <td>{c.columnA}</td>
                    <td>{c.columnB}</td>
                    <td>{c.correlation?.toFixed ? c.correlation.toFixed(3) : c.correlation}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
