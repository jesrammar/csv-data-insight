import { useQuery } from '@tanstack/react-query'
import { useRef, useState, useEffect } from 'react'
import { downloadTribunalCsv, getTribunalStatus, getTribunalSummary, uploadTribunalImportWithProgress } from '../api'
import KpiChart from '../components/KpiChart'
import { useCompanySelection } from '../hooks/useCompany'
import Papa from 'papaparse'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'
import Section from '../components/ui/Section'

type GestorRow = {
  gestor: string
  total: number
  carga: number
  minuta: number
}

export default function TribunalDashboardPage() {
  const { id: companyId, plan } = useCompanySelection()
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const toast = useToast()
  const [file, setFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [uploadOk, setUploadOk] = useState<string | null>(null)
  const [dragActive, setDragActive] = useState(false)
  const [uploadProgress, setUploadProgress] = useState<number>(0)
  const [autoSync, setAutoSync] = useState(false)
  const [autoSyncSeconds, setAutoSyncSeconds] = useState(20)
  const [previewHeaders, setPreviewHeaders] = useState<string[]>([])
  const [previewRows, setPreviewRows] = useState<string[][]>([])
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [importInfo, setImportInfo] = useState<any | null>(null)
  const dashboardRef = useRef<HTMLDivElement | null>(null)
  const uploadAnchorRef = useRef<HTMLDivElement | null>(null)

  const [gestorSort, setGestorSort] = useState<{ key: 'gestor' | 'total' | 'active' | 'minuta' | 'carga'; dir: 'asc' | 'desc' }>({
    key: 'carga',
    dir: 'desc'
  })
  const [riskGestor, setRiskGestor] = useState<string>('')
  const [riskQuery, setRiskQuery] = useState<string>('')

  const { data, error, refetch } = useQuery({
    queryKey: ['tribunal-summary', companyId],
    queryFn: () => getTribunalSummary(companyId as number),
    enabled: !!companyId && hasGold
  })

  const { data: status, refetch: refetchStatus } = useQuery({
    queryKey: ['tribunal-status', companyId],
    queryFn: () => getTribunalStatus(companyId as number),
    enabled: !!companyId && hasGold
  })

  function fmtTime(iso?: string | null) {
    if (!iso) return '—'
    try {
      return new Date(iso).toLocaleString()
    } catch {
      return String(iso)
    }
  }

  async function handleUpload() {
    if (!companyId || !file || !hasGold) return
    setUploading(true)
    setUploadError(null)
    setUploadOk(null)
    setUploadProgress(10)
    try {
      const result = await uploadTribunalImportWithProgress(companyId, file, (pct) => setUploadProgress(pct))
      setImportInfo(result)
      await refetch()
      await refetchStatus()
      setFile(null)
      const warn = result?.warningCount ? ` (${result.warningCount} avisos)` : ''
      setUploadOk(`CSV cargado correctamente${warn}.`)
      setUploadProgress(100)
      toast.push({ tone: 'success', title: 'Tribunal', message: `CSV cargado correctamente${warn}.` })
    } catch (err: any) {
      setUploadError(err?.message || 'Error subiendo CSV.')
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'Error subiendo CSV.' })
    } finally {
      setUploading(false)
      setTimeout(() => setUploadProgress(0), 800)
    }
  }

  function handleDrop(evt: React.DragEvent<HTMLDivElement>) {
    evt.preventDefault()
    setDragActive(false)
    const dropped = evt.dataTransfer.files?.[0]
    if (dropped) setFile(dropped)
  }

  function handleDrag(evt: React.DragEvent<HTMLDivElement>) {
    evt.preventDefault()
    setDragActive(true)
  }

  function handleDragLeave(evt: React.DragEvent<HTMLDivElement>) {
    evt.preventDefault()
    setDragActive(false)
  }

  const isCsv = !file ? true : file.name.toLowerCase().endsWith('.csv')
  const canUpload = !!companyId && hasGold && !!file && isCsv && !uploading && !previewError

  function downloadTemplate() {
    const csv =
      'cliente,cif,gestor,minutas,falta,fbaja,cargadetrabajo,pctcontabilidad\n' +
      'ACME S.L.,B12345678,CARMEN,1200,2024-01,,6,100\n' +
      'EJEMPLO S.A.,A11111111,I.S.,800,2023-06,2025-02,4,60\n'
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'plantilla-tribunal.csv'
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  useEffect(() => {
    let interval: number | undefined
    if (autoSync && file && isCsv && companyId && hasGold) {
      interval = window.setInterval(() => {
        if (!uploading) {
          handleUpload()
        }
      }, autoSyncSeconds * 1000)
    }
    return () => {
      if (interval) window.clearInterval(interval)
    }
  }, [autoSync, file, isCsv, companyId, hasGold, autoSyncSeconds, uploading])

  useEffect(() => {
    if (!file || !isCsv) {
      setPreviewHeaders([])
      setPreviewRows([])
      setPreviewError(null)
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      const text = String(reader.result || '')
      parseCsvPreview(text, 8)
    }
    reader.readAsText(file)
  }, [file, isCsv])

  function parseCsvPreview(text: string, maxRows: number) {
    setPreviewError(null)
    Papa.parse<Record<string, unknown>>(text, {
      header: true,
      skipEmptyLines: true,
      dynamicTyping: false,
      transformHeader: (h: string) => h.trim(),
      complete: (result: Papa.ParseResult<Record<string, unknown>>) => {
        const fields = (result.meta.fields || []).filter(Boolean)
        const rows = (result.data || []).slice(0, maxRows).map((row) =>
          fields.map((f) => String((row as any)[f] ?? '').trim())
        )
        setPreviewHeaders(fields)
        setPreviewRows(rows)
        if (result.errors?.length) {
          setPreviewError(result.errors[0].message)
        }
      },
      error: (err: Error) => {
        setPreviewHeaders([])
        setPreviewRows([])
        setPreviewError(err.message || 'Error parseando CSV')
      }
    })
  }

  async function handleExportCsv() {
    if (!companyId) return
    const csv = await downloadTribunalCsv(companyId)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `tribunal-export-${new Date().toISOString().slice(0, 10)}.csv`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  async function handleExportPng() {
    if (!dashboardRef.current) return
    const { default: html2canvas } = await import('html2canvas')
    const canvas = await html2canvas(dashboardRef.current, { scale: 2, backgroundColor: '#0b1220' })
    const url = canvas.toDataURL('image/png')
    const a = document.createElement('a')
    a.href = url
    a.download = `tribunal-dashboard-${new Date().toISOString().slice(0, 10)}.png`
    document.body.appendChild(a)
    a.click()
    a.remove()
  }

  async function handleExportPdf() {
    if (!dashboardRef.current) return
    const { default: html2canvas } = await import('html2canvas')
    const { default: jsPDF } = await import('jspdf')
    const canvas = await html2canvas(dashboardRef.current, { scale: 2, backgroundColor: '#0b1220' })
    const imgData = canvas.toDataURL('image/png')
    const pdf = new jsPDF('p', 'mm', 'a4')
    const pdfWidth = 210
    const pdfHeight = (canvas.height * pdfWidth) / canvas.width
    pdf.addImage(imgData, 'PNG', 0, 0, pdfWidth, pdfHeight)
    pdf.save(`tribunal-dashboard-${new Date().toISOString().slice(0, 10)}.pdf`)
  }

  const activityPoints = (data?.activity || []).map((p: any) => ({
    label: String(p.year),
    value: Number(p.totalAsientos)
  }))

  const gestores: GestorRow[] = (data?.gestores || []).map((g: any) => ({
    gestor: g.gestor,
    total: Number(g.totalClients || 0),
    carga: Number(g.cargaAvg || 0),
    minuta: Number(g.minutasAvg || 0)
  }))

  const gestoresTable = (data?.gestores || []).map((g: any) => ({
    gestor: String(g.gestor ?? ''),
    total: Number(g.totalClients || 0),
    active: Number(g.activeClients || 0),
    minuta: Number(g.minutasAvg || 0),
    carga: Number(g.cargaAvg || 0)
  }))

  const gestoresSorted = gestoresTable.slice().sort((a: any, b: any) => {
    const dir = gestorSort.dir === 'asc' ? 1 : -1
    if (gestorSort.key === 'gestor') return dir * a.gestor.localeCompare(b.gestor)
    return dir * (Number(a[gestorSort.key]) - Number(b[gestorSort.key]))
  })

  function toggleGestorSort(key: 'gestor' | 'total' | 'active' | 'minuta' | 'carga') {
    setGestorSort((s) => (s.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'desc' }))
  }

  function sortMark(key: 'gestor' | 'total' | 'active' | 'minuta' | 'carga') {
    if (gestorSort.key !== key) return ''
    return gestorSort.dir === 'asc' ? ' ↑' : ' ↓'
  }

  const topGestores = gestores
    .slice()
    .sort((a: GestorRow, b: GestorRow) => b.carga - a.carga)
    .slice(0, 6)

  const gestorCargaPoints = topGestores.map((g: GestorRow) => ({
    label: g.gestor,
    value: g.carga
  }))

  const totalClients = Number((data as any)?.kpis?.totalClients || 0)
  const hasData = totalClients > 0
  const riskRows: any[] = (data as any)?.risk || []
  const gestorOptions = Array.from(new Set(riskRows.map((r) => String(r?.gestor || 'SIN GESTOR')))).sort((a, b) =>
    a.localeCompare(b)
  )
  const riskFiltered = riskRows.filter((r) => {
    const g = String(r?.gestor || 'SIN GESTOR')
    const text = `${r?.cliente || ''} ${r?.cif || ''} ${r?.issues || ''}`.toLowerCase()
    const okGestor = !riskGestor || g === riskGestor
    const okQuery = !riskQuery || text.includes(riskQuery.trim().toLowerCase())
    return okGestor && okQuery
  })

  return (
    <div ref={dashboardRef}>
      <PageHeader
        title="Dashboard Tribunal"
        subtitle="Cumplimiento, riesgos y gestión por gestor. Carga un CSV y el tablero se actualiza."
        actions={
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center', justifyContent: 'flex-end' }}>
            <span className="badge">{plan}</span>
            {hasGold ? (
              <div className="card soft" style={{ padding: 12, minWidth: 240 }}>
                <div className="upload-hint">Última carga</div>
                <div style={{ fontWeight: 800, marginTop: 6 }}>{fmtTime(status?.createdAt)}</div>
                <div className="upload-hint" style={{ marginTop: 6 }}>{status?.filename ? `Fichero: ${status.filename}` : 'Sin ingestas'}</div>
                <div className="upload-hint" style={{ marginTop: 4 }}>
                  {status?.rowCount != null ? `Filas: ${status.rowCount}` : '—'}
                  {status?.warningCount ? ` · Avisos: ${status.warningCount}` : ''}
                </div>
              </div>
            ) : null}
          </div>
        }
      />
      {!hasGold && (
        <div style={{ marginBottom: 14 }}>
          <Alert tone="warning" title="Plan insuficiente">
            Tu plan actual no incluye este estudio. Requiere plan GOLD o superior.
          </Alert>
        </div>
      )}

      {hasGold && (
        <div
          className="card soft"
          style={{
            position: 'sticky',
            top: 74,
            zIndex: 4,
            margin: '14px 0 18px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 14,
            flexWrap: 'wrap'
          }}
        >
          <div>
            <div className="upload-hint">{hasData ? 'Listo para analizar' : 'Siguiente paso: cargar CSV'}</div>
            <div style={{ fontWeight: 800, marginTop: 4 }}>
              {hasData ? `${totalClients} clientes en el tablero` : 'Aún no hay datos para este estudio.'}
            </div>
            <div className="upload-hint" style={{ marginTop: 6 }}>
              Mínimo requerido: columnas <strong>cliente</strong> y <strong>cif</strong> (resto opcional).
            </div>
          </div>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            <Button
              size="sm"
              onClick={() => uploadAnchorRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
              disabled={!companyId}
            >
              {hasData ? 'Recargar CSV' : 'Cargar CSV'}
            </Button>
          </div>
        </div>
      )}

      <Section title="1) Ingesta" subtitle="Carga el CSV y sincroniza para recalcular el tablero.">
        <div className="hero" ref={uploadAnchorRef}>
          <div>
            <div
              className={`upload-panel ${dragActive ? 'is-dragging' : ''}`}
              onDragOver={handleDrag}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
            >
              <div className="upload-input">
                <input
                  id="tribunal-upload"
                  type="file"
                  accept=".csv"
                  onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                />
                <label htmlFor="tribunal-upload">
                  <span>Seleccionar CSV</span>
                  <small>{file?.name || 'Ningún archivo seleccionado'}</small>
                </label>
              </div>
              <div className="upload-actions">
                <Button
                  onClick={handleUpload}
                  disabled={!canUpload}
                  loading={uploading}
                >
                  Subir CSV
                </Button>
                <Button variant="secondary" size="sm" onClick={downloadTemplate}>
                  Descargar plantilla
                </Button>
                {file && !isCsv && <span className="error">Solo se permite CSV.</span>}
                {!file && <span className="upload-hint">Arrastra y suelta aquí para cargar rápido.</span>}
                {file && isCsv && <span className="upload-hint">Tip: puedes re-subir para recalcular al instante.</span>}
                {previewError && (
                  <span className="error">
                    El CSV no se puede previsualizar: {previewError}. Suele pasar por comillas mal cerradas o varias tablas en la
                    misma hoja.
                  </span>
                )}
                <label className="upload-toggle">
                  <input type="checkbox" checked={autoSync} onChange={(e) => setAutoSync(e.target.checked)} />
                  Auto-sync
                </label>
                <select
                  value={autoSyncSeconds}
                  onChange={(e) => setAutoSyncSeconds(Number(e.target.value))}
                  disabled={!autoSync}
                >
                  <option value={10}>Cada 10s</option>
                  <option value={20}>Cada 20s</option>
                  <option value={30}>Cada 30s</option>
                  <option value={60}>Cada 60s</option>
                </select>
                {uploading && (
                  <div className="upload-progress">
                    <div className="upload-progress-bar" style={{ width: `${uploadProgress}%` }} />
                  </div>
                )}
              </div>
            </div>
            {!companyId && (
              <div style={{ marginTop: 12 }}>
                <Alert tone="warning">Selecciona una empresa para subir el CSV.</Alert>
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
            {importInfo?.errorSummary && (
              <div style={{ marginTop: 12 }}>
                <Alert tone="warning" title="Avisos">
                  {importInfo.errorSummary}
                </Alert>
              </div>
            )}
            <div style={{ marginTop: 12 }} className="upload-hint">
              Recomendado: separador <strong>;</strong> o <strong>,</strong> y cabeceras en la primera fila. Mínimo requerido:{' '}
              <strong>cliente</strong> y <strong>cif</strong>. Si tu fichero es un presupuesto con varias tablas/gráficas, este estudio
              no lo va a “entender”: súbelo a <strong>Universal</strong> (Cargar datos) y luego lo conectamos a un tablero de
              presupuestos.
            </div>
          </div>
          <div className="card soft">
            <h3 style={{ marginTop: 0 }}>Exportaciones</h3>
            <div className="export-actions">
              <Button variant="secondary" size="sm" onClick={handleExportCsv}>
                CSV
              </Button>
              <Button variant="secondary" size="sm" onClick={handleExportPng}>
                PNG
              </Button>
              <Button variant="secondary" size="sm" onClick={handleExportPdf}>
                PDF
              </Button>
            </div>
          </div>
        </div>
      </Section>

      {error && <p className="error">{String((error as any).message)}</p>}

      <Section title="2) Vista previa" subtitle="Valida columnas y primeras filas antes de operar.">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Vista previa del CSV</h3>
          {previewError ? (
            <div className="empty">{previewError}</div>
          ) : !previewHeaders.length ? (
            <div className="empty">Selecciona un CSV para ver columnas y primeras filas.</div>
          ) : (
            <div className="csv-preview">
              <table className="table">
                <thead>
                  <tr>
                    {previewHeaders.map((h) => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {previewRows.map((row, idx) => (
                    <tr key={idx}>
                      {row.map((cell, cidx) => (
                        <td key={`${idx}-${cidx}`}>{cell}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </Section>

      <Section title="3) KPIs y actividad" subtitle="KPIs principales, actividad contable y ranking por gestor.">
        <div className="grid">
          <div className="card">
            <h3 style={{ marginTop: 0 }}>KPIs principales</h3>
            <div className="grid">
              <div className="kpi">
                <h4>Total clientes</h4>
                <strong>{data?.kpis?.totalClients ?? '-'}</strong>
              </div>
              <div className="kpi">
                <h4>Clientes activos</h4>
                <strong>{data?.kpis?.activeClients ?? '-'}</strong>
              </div>
              <div className="kpi">
                <h4>% bajas</h4>
                <strong>{data?.kpis?.bajaPct ?? '-'}</strong>
              </div>
              <div className="kpi">
                <h4>Minuta media</h4>
                <strong>{data?.kpis?.minutasAvg ?? '-'}</strong>
              </div>
              <div className="kpi">
                <h4>Carga media</h4>
                <strong>{data?.kpis?.cargaAvg ?? '-'}</strong>
              </div>
              <div className="kpi">
                <h4>% contabilidad</h4>
                <strong>{data?.kpis?.contabilidadPct ?? '-'}</strong>
              </div>
              <div className="kpi">
                <h4>% fiscal</h4>
                <strong>{data?.kpis?.fiscalPct ?? '-'}</strong>
              </div>
            </div>
          </div>
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Actividad contable</h3>
            {!activityPoints.length ? (
              <div className="empty">Sin datos para graficar.</div>
            ) : (
              <KpiChart title="Asientos contables por año" points={activityPoints} variant="area" />
            )}
          </div>
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Ranking gestores (carga media)</h3>
            {!gestorCargaPoints.length ? (
              <div className="empty">Sin datos para graficar.</div>
            ) : (
              <KpiChart title="Carga media por gestor" points={gestorCargaPoints} variant="bar" />
            )}
          </div>
        </div>
      </Section>

      <Section title="4) Gestión y riesgo" subtitle="Operativa por gestor + lista de riesgos para priorizar.">
        <div className="grid">
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Gestores</h3>
            {!data?.gestores?.length ? (
              <div className="empty">Sin datos de gestores.</div>
            ) : (
              <table className="table">
                <thead>
                  <tr>
                    <th style={{ cursor: 'pointer' }} onClick={() => toggleGestorSort('gestor')}>
                      Gestor{sortMark('gestor')}
                    </th>
                    <th style={{ cursor: 'pointer' }} onClick={() => toggleGestorSort('total')}>
                      Total{sortMark('total')}
                    </th>
                    <th style={{ cursor: 'pointer' }} onClick={() => toggleGestorSort('active')}>
                      Activos{sortMark('active')}
                    </th>
                    <th style={{ cursor: 'pointer' }} onClick={() => toggleGestorSort('minuta')}>
                      Minuta media{sortMark('minuta')}
                    </th>
                    <th style={{ cursor: 'pointer' }} onClick={() => toggleGestorSort('carga')}>
                      Carga media{sortMark('carga')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {gestoresSorted.map((g: any) => (
                    <tr key={g.gestor || `${g.total}-${g.active}-${g.minuta}-${g.carga}`}>
                      <td>{g.gestor || '—'}</td>
                      <td>{g.total}</td>
                      <td>{g.active}</td>
                      <td>{g.minuta}</td>
                      <td>{g.carga}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Riesgos</h3>
            {!data?.risk?.length ? (
              <div className="empty">No se detectaron riesgos.</div>
            ) : (
              <div>
                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center', marginBottom: 10 }}>
                  <select value={riskGestor} onChange={(e) => setRiskGestor(e.target.value)}>
                    <option value="">Todos los gestores</option>
                    {gestorOptions.map((g: any) => (
                      <option key={g} value={g}>
                        {g}
                      </option>
                    ))}
                  </select>
                  <input
                    value={riskQuery}
                    onChange={(e) => setRiskQuery(e.target.value)}
                    placeholder="Buscar (cliente, CIF, motivo)"
                    style={{ minWidth: 240 }}
                  />
                  <span className="upload-hint">
                    {riskFiltered.length}/{riskRows.length} riesgos
                  </span>
                </div>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Cliente</th>
                      <th>CIF</th>
                      <th>Gestor</th>
                      <th>Motivo</th>
                    </tr>
                  </thead>
                  <tbody>
                    {riskFiltered.map((r: any, idx: number) => (
                      <tr key={`${r.cif}-${idx}`}>
                        <td>{r.cliente}</td>
                        <td>{r.cif}</td>
                        <td>{r.gestor}</td>
                        <td>{r.issues}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </Section>
    </div>
  )
}


