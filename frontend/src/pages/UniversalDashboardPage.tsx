import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import EChart from '../components/charts/EChart'
import {
  assistantChat,
  createUniversalViewForImport,
  downloadUniversalNormalizedCsvForImport,
  getUniversalViewDataForImport,
  getUniversalSummaryForImport,
  getUniversalRows,
  getReportContent,
  generateAdvisorReport,
  listUniversalImports,
  listUniversalViews,
  getUniversalSuggestionsForImport,
  downloadUniversalBuilderProblemsCsvForImport,
  previewUniversalXlsx,
  previewUniversalViewForImport,
  uploadUniversalImport,
  type AdvisorAction,
  type AssistantMessage,
  type UniversalImportDto,
  type UniversalAutoSuggestion,
  type UniversalChartData,
  type UniversalRows,
  type UniversalViewDto,
  type UniversalViewRequest,
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
  const queryClient = useQueryClient()
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
  const [showAllInsights, setShowAllInsights] = useState(false)
  const [showAllColumns, setShowAllColumns] = useState(false)
  const [activeImportId, setActiveImportId] = useState<number | null>(null) // null => último dataset

  useEffect(() => {
    if (!companyId) {
      setActiveImportId(null)
      return
    }
    try {
      const raw = window.localStorage.getItem(`universal.activeImportId.${companyId}`)
      if (!raw) {
        setActiveImportId(null)
        return
      }
      const n = Number(raw)
      setActiveImportId(Number.isFinite(n) ? n : null)
    } catch {
      setActiveImportId(null)
    }
  }, [companyId])

  const [builderType, setBuilderType] = useState<
    'TIME_SERIES' | 'CATEGORY_BAR' | 'KPI_CARDS' | 'SCATTER' | 'HEATMAP' | 'PIVOT_MONTHLY'
  >('TIME_SERIES')
  const [builderName, setBuilderName] = useState('')
  const [builderDateCol, setBuilderDateCol] = useState('')
  const [builderValueCol, setBuilderValueCol] = useState('')
  const [builderCatCol, setBuilderCatCol] = useState('')
  const [builderXCol, setBuilderXCol] = useState('')
  const [builderYCol, setBuilderYCol] = useState('')
  const [builderFilters, setBuilderFilters] = useState<Array<{ column: string; op: 'eq' | 'contains' | 'year_eq' | 'gt' | 'gte' | 'lt' | 'lte'; value: string }>>([
    { column: '', op: 'eq', value: '' }
  ])
  const [builderTopN, setBuilderTopN] = useState(8)
  const [builderMaxPoints, setBuilderMaxPoints] = useState(1500)
  const [builderAgg, setBuilderAgg] = useState<'sum' | 'avg'>('sum')
  const [builderPreview, setBuilderPreview] = useState<UniversalChartData | null>(null)
  const [builderLastRequest, setBuilderLastRequest] = useState<UniversalViewRequest | null>(null)
  const [builderLoading, setBuilderLoading] = useState(false)
  const [builderError, setBuilderError] = useState<string | null>(null)
  const [selectedViewId, setSelectedViewId] = useState<number | null>(null)

  const { data, error, refetch } = useQuery({
    queryKey: ['universal-summary', companyId, activeImportId ?? 'latest'],
    queryFn: () => getUniversalSummaryForImport(companyId as number, activeImportId),
    enabled: !!companyId
  })

  const { data: views } = useQuery({
    queryKey: ['universal-views', companyId],
    queryFn: () => listUniversalViews(companyId as number),
    enabled: !!companyId
  })

  const { data: imports } = useQuery({
    queryKey: ['universal-imports', companyId],
    queryFn: () => listUniversalImports(companyId as number),
    enabled: !!companyId
  })

  const importsList = (imports || []) as UniversalImportDto[]
  const activeImport = activeImportId ? importsList.find((i) => i.id === activeImportId) : null

  const { data: suggestions } = useQuery({
    queryKey: ['universal-suggestions', companyId, activeImportId ?? 'latest'],
    queryFn: () => getUniversalSuggestionsForImport(companyId as number, activeImportId),
    enabled: !!companyId
  })

  async function handleUpload() {
    if (!companyId || !file) return
    setUploading(true)
    setUploadError(null)
    setUploadOk(null)
    try {
      const opts = file.name.toLowerCase().endsWith('.xlsx')
        ? { sheetIndex: sheetIndex ?? xlsxPreview?.sheetIndex ?? undefined, headerRow: headerRow ?? xlsxPreview?.headerRow ?? undefined }
        : {}
      const res: any = await uploadUniversalImport(companyId, file, opts)
      const newId = Number(res?.importId)
      if (Number.isFinite(newId)) {
        setActiveImportId(newId)
        try {
          window.localStorage.setItem(`universal.activeImportId.${companyId}`, String(newId))
        } catch {}
      }
      setRowsPreview(null)
      setRowsError(null)
      setBuilderPreview(null)
      setBuilderError(null)
      await queryClient.invalidateQueries({ queryKey: ['universal-imports', companyId] })
      await refetch()
      await queryClient.invalidateQueries({ queryKey: ['universal-suggestions', companyId] })
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
  const numericColumns = columns.filter((c: any) => c.detectedType === 'number').slice(0, 2)
  const dateColumns = columns.filter((c: any) => c.detectedType === 'date').slice(0, 2)
  const categoricalColumns = columns.filter((c: any) => c.detectedType === 'text' && (c.topValues?.length || 0) > 0).slice(0, 6)
  const topCorrelations = correlations.slice(0, 5)

  const dateCols = columns.filter((c: any) => c.detectedType === 'date').map((c: any) => String(c.name))
  const numberCols = columns.filter((c: any) => c.detectedType === 'number').map((c: any) => String(c.name))
  const textCols = columns.filter((c: any) => c.detectedType === 'text').map((c: any) => String(c.name))
  const allCols = columns.map((c: any) => String(c.name))

  useEffect(() => {
    // When switching dataset, keep the UI consistent: previous column selections may no longer exist.
    setBuilderPreview(null)
    setBuilderError(null)
    setBuilderLastRequest(null)
    setBuilderName('')
    setBuilderDateCol('')
    setBuilderValueCol('')
    setBuilderCatCol('')
    setBuilderXCol('')
    setBuilderYCol('')
    setBuilderFilters([{ column: '', op: 'eq', value: '' }])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [companyId, activeImportId])

  useEffect(() => {
    if (!builderValueCol && numberCols.length) setBuilderValueCol(numberCols[0])
    if (!builderDateCol && dateCols.length) setBuilderDateCol(dateCols[0])
    if (!builderCatCol && textCols.length) setBuilderCatCol(textCols[0])
    if (!builderXCol && numberCols.length) setBuilderXCol(numberCols[0])
    if (!builderYCol && numberCols.length) setBuilderYCol(numberCols[1] ?? numberCols[0])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [companyId, columns?.length])

  const corrHeatmap = useMemo(() => {
    if (!correlations?.length) return null
    const cols = Array.from(
      new Set<string>(correlations.flatMap((c: any) => [String(c.columnA), String(c.columnB)]))
    ).slice(0, 10)
    if (cols.length < 2) return null
    const index = new Map(cols.map((n, i) => [n, i]))
    const data: Array<[number, number, number]> = []
    for (const c of correlations as any[]) {
      const a = index.get(String(c.columnA))
      const b = index.get(String(c.columnB))
      if (a === undefined || b === undefined) continue
      const v = Number(c.correlation ?? 0)
      data.push([a, b, v])
      data.push([b, a, v])
    }
    for (let i = 0; i < cols.length; i++) data.push([i, i, 1])
    return { cols, data }
  }, [correlations])

  const likelyBadHeaders = useMemo(() => {
    if (!columns?.length) return false
    const numericish = (name: any) => /^[0-9,.\-]+$/.test(String(name || '').trim())
    const n = columns.filter((c: any) => numericish(c.name)).length
    return columns.length >= 6 && n / columns.length >= 0.5
  }, [columns])

  const isCsv = !file ? true : file.name.toLowerCase().endsWith('.csv')
  const isXlsx = !file ? false : file.name.toLowerCase().endsWith('.xlsx')
  const isAllowed = !file ? true : isCsv || isXlsx

  const canPreviewXlsx = !!companyId && isXlsx && !!file

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
    const csv = await downloadUniversalNormalizedCsvForImport(companyId, activeImportId)
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
      const res = await getUniversalRows(companyId, limit, activeImportId)
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

  async function runBuilderPreview() {
    if (!companyId) return
    setBuilderLoading(true)
    setBuilderError(null)
    try {
      const filters = (builderFilters || []).filter((f) => !!f?.column && !!f?.op && !!f?.value)
      const body: UniversalViewRequest = {
        name: builderName || undefined,
        type: builderType,
        dateColumn: builderType === 'TIME_SERIES' || builderType === 'PIVOT_MONTHLY' ? builderDateCol : undefined,
        categoryColumn: builderType === 'CATEGORY_BAR' || builderType === 'PIVOT_MONTHLY' ? builderCatCol : undefined,
        valueColumn: builderValueCol || undefined,
        xColumn: builderType === 'SCATTER' || builderType === 'HEATMAP' ? builderXCol : undefined,
        yColumn: builderType === 'SCATTER' || builderType === 'HEATMAP' ? builderYCol : undefined,
        aggregation: builderAgg,
        filters: filters.length ? (filters as any) : undefined,
        topN: builderType === 'PIVOT_MONTHLY' || builderType === 'HEATMAP' ? builderTopN : undefined,
        maxPoints: builderType === 'SCATTER' ? builderMaxPoints : undefined
      }
      setBuilderLastRequest(body)
      const res = await previewUniversalViewForImport(companyId as number, body, activeImportId)
      setBuilderPreview(res)
    } catch (e: any) {
      setBuilderPreview(null)
      setBuilderError(e?.message || 'No se pudo previsualizar el dashboard.')
    } finally {
      setBuilderLoading(false)
    }
  }

  async function saveBuilderView() {
    if (!companyId) return
    setBuilderLoading(true)
    setBuilderError(null)
    try {
      const filters = (builderFilters || []).filter((f) => !!f?.column && !!f?.op && !!f?.value)
      const name =
        (builderName || '').trim() ||
        (builderType === 'TIME_SERIES'
          ? 'Serie temporal'
          : builderType === 'CATEGORY_BAR'
            ? 'Ranking categorías'
            : builderType === 'KPI_CARDS'
              ? 'KPIs'
              : builderType === 'SCATTER'
                ? 'Scatter'
                : builderType === 'HEATMAP'
                  ? 'Heatmap'
                  : 'Pivote mensual')
      const body: UniversalViewRequest = {
        name,
        type: builderType,
        dateColumn: builderType === 'TIME_SERIES' || builderType === 'PIVOT_MONTHLY' ? builderDateCol : undefined,
        categoryColumn: builderType === 'CATEGORY_BAR' || builderType === 'PIVOT_MONTHLY' ? builderCatCol : undefined,
        valueColumn: builderValueCol || undefined,
        xColumn: builderType === 'SCATTER' || builderType === 'HEATMAP' ? builderXCol : undefined,
        yColumn: builderType === 'SCATTER' || builderType === 'HEATMAP' ? builderYCol : undefined,
        aggregation: builderAgg,
        filters: filters.length ? (filters as any) : undefined,
        topN: builderType === 'PIVOT_MONTHLY' || builderType === 'HEATMAP' ? builderTopN : undefined,
        maxPoints: builderType === 'SCATTER' ? builderMaxPoints : undefined
      }
      const created = await createUniversalViewForImport(companyId as number, body, activeImportId)
      await queryClient.invalidateQueries({ queryKey: ['universal-views', companyId] })
      setSelectedViewId(created.id)
      toast.push({ tone: 'success', title: 'Dashboard', message: 'Plantilla guardada.' })
    } catch (e: any) {
      setBuilderError(e?.message || 'No se pudo guardar la plantilla.')
    } finally {
      setBuilderLoading(false)
    }
  }

  async function loadView(id: number) {
    if (!companyId) return
    setSelectedViewId(id)
    setBuilderLoading(true)
    setBuilderError(null)
    try {
      const res = await getUniversalViewDataForImport(companyId as number, id, activeImportId)
      setBuilderPreview(res)
    } catch (e: any) {
      setBuilderError(e?.message || 'No se pudo cargar el dashboard guardado.')
    } finally {
      setBuilderLoading(false)
    }
  }

  const canBuild =
    builderType === 'KPI_CARDS'
      ? true
      : builderType === 'SCATTER'
        ? !!builderXCol && !!builderYCol
        : builderType === 'HEATMAP'
          ? !!builderXCol && !!builderYCol && !!builderValueCol
          : builderType === 'PIVOT_MONTHLY'
            ? !!builderDateCol && !!builderCatCol && !!builderValueCol
            : builderType === 'TIME_SERIES'
              ? !!builderDateCol && !!builderValueCol
              : builderType === 'CATEGORY_BAR'
                ? !!builderCatCol && !!builderValueCol
                : false

  return (
    <div>
      <PageHeader
        title="Análisis universal"
        subtitle="Sube un CSV o XLSX y te enseño primero lo importante. El detalle técnico queda plegado."
        actions={
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
            <span className="badge">{plan}</span>
            {companyId ? (
              <div className="card soft" style={{ padding: 10, minWidth: 320 }}>
                <div className="upload-hint">Dataset activo</div>
                <div style={{ display: 'grid', gap: 6, marginTop: 6 }}>
                  <select
                    value={activeImportId ?? ''}
                    onChange={(e) => {
                      if (!companyId) return
                      const raw = String(e.target.value || '').trim()
                      const next = raw ? Number(raw) : null
                      setActiveImportId(raw && Number.isFinite(next) ? next : null)
                      try {
                        const key = `universal.activeImportId.${companyId}`
                        if (!raw) window.localStorage.removeItem(key)
                        else window.localStorage.setItem(key, String(next))
                      } catch {}
                      setRowsPreview(null)
                      setRowsError(null)
                      setBuilderPreview(null)
                      setBuilderError(null)
                    }}
                    title="Elige dataset (import) para construir dashboards"
                  >
                    <option value="">Último (auto)</option>
                    {importsList.slice(0, 20).map((imp) => (
                      <option key={imp.id} value={imp.id}>
                        #{imp.id} · {imp.filename}
                      </option>
                    ))}
                  </select>
                  <div className="upload-hint">
                    {activeImportId ? (
                      <>
                        {activeImport?.filename || `Import #${activeImportId}`} ·{' '}
                        {activeImport?.createdAt ? new Date(activeImport.createdAt).toLocaleString() : '—'}
                        {activeImport ? ` · ${activeImport.rowCount} filas · ${activeImport.columnCount} columnas` : ''}
                      </>
                    ) : summary?.filename ? (
                      <>
                        {summary.filename} · {summary.createdAt ? new Date(summary.createdAt).toLocaleString() : '—'} ·{' '}
                        {summary.rowCount} filas · {summary.columnCount} columnas
                      </>
                    ) : (
                      '—'
                    )}
                  </div>
                </div>
              </div>
            ) : null}
          </div>
        }
      />

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Crear dashboard (Auto → Guiado)</h3>
        <div className="upload-hint">
          Para ficheros con columnas arbitrarias: eliges 2–3 columnas y te genero un dashboard (y lo guardas como plantilla para próximos ficheros).
        </div>

        {!companyId ? (
          <div style={{ marginTop: 12 }}>
            <Alert tone="warning">Selecciona una empresa.</Alert>
          </div>
        ) : null}

        {(suggestions as UniversalAutoSuggestion[] | undefined)?.length ? (
          <div className="card soft" style={{ marginTop: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
              <div>
                <div style={{ fontWeight: 800 }}>Sugerencias AUTO</div>
                <div className="upload-hint">Te propongo 1–2 dashboards típicos según tus columnas. Crea con 1 click.</div>
              </div>
            </div>
            <div style={{ display: 'grid', gap: 10, marginTop: 10 }}>
              {(suggestions as UniversalAutoSuggestion[]).slice(0, 2).map((sug, idx) => (
                <div key={`${sug.title}-${idx}`} className="card" style={{ padding: 12 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
                    <div>
                      <div style={{ fontWeight: 800 }}>{sug.title}</div>
                      <div className="upload-hint">{sug.description}</div>
                    </div>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={async () => {
                          if (!companyId) return
                          try {
                            const res = await previewUniversalViewForImport(companyId as number, sug.request, activeImportId)
                            setBuilderPreview(res)
                            setBuilderLastRequest(sug.request)
                            toast.push({ tone: 'success', title: 'Vista previa', message: 'Preview cargada.' })
                          } catch (e: any) {
                            toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo previsualizar.' })
                          }
                        }}
                      >
                        Previsualizar
                      </Button>
                      <Button
                        size="sm"
                        onClick={async () => {
                          if (!companyId) return
                          try {
                            const created = await createUniversalViewForImport(companyId as number, {
                              ...sug.request,
                              name: (sug.request?.name || sug.title || 'Dashboard').trim()
                            }, activeImportId)
                            await queryClient.invalidateQueries({ queryKey: ['universal-views', companyId] })
                            setSelectedViewId(created.id)
                            toast.push({ tone: 'success', title: 'Dashboard', message: 'Plantilla creada.' })
                          } catch (e: any) {
                            toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo crear.' })
                          }
                        }}
                      >
                        Crear con 1 click
                      </Button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ) : null}

        <div className="upload-row" style={{ marginTop: 12, alignItems: 'flex-end' }}>
          <label style={{ display: 'grid', gap: 6 }}>
            <span className="upload-hint">Tipo</span>
            <select value={builderType} onChange={(e) => setBuilderType(e.target.value as any)}>
              <option value="TIME_SERIES">Serie temporal (fecha → valor)</option>
              <option value="CATEGORY_BAR">Ranking por categoría (texto → valor)</option>
              <option value="KPI_CARDS">KPIs (count/sum/avg) + filtro</option>
              <option value="SCATTER">Scatter (X vs Y)</option>
              <option value="HEATMAP">Heatmap simple (X × Y)</option>
              <option value="PIVOT_MONTHLY">Tabla pivote (categoría × mes)</option>
            </select>
          </label>
          <label style={{ display: 'grid', gap: 6, minWidth: 220 }}>
            <span className="upload-hint">Nombre (opcional)</span>
            <input value={builderName} onChange={(e) => setBuilderName(e.target.value)} placeholder="Ej: Ventas por mes" />
          </label>
          <label style={{ display: 'grid', gap: 6 }}>
            <span className="upload-hint">Agregación</span>
            <select value={builderAgg} onChange={(e) => setBuilderAgg(e.target.value as any)}>
              <option value="sum">Suma</option>
              <option value="avg">Media</option>
            </select>
          </label>
        </div>

        <div className="upload-row" style={{ marginTop: 10, alignItems: 'flex-end', flexWrap: 'wrap' }}>
          {builderType === 'TIME_SERIES' || builderType === 'PIVOT_MONTHLY' ? (
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Fecha</span>
              <select value={builderDateCol} onChange={(e) => setBuilderDateCol(e.target.value)}>
                <option value="">—</option>
                {dateCols.map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {builderType === 'CATEGORY_BAR' || builderType === 'PIVOT_MONTHLY' ? (
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Categoría</span>
              <select value={builderCatCol} onChange={(e) => setBuilderCatCol(e.target.value)}>
                <option value="">—</option>
                {textCols.map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {builderType === 'SCATTER' || builderType === 'HEATMAP' ? (
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">X</span>
              <select value={builderXCol} onChange={(e) => setBuilderXCol(e.target.value)}>
                <option value="">—</option>
                {(builderType === 'HEATMAP' ? textCols : numberCols).map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {builderType === 'SCATTER' || builderType === 'HEATMAP' ? (
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Y</span>
              <select value={builderYCol} onChange={(e) => setBuilderYCol(e.target.value)}>
                <option value="">—</option>
                {(builderType === 'HEATMAP' ? textCols : numberCols).map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {builderType !== 'SCATTER' ? (
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">{builderType === 'KPI_CARDS' ? 'Valor (opcional)' : 'Valor'}</span>
              <select value={builderValueCol} onChange={(e) => setBuilderValueCol(e.target.value)}>
                <option value="">—</option>
                {numberCols.map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {builderType === 'PIVOT_MONTHLY' || builderType === 'HEATMAP' ? (
            <label style={{ display: 'grid', gap: 6, width: 90 }}>
              <span className="upload-hint">Top N</span>
              <input
                type="number"
                min={1}
                max={30}
                value={builderTopN}
                onChange={(e) => setBuilderTopN(Number(e.target.value || 0))}
              />
            </label>
          ) : null}

          {builderType === 'SCATTER' ? (
            <label style={{ display: 'grid', gap: 6, width: 110 }}>
              <span className="upload-hint">Max puntos</span>
              <input
                type="number"
                min={50}
                max={10000}
                value={builderMaxPoints}
                onChange={(e) => setBuilderMaxPoints(Number(e.target.value || 0))}
              />
            </label>
          ) : null}

          <div className="card soft" style={{ padding: 12, minWidth: 420 }}>
            <div className="upload-hint" style={{ marginBottom: 8 }}>
              Filtros guardados (AND)
            </div>
            <div style={{ display: 'grid', gap: 8 }}>
              {(builderFilters || []).map((f, idx) => (
                <div key={`f-${idx}`} className="upload-row" style={{ alignItems: 'flex-end', flexWrap: 'wrap' }}>
                  <label style={{ display: 'grid', gap: 6, minWidth: 180 }}>
                    <span className="upload-hint">Columna</span>
                    <select
                      value={f.column}
                      onChange={(e) => {
                        const v = e.target.value
                        setBuilderFilters((arr) => arr.map((it, i) => (i === idx ? { ...it, column: v } : it)))
                      }}
                    >
                      <option value="">(seleccionar)</option>
                      {allCols.map((c: string) => (
                        <option key={`${c}-${idx}`} value={c}>
                          {c}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label style={{ display: 'grid', gap: 6, width: 130 }}>
                    <span className="upload-hint">Op</span>
                    <select
                      value={f.op}
                      onChange={(e) => {
                        const v = e.target.value as any
                        setBuilderFilters((arr) => arr.map((it, i) => (i === idx ? { ...it, op: v } : it)))
                      }}
                    >
                      <option value="eq">=</option>
                      <option value="contains">contiene</option>
                      <option value="year_eq">año =</option>
                      <option value="gt">&gt;</option>
                      <option value="gte">&gt;=</option>
                      <option value="lt">&lt;</option>
                      <option value="lte">&lt;=</option>
                    </select>
                  </label>
                  <label style={{ display: 'grid', gap: 6, minWidth: 150 }}>
                    <span className="upload-hint">Valor</span>
                    <input
                      value={f.value}
                      onChange={(e) => {
                        const v = e.target.value
                        setBuilderFilters((arr) => arr.map((it, i) => (i === idx ? { ...it, value: v } : it)))
                      }}
                      placeholder={f.op === 'year_eq' ? '2025' : 'Ej: Ventas'}
                    />
                  </label>
                  <button
                    className="badge"
                    onClick={() => setBuilderFilters((arr) => arr.filter((_, i) => i !== idx))}
                    disabled={(builderFilters || []).length <= 1}
                    title="Quitar filtro"
                  >
                    Quitar
                  </button>
                </div>
              ))}
              <div>
                <button
                  className="badge"
                  onClick={() => setBuilderFilters((arr) => [...arr, { column: '', op: 'eq', value: '' }])}
                  title="Añadir filtro"
                >
                  + Añadir filtro
                </button>
              </div>
            </div>
          </div>

          <Button variant="secondary" size="sm" onClick={runBuilderPreview} disabled={!companyId || !canBuild || builderLoading}>
            Previsualizar
          </Button>
          <Button size="sm" onClick={saveBuilderView} disabled={!companyId || !canBuild || builderLoading}>
            Guardar plantilla
          </Button>

          {views?.length ? (
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Plantillas</span>
              <select
                value={selectedViewId ?? ''}
                onChange={(e) => {
                  const v = e.target.value ? Number(e.target.value) : null
                  if (v) loadView(v)
                }}
              >
                <option value="">(seleccionar)</option>
                {(views || []).map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.name}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
        </div>

        {builderError ? (
          <div style={{ marginTop: 12 }}>
            <Alert tone="danger">{builderError}</Alert>
          </div>
        ) : null}

        {builderPreview ? (
          <div className="card" style={{ marginTop: 12 }}>
            <h3 style={{ marginTop: 0 }}>Vista previa</h3>
            {String(builderPreview.type || '').toUpperCase() === 'KPI_CARDS' ? (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(160px,1fr))', gap: 12 }}>
                {builderPreview.labels.map((k, idx) => (
                  <div key={`${k}-${idx}`} className="card soft">
                    <div className="upload-hint" style={{ textTransform: 'uppercase' }}>
                      {k}
                    </div>
                    <div style={{ fontSize: 22, fontWeight: 700, marginTop: 6 }}>
                      {String(((builderPreview.series as any)?.[0]?.data || [])[idx] ?? '—')}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <EChart
                style={{ height: 320 }}
                option={
                  String(builderPreview.type || '').toUpperCase() === 'SCATTER'
                    ? ({
                        tooltip: { trigger: 'item' },
                        xAxis: { type: 'value', name: (builderPreview.meta as any)?.xColumn || 'X' },
                        yAxis: { type: 'value', name: (builderPreview.meta as any)?.yColumn || 'Y' },
                        series: [
                          {
                            name: (builderPreview.series as any)?.[0]?.name || 'Puntos',
                            type: 'scatter',
                            symbolSize: 6,
                            data: (builderPreview.series as any)?.[0]?.data || []
                          }
                        ]
                      } as any)
                    : String(builderPreview.type || '').toUpperCase() === 'HEATMAP'
                      ? (() => {
                          const pts = ((builderPreview.series as any)?.[0]?.data || []) as any[]
                          const max =
                            pts.reduce((acc, p) => Math.max(acc, Number(Array.isArray(p) ? p[2] : 0) || 0), 0) || 1
                          return {
                            tooltip: { position: 'top' },
                            grid: { height: '70%', top: 30 },
                            xAxis: { type: 'category', data: builderPreview.labels, splitArea: { show: true } },
                            yAxis: { type: 'category', data: (builderPreview.meta as any)?.yLabels || [], splitArea: { show: true } },
                            visualMap: { min: 0, max, calculable: true, orient: 'horizontal', left: 'center', bottom: 0 },
                            series: [
                              {
                                name: (builderPreview.series as any)?.[0]?.name || 'Heatmap',
                                type: 'heatmap',
                                data: pts,
                                emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0, 0, 0, 0.5)' } }
                              }
                            ]
                          } as any
                        })()
                      : String(builderPreview.type || '').toUpperCase() === 'PIVOT_MONTHLY'
                        ? ({
                            tooltip: { trigger: 'axis' },
                            legend: { type: 'scroll' },
                            xAxis: { type: 'category', data: builderPreview.labels },
                            yAxis: { type: 'value' },
                            series: (builderPreview.series as any[]).map((s) => ({
                              name: s?.name,
                              type: 'bar',
                              stack: 'total',
                              data: s?.data || []
                            }))
                          } as any)
                        : builderPreview.type === 'CATEGORY_BAR'
                          ? ({
                              tooltip: { trigger: 'axis' },
                              xAxis: { type: 'category', data: builderPreview.labels },
                              yAxis: { type: 'value' },
                              series: [
                                {
                                  name: (builderPreview.series as any)?.[0]?.name || 'Valor',
                                  type: 'bar',
                                  data: (builderPreview.series as any)?.[0]?.data || []
                                }
                              ]
                            } as any)
                          : ({
                              tooltip: { trigger: 'axis' },
                              xAxis: { type: 'category', data: builderPreview.labels },
                              yAxis: { type: 'value' },
                              series: [
                                {
                                  name: (builderPreview.series as any)?.[0]?.name || 'Valor',
                                  type: 'line',
                                  smooth: true,
                                  data: (builderPreview.series as any)?.[0]?.data || []
                                }
                              ]
                            } as any)
                }
              />
            )}
            {builderPreview.meta ? (
              <div className="upload-hint" style={{ marginTop: 8 }}>
                Filas usadas: {(builderPreview.meta as any).rowsUsed ?? '—'} • Agregación: {(builderPreview.meta as any).aggregation ?? '—'}
              </div>
            ) : null}
            {builderPreview.meta &&
            ((builderPreview.meta as any).badDateCount ||
              (builderPreview.meta as any).badNumberCount ||
              (builderPreview.meta as any).badXCount ||
              (builderPreview.meta as any).badYCount) ? (
              <div className="upload-hint" style={{ marginTop: 6 }}>
                Inválidos: fecha={(builderPreview.meta as any).badDatePct ?? 0}% • num={(builderPreview.meta as any).badNumberPct ?? 0}% • X={(builderPreview.meta as any).badXPct ?? 0}% • Y={(builderPreview.meta as any).badYPct ?? 0}%
              </div>
            ) : null}
            {builderLastRequest ? (
              <div style={{ marginTop: 10, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={async () => {
                    if (!companyId) return
                    try {
                      const blob = await downloadUniversalBuilderProblemsCsvForImport(companyId as number, builderLastRequest, 120, activeImportId)
                      const url = URL.createObjectURL(blob)
                      const a = document.createElement('a')
                      a.href = url
                      a.download = `universal-problemas-${new Date().toISOString().slice(0, 10)}.csv`
                      document.body.appendChild(a)
                      a.click()
                      a.remove()
                      URL.revokeObjectURL(url)
                    } catch (e: any) {
                      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo descargar.' })
                    }
                  }}
                >
                  Descargar filas problemáticas
                </Button>
              </div>
            ) : null}
            {Array.isArray((builderPreview.meta as any)?.warnings) && (builderPreview.meta as any).warnings.length ? (
              <div style={{ marginTop: 10 }}>
                <Alert tone="warning" title="Avisos">
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {(builderPreview.meta as any).warnings.slice(0, 6).map((w: any, idx: number) => (
                      <li key={`${idx}`}>{String(w)}</li>
                    ))}
                  </ul>
                </Alert>
              </div>
            ) : null}
          </div>
        ) : null}
      </div>

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
          {file && isXlsx ? (
            <details className="card soft" style={{ marginTop: 12 }}>
              <summary className="upload-hint" style={{ cursor: 'pointer' }}>
                Ajustes XLSX (si las columnas salen raras)
              </summary>
              <div style={{ marginTop: 10 }}>
                {xlsxLoading ? <div className="upload-hint">Detectando estructura del Excel…</div> : null}
                {!!xlsxPreview?.sheets?.length ? (
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
                ) : null}
                {!!xlsxPreview?.headers?.length ? (
                  <div style={{ marginTop: 10 }} className="upload-hint">
                    Headers detectados: {xlsxPreview.headers.slice(0, 8).join(' · ')}
                    {xlsxPreview.headers.length > 8 ? '…' : ''}
                  </div>
                ) : null}
                {!!xlsxPreview?.sampleRows?.length ? (
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
                ) : null}
              </div>
            </details>
          ) : null}
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

      {likelyBadHeaders ? (
        <div className="section">
          <Alert tone="warning" title="El Excel parece mal interpretado">
            Los nombres de columna parecen números (posible fila de datos en vez de encabezado). Abre “Ajustes XLSX” y prueba a cambiar la fila de
            encabezado (o exporta a CSV con títulos).
          </Alert>
        </div>
      ) : null}

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Insights & asesoramiento</h3>
          {!insights.length ? (
            <div className="empty">Sin insights aun. Sube un archivo.</div>
          ) : (
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {insights.slice(0, showAllInsights ? insights.length : 3).map((it: any, idx: number) => (
                <li key={`${it.title}-${idx}`} style={{ marginBottom: 8 }}>
                  <strong>{it.title}:</strong> {it.message}
                </li>
              ))}
            </ul>
          )}
          {insights.length > 3 ? (
            <div style={{ marginTop: 10 }}>
              <Button size="sm" variant="ghost" onClick={() => setShowAllInsights((v) => !v)}>
                {showAllInsights ? 'Ver menos' : `Ver todos (${insights.length})`}
              </Button>
            </div>
          ) : null}
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

      <div className="section">
        <details className="card">
          <summary className="mini-row" style={{ cursor: 'pointer', marginTop: 0 }}>
            <strong>Columnas</strong>
            <span className="badge">{columns.length || 0}</span>
          </summary>
          {!columns.length ? (
            <div className="empty" style={{ marginTop: 12 }}>
              Aún no hay análisis. Sube un CSV/XLSX.
            </div>
          ) : (
            <>
              <div style={{ marginTop: 12 }}>
                <Button size="sm" variant="ghost" onClick={() => setShowAllColumns((v) => !v)}>
                  {showAllColumns ? 'Ver menos' : `Ver todas (${columns.length})`}
                </Button>
              </div>
              <div style={{ marginTop: 12, overflow: 'auto' }}>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Columna</th>
                      <th>Tipo</th>
                      <th>Nulos</th>
                      <th>Únicos</th>
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
                    {(showAllColumns ? columns : columns.slice(0, 12)).map((c: any) => (
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
              </div>
            </>
          )}
        </details>
      </div>

      <div className="section">
        <details className="card">
          <summary className="mini-row" style={{ cursor: 'pointer', marginTop: 0 }}>
            <strong>Gráficos</strong>
            <span className="upload-hint">distribuciones y fechas</span>
          </summary>
          <div className="grid" style={{ marginTop: 12 }}>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Distribuciones numéricas</h3>
              {!numericColumns.length ? (
                <div className="empty">No se detectaron columnas numéricas.</div>
              ) : (
                <div className="grid">
                  {numericColumns.map((c: any) => (
                    <div key={`${c.name}-hist`} className="kpi">
                      <h4>{c.name}</h4>
                      {!c.histogram?.length ? (
                        <span className="badge">sin histograma</span>
                      ) : (
                        <EChart
                          style={{ height: 220 }}
                          option={{
                            xAxis: {
                              type: 'category',
                              data: (c.histogram || []).map((b: any) => String(b.label)),
                              axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)' } },
                              axisLabel: { color: 'rgba(226, 232, 240, 0.65)', interval: 0, rotate: 22 }
                            },
                            yAxis: {
                              type: 'value',
                              axisLine: { show: false },
                              splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.12)' } },
                              axisLabel: { color: 'rgba(226, 232, 240, 0.65)' }
                            },
                            series: [
                              {
                                type: 'bar',
                                data: (c.histogram || []).map((b: any) => Number(b.count || 0)),
                                barMaxWidth: 26,
                                itemStyle: { color: 'rgba(96, 165, 250, 0.78)' }
                              }
                            ]
                          }}
                        />
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div className="card soft">
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
                        <EChart
                          style={{ height: 240 }}
                          option={{
                            xAxis: {
                              type: 'category',
                              data: (c.dateSeries || []).map((d: any) => String(d.label)),
                              axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)' } },
                              axisLabel: { color: 'rgba(226, 232, 240, 0.65)' }
                            },
                            yAxis: {
                              type: 'value',
                              axisLine: { show: false },
                              splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.12)' } },
                              axisLabel: { color: 'rgba(226, 232, 240, 0.65)' }
                            },
                            series: [
                              {
                                name: 'Filas',
                                type: 'line',
                                smooth: true,
                                symbol: 'circle',
                                symbolSize: 6,
                                data: (c.dateSeries || []).map((d: any) => Number(d.count || 0)),
                                itemStyle: { color: 'rgba(20, 184, 166, 0.95)' },
                                lineStyle: { width: 3 },
                                areaStyle: { color: 'rgba(20, 184, 166, 0.12)' }
                              }
                            ]
                          }}
                        />
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </details>
      </div>

      <div className="section">
        <details className="card">
          <summary className="mini-row" style={{ cursor: 'pointer', marginTop: 0 }}>
            <strong>Relaciones y categorías</strong>
            <span className="upload-hint">valores frecuentes y correlaciones</span>
          </summary>
          <div className="grid" style={{ marginTop: 12 }}>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Top valores (categorías)</h3>
              {!categoricalColumns.length ? (
                <div className="empty">No se detectaron columnas categóricas.</div>
              ) : (
                <div className="grid">
                  {categoricalColumns.map((c: any) => (
                    <div key={`${c.name}-top`} className="kpi">
                      <h4>{c.name}</h4>
                      <EChart
                        style={{ height: 220 }}
                        option={{
                          xAxis: {
                            type: 'value',
                            axisLine: { show: false },
                            splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.12)' } },
                            axisLabel: { color: 'rgba(226, 232, 240, 0.65)' }
                          },
                          yAxis: {
                            type: 'category',
                            data: (c.topValues || [])
                              .slice(0, 8)
                              .map((v: any) => String(v.value).slice(0, 26))
                              .reverse(),
                            axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)' } },
                            axisLabel: { color: 'rgba(226, 232, 240, 0.7)' }
                          },
                          series: [
                            {
                              type: 'bar',
                              data: (c.topValues || [])
                                .slice(0, 8)
                                .map((v: any) => Number(v.count || 0))
                                .reverse(),
                              barMaxWidth: 22,
                              itemStyle: { color: 'rgba(20, 184, 166, 0.78)' }
                            }
                          ]
                        }}
                      />
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Correlaciones (numéricas)</h3>
              {!correlations.length ? (
                <div className="empty">
                  {plan === 'BRONZE' ? 'Correlaciones disponibles en plan GOLD o superior.' : 'No hay correlaciones disponibles.'}
                </div>
              ) : (
                <div>
                  {corrHeatmap ? (
                    <div style={{ marginBottom: 12 }}>
                      <EChart
                        style={{ height: 320 }}
                        option={{
                          tooltip: {
                            trigger: 'item',
                            formatter: (p: any) => {
                              const a = corrHeatmap.cols?.[p.value?.[0]] ?? ''
                              const b = corrHeatmap.cols?.[p.value?.[1]] ?? ''
                              const v = Number(p.value?.[2] ?? 0)
                              return `${a} vs ${b}<br/>${v.toFixed(3)}`
                            }
                          },
                          grid: { left: 60, right: 18, top: 18, bottom: 58 },
                          xAxis: {
                            type: 'category',
                            data: corrHeatmap.cols,
                            axisLabel: { color: 'rgba(226, 232, 240, 0.65)', rotate: 35 }
                          },
                          yAxis: {
                            type: 'category',
                            data: corrHeatmap.cols,
                            axisLabel: { color: 'rgba(226, 232, 240, 0.65)' }
                          },
                          visualMap: {
                            min: -1,
                            max: 1,
                            calculable: false,
                            orient: 'horizontal',
                            left: 'center',
                            bottom: 6,
                            textStyle: { color: 'rgba(226, 232, 240, 0.75)' },
                            inRange: { color: ['rgba(239, 68, 68, 0.9)', 'rgba(15, 23, 42, 0.92)', 'rgba(34, 197, 94, 0.9)'] }
                          },
                          series: [
                            {
                              type: 'heatmap',
                              data: corrHeatmap.data,
                              emphasis: { itemStyle: { borderColor: 'rgba(148, 163, 184, 0.6)', borderWidth: 1 } }
                            }
                          ]
                        }}
                      />
                      <div className="upload-hint" style={{ marginTop: 6 }}>
                        Matriz de correlación (subconjunto). Valores cerca de 1/-1 implican relación fuerte.
                      </div>
                    </div>
                  ) : null}
                  <div className="insight-grid">
                    {topCorrelations.map((c: any, idx: number) => (
                      <div key={`${c.columnA}-${c.columnB}-${idx}`} className="kpi">
                        <h4>
                          {c.columnA} vs {c.columnB}
                        </h4>
                        <strong>{c.correlation?.toFixed ? c.correlation.toFixed(3) : c.correlation}</strong>
                      </div>
                    ))}
                  </div>
                  <details style={{ marginTop: 12 }}>
                    <summary className="upload-hint" style={{ cursor: 'pointer' }}>
                      Ver tabla completa
                    </summary>
                    <div style={{ marginTop: 10, overflow: 'auto' }}>
                      <table className="table">
                        <thead>
                          <tr>
                            <th>Columna A</th>
                            <th>Columna B</th>
                            <th>Correlación</th>
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
                  </details>
                </div>
              )}
            </div>
          </div>
        </details>
      </div>
    </div>
  )
}
