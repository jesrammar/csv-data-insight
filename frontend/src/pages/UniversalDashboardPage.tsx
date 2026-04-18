import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import EChart from '../components/charts/EChart'
import ChartNarrative from '../components/charts/ChartNarrative'
import LineagePanel from '../components/charts/LineagePanel'
import {
  assistantChat,
  createUniversalViewForImport,
  downloadUniversalNormalizedCsvForImport,
  getAlerts,
  getUniversalLineage,
  getUniversalQuality,
  getUniversalEvidenceForImport,
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
  type UniversalEvidenceDto,
  type UniversalImportQualityDto,
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
import { buildUniversalChartNarrative } from '../utils/universalChartNarrative'

function formatPeriod(date: Date) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

function lastMonths(count: number) {
  const months: string[] = []
  const today = new Date()
  for (let i = count - 1; i >= 0; i--) {
    const d = new Date(today.getFullYear(), today.getMonth() - i, 1)
    months.push(formatPeriod(d))
  }
  return months
}

export default function UniversalDashboardPage() {
  const { id: companyId, plan } = useCompanySelection()
  const hasPlatinum = plan === 'PLATINUM'
  const canUseEvidence = plan === 'GOLD' || plan === 'PLATINUM'
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()
  const datasetRef = useRef<HTMLDivElement | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [uploadOk, setUploadOk] = useState<string | null>(null)
  const [xlsxPreview, setXlsxPreview] = useState<UniversalXlsxPreview | null>(null)
  const [xlsxLoading, setXlsxLoading] = useState(false)
  const [sheetIndex, setSheetIndex] = useState<number | null>(null)
  const [headerRow, setHeaderRow] = useState<number | null>(null)
  const [assistantMessages, setAssistantMessages] = useState<AssistantMessage[]>([
    { role: 'assistant', content: 'Soy tu Assistant (reglas) en PLATINUM. Dime tu objetivo (margen, costes, caja o crecimiento) y te propongo un plan 30/60/90 dias con evidencias.' }
  ])
  const [assistantInput, setAssistantInput] = useState('')
  const [assistantLoading, setAssistantLoading] = useState(false)
  const [assistantActions, setAssistantActions] = useState<AdvisorAction[]>([])
  const [assistantPrompts, setAssistantPrompts] = useState<string[]>([])
  const [assistantQuestions, setAssistantQuestions] = useState<string[]>([])
  const [assistantDisclosure, setAssistantDisclosure] = useState('')
  const [rowsPreview, setRowsPreview] = useState<UniversalRows | null>(null)
  const [rowsLoading, setRowsLoading] = useState(false)
  const [rowsError, setRowsError] = useState<string | null>(null)
  const [showAllInsights, setShowAllInsights] = useState(false)
  const [showAllColumns, setShowAllColumns] = useState(false)
  const [xfCategory, setXfCategory] = useState<string | null>(null)
  const [xfMonth, setXfMonth] = useState<string | null>(null)
  const [xfDateCol, setXfDateCol] = useState<string>('')
  const [xfNumCol, setXfNumCol] = useState<string>('')
  const [xfCatCol, setXfCatCol] = useState<string>('')
  const [activeImportId, setActiveImportId] = useState<number | null>(null) // null => ultimo dataset

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

  useEffect(() => {
    setXfCategory(null)
    setXfMonth(null)
    setXfDateCol('')
    setXfNumCol('')
    setXfCatCol('')
  }, [companyId, activeImportId])

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
  const [builderFocusLabel, setBuilderFocusLabel] = useState('')
  const [builderEvidence, setBuilderEvidence] = useState<UniversalEvidenceDto | null>(null)
  const [builderEvidenceLoading, setBuilderEvidenceLoading] = useState(false)
  const [builderEvidenceError, setBuilderEvidenceError] = useState<string | null>(null)

  useEffect(() => {
    const labels = builderPreview?.labels || []
    setBuilderFocusLabel(labels.length ? String(labels[labels.length - 1] || '') : '')
    setBuilderEvidence(null)
    setBuilderEvidenceError(null)
  }, [builderPreview?.labels?.join('|')])

  const builderNarrative = useMemo(() => buildUniversalChartNarrative(builderPreview, builderFocusLabel), [builderPreview, builderFocusLabel])

  async function loadBuilderEvidence() {
    if (!companyId || !builderLastRequest) return
    const focus = String(builderFocusLabel || '').trim()
    if (!focus) {
      toast.push({ tone: 'warning', title: 'Evidencia', message: 'Selecciona un punto/etiqueta del grafico.' })
      return
    }
    setBuilderEvidenceLoading(true)
    setBuilderEvidenceError(null)
    try {
      const res = await getUniversalEvidenceForImport(companyId as number, builderLastRequest, focus, 40, activeImportId)
      setBuilderEvidence(res)
      if (!res?.rows?.length) {
        toast.push({ tone: 'warning', title: 'Evidencia', message: 'No hay filas para ese punto (o hay datos invalidos).'})
      } else {
        toast.push({ tone: 'success', title: 'Evidencia', message: `Filas: ${res.rows.length}.` })
      }
    } catch (e: any) {
      const msg = e?.message || 'No se pudo cargar evidencia.'
      setBuilderEvidence(null)
      setBuilderEvidenceError(msg)
      toast.push({ tone: 'danger', title: 'Evidencia', message: msg })
    } finally {
      setBuilderEvidenceLoading(false)
    }
  }

  function downloadEvidenceCsv(ev: UniversalEvidenceDto) {
    const headers = Array.isArray(ev.headers) ? ev.headers : []
    const rows = Array.isArray(ev.rows) ? ev.rows : []
    const esc = (s: any) => {
      const v = String(s ?? '')
      const needs = v.includes('"') || v.includes(',') || v.includes('\n') || v.includes('\r')
      const q = v.replace(/"/g, '""')
      return needs ? `"${q}"` : q
    }
    const csv = [headers.map(esc).join(','), ...rows.map((r) => (Array.isArray(r) ? r : []).map(esc).join(','))].join('\n')
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `universal-evidencia-${new Date().toISOString().slice(0, 10)}.csv`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  useEffect(() => {
    if (plan !== 'BRONZE') return
    if (builderType === 'SCATTER' || builderType === 'HEATMAP' || builderType === 'PIVOT_MONTHLY') {
      setBuilderType('TIME_SERIES')
    }
  }, [plan, builderType])
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

  const activityMonths = useMemo(() => lastMonths(hasPlatinum ? 12 : 6), [hasPlatinum])
  const activityFrom = activityMonths[0]
  const activityTo = activityMonths[activityMonths.length - 1]

  const { data: alertsByMonth } = useQuery({
    queryKey: ['universal-alerts-by-month', companyId, activityFrom, activityTo],
    queryFn: async () => {
      if (!companyId) return {} as Record<string, any[]>
      const entries = await Promise.all(
        activityMonths.map(async (p) => {
          try {
            const res = await getAlerts(companyId as number, p)
            return [p, Array.isArray(res) ? res : []] as const
          } catch {
            return [p, []] as const
          }
        })
      )
      return Object.fromEntries(entries) as Record<string, any[]>
    },
    enabled: !!companyId
  })

  const importsByMonth = useMemo(() => {
    const map = new Map<string, UniversalImportDto[]>()
    for (const imp of importsList) {
      const p = String(imp?.createdAt || '').slice(0, 7)
      if (!p) continue
      if (!map.has(p)) map.set(p, [])
      map.get(p)!.push(imp)
    }
    for (const [p, arr] of map.entries()) {
      arr.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
      map.set(p, arr)
    }
    return map
  }, [importsList])

  const importCounts = activityMonths.map((p) => (importsByMonth.get(p) || []).length)
  const alertCounts = activityMonths.map((p) => ((alertsByMonth as any)?.[p]?.length || 0) as number)

  const lastIdx = Math.max(0, activityMonths.length - 1)
  const impLast = importCounts[lastIdx] ?? 0
  const impPrev = lastIdx > 0 ? importCounts[lastIdx - 1] : null
  const impDelta = impPrev == null ? null : impLast - impPrev

  const alertLast = alertCounts[lastIdx] ?? 0
  const alertPrev = lastIdx > 0 ? alertCounts[lastIdx - 1] : null
  const alertDelta = alertPrev == null ? null : alertLast - alertPrev

  const [activityFocus, setActivityFocus] = useState<string | null>(activityMonths[activityMonths.length - 1] || null)

  useEffect(() => {
    setActivityFocus(activityMonths[activityMonths.length - 1] || null)
  }, [activityMonths.join('|')])

  const activityNarrative = useMemo(() => {
    if (!activityFocus) return { see: [], why: [], todo: [] }
    const idx = activityMonths.indexOf(activityFocus)
    const safeIdx = idx >= 0 ? idx : Math.max(0, activityMonths.length - 1)
    const month = activityMonths[safeIdx] || activityFocus
    const imp = importCounts[safeIdx] ?? 0
    const al = alertCounts[safeIdx] ?? 0
    const impPrevLocal = safeIdx > 0 ? importCounts[safeIdx - 1] : null
    const alPrevLocal = safeIdx > 0 ? alertCounts[safeIdx - 1] : null

    const see: string[] = []
    see.push(`${month}: ${imp} import${imp === 1 ? '' : 's'} y ${al} alerta${al === 1 ? '' : 's'}.`)
    if (impPrevLocal != null) see.push(`Delta imports vs mes anterior: ${imp - impPrevLocal >= 0 ? '+' : ''}${imp - impPrevLocal}.`)

    const why: string[] = []
    if (imp === 0) why.push('Sin import ese mes: no hay dataset nuevo para recalcular insights/alertas.')
    else why.push('Mas imports suele implicar cambios de datos (y posibles cambios en insights).')
    if (al > 0) why.push('Alertas indican anomalias o riesgos que conviene revisar antes de entregar informe.')

    const todo: string[] = []
    if (imp === 0) todo.push('Sube un dataset (CSV/XLSX) para ese mes o revisa el periodo de trabajo.')
    if (al > 0) todo.push('Abre Alertas y revisa severidad/causa raiz; anota acciones para el cliente.')
    else todo.push('Si el mes esta "limpio", puedes generar informe y compararlo con meses anteriores.')
    if (alPrevLocal != null && al > alPrevLocal) todo.push('Si suben alertas, revisa cambios de columnas/formato o reglas de calidad.')

    return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 3) }
  }, [activityFocus, activityMonths, alertCounts, importCounts])

  const activityMarkers = activityMonths
    .flatMap((p) => {
      const out: any[] = []
      const a = ((alertsByMonth as any)?.[p]?.length || 0) as number
      const i = (importsByMonth.get(p) || []).length
      if (a > 0) out.push({ label: p, name: 'A', kind: 'alert', text: `${a} alerta${a === 1 ? '' : 's'}` })
      if (i > 0) out.push({ label: p, name: 'I', kind: 'import', text: `${i} import${i === 1 ? '' : 's'}` })
      return out
    })
    .slice(0, 18)

  const activityOption = useMemo(() => {
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['Imports', 'Alertas'] },
      xAxis: { type: 'category', data: activityMonths },
      yAxis: { type: 'value' },
      series: [
        {
          name: 'Imports',
          type: 'bar',
          data: importCounts,
          barMaxWidth: 26,
          markPoint: activityMarkers.length
            ? {
                symbolSize: 44,
                label: { color: '#e2e8f0', fontWeight: 900 },
                data: activityMarkers
                  .filter((m: any) => m.name === 'I')
                  .map((m: any) => {
                    const idx = activityMonths.indexOf(m.label)
                    return { name: m.name, value: m.text, coord: [m.label, importCounts[idx] ?? 0] }
                  })
              }
            : undefined
        },
        {
          name: 'Alertas',
          type: 'line',
          data: alertCounts,
          smooth: true,
          symbolSize: 7,
          lineStyle: { width: 3, opacity: 0.85 },
          markPoint: activityMarkers.length
            ? {
                symbolSize: 44,
                label: { color: '#e2e8f0', fontWeight: 900 },
                data: activityMarkers
                  .filter((m: any) => m.name === 'A')
                  .map((m: any) => {
                    const idx = activityMonths.indexOf(m.label)
                    return { name: m.name, value: m.text, coord: [m.label, alertCounts[idx] ?? 0] }
                  })
              }
            : undefined
        }
      ]
    }
  }, [activityMonths, activityMarkers, alertCounts, importCounts])

  const { data: suggestions } = useQuery({
    queryKey: ['universal-suggestions', companyId, activeImportId ?? 'latest'],
    queryFn: () => getUniversalSuggestionsForImport(companyId as number, activeImportId),
    enabled: !!companyId
  })

  const {
    data: universalQuality,
    error: universalQualityError,
    isFetching: universalQualityLoading
  } = useQuery({
    queryKey: ['universal-quality', companyId, activeImportId ?? 'latest'],
    queryFn: () => getUniversalQuality(companyId as number, activeImportId),
    enabled: !!companyId
  })

  const { data: universalLineage } = useQuery({
    queryKey: ['universal-lineage', companyId, activeImportId ?? 'latest'],
    queryFn: () => getUniversalLineage(companyId as number, activeImportId),
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
      toast.push({ tone: 'success', title: 'Analisis', message: `${kind} analizado correctamente.` })
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

  const crossDefaults = useMemo(() => {
    const cols = Array.isArray(columns) ? columns : []
    const dateCol =
      cols.find((c: any) => String(c?.detectedType || '').toLowerCase() === 'date' || c?.dateMin || (c?.dateSeries || []).length)?.name ||
      ''
    const numCol =
      cols.find((c: any) => String(c?.detectedType || '').toLowerCase() === 'number' || c?.mean != null || c?.median != null)?.name || ''
    const catCol =
      cols.find((c: any) => String(c?.detectedType || '').toLowerCase() === 'string' && Number(c?.uniqueCount || 0) > 1)?.name || ''

    const dateCandidates = cols
      .filter((c: any) => String(c?.detectedType || '').toLowerCase() === 'date' || c?.dateMin || (c?.dateSeries || []).length)
      .map((c: any) => String(c?.name || ''))
      .filter(Boolean)
    const numCandidates = cols
      .filter((c: any) => String(c?.detectedType || '').toLowerCase() === 'number' || c?.mean != null || c?.median != null)
      .map((c: any) => String(c?.name || ''))
      .filter(Boolean)
    const catCandidates = cols
      .filter((c: any) => String(c?.detectedType || '').toLowerCase() === 'string' && Number(c?.uniqueCount || 0) > 1)
      .map((c: any) => String(c?.name || ''))
      .filter(Boolean)

    return {
      defaults: { dateCol: String(dateCol), numCol: String(numCol), catCol: String(catCol) },
      dateCandidates,
      numCandidates,
      catCandidates
    }
  }, [columns])

  useEffect(() => {
    if (!xfDateCol && crossDefaults.defaults.dateCol) setXfDateCol(crossDefaults.defaults.dateCol)
    if (!xfNumCol && crossDefaults.defaults.numCol) setXfNumCol(crossDefaults.defaults.numCol)
    if (!xfCatCol && crossDefaults.defaults.catCol) setXfCatCol(crossDefaults.defaults.catCol)
  }, [crossDefaults.defaults.dateCol, crossDefaults.defaults.numCol, crossDefaults.defaults.catCol, xfDateCol, xfNumCol, xfCatCol])

  const crossPick = useMemo(() => {
    const dateCol = String(xfDateCol || '').trim()
    const numCol = String(xfNumCol || '').trim()
    const catCol = String(xfCatCol || '').trim()
    if (!dateCol || !numCol || !catCol) return null
    return { dateCol, numCol, catCol }
  }, [xfDateCol, xfNumCol, xfCatCol])

  const crossTimeReq = useMemo(() => {
    if (!crossPick) return null
    const filters: any[] = []
    if (xfCategory) filters.push({ column: crossPick.catCol, op: 'eq', value: xfCategory })
    if (xfMonth) filters.push({ column: crossPick.dateCol, op: 'month_eq', value: xfMonth })
    return {
      type: 'TIME_SERIES',
      dateColumn: crossPick.dateCol,
      valueColumn: crossPick.numCol,
      aggregation: 'sum',
      filters
    } as any
  }, [crossPick, xfCategory, xfMonth])

  const crossBarReq = useMemo(() => {
    if (!crossPick) return null
    const filters: any[] = []
    if (xfMonth) filters.push({ column: crossPick.dateCol, op: 'month_eq', value: xfMonth })
    return {
      type: 'CATEGORY_BAR',
      categoryColumn: crossPick.catCol,
      valueColumn: crossPick.numCol,
      aggregation: 'sum',
      topN: 10,
      filters
    } as any
  }, [crossPick, xfMonth])

  const { data: crossTime, error: crossTimeError, isFetching: crossTimeLoading } = useQuery({
    queryKey: ['universal-cross-time', companyId, activeImportId ?? 'latest', xfCategory, xfMonth, crossPick?.dateCol, crossPick?.numCol, crossPick?.catCol],
    queryFn: async () => {
      if (!companyId || !crossTimeReq) return null
      return previewUniversalViewForImport(companyId as number, crossTimeReq as any, activeImportId)
    },
    enabled: !!companyId && !!crossTimeReq
  })

  const { data: crossBar, error: crossBarError, isFetching: crossBarLoading } = useQuery({
    queryKey: ['universal-cross-bar', companyId, activeImportId ?? 'latest', xfMonth, crossPick?.dateCol, crossPick?.numCol, crossPick?.catCol],
    queryFn: async () => {
      if (!companyId || !crossBarReq) return null
      return previewUniversalViewForImport(companyId as number, crossBarReq as any, activeImportId)
    },
    enabled: !!companyId && !!crossBarReq
  })

  const qualitySummary = (q: UniversalImportQualityDto | null | undefined) => {
    if (!q) return null
    const issues = Array.isArray(q.issues) ? q.issues : []
    const high = issues.filter((i) => String(i.severity).toUpperCase() === 'HIGH').length
    const med = issues.filter((i) => String(i.severity).toUpperCase() === 'MEDIUM').length
    const low = issues.filter((i) => String(i.severity).toUpperCase() === 'LOW').length
    const badge = high || String(q.level).toUpperCase() === 'RED' ? 'err' : med || String(q.level).toUpperCase() === 'YELLOW' ? 'warn' : 'ok'
    const label = high ? 'ROJO' : med ? 'AMARILLO' : low ? 'AMARILLO' : 'VERDE'
    return { badge, label, high, med, low }
  }

  const suggestionsList = (suggestions as UniversalAutoSuggestion[] | undefined) || []
  const allowedSuggestionTypes = plan === 'BRONZE' ? new Set(['TIME_SERIES', 'CATEGORY_BAR', 'KPI_CARDS']) : null
  const suggestionsAllowed = allowedSuggestionTypes ? suggestionsList.filter((s) => allowedSuggestionTypes.has(String(s?.request?.type || '').toUpperCase())) : suggestionsList
  const suggestionsBlockedCount = allowedSuggestionTypes ? suggestionsList.length - suggestionsAllowed.length : 0
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
      if (res.disclosure) setAssistantDisclosure(String(res.disclosure))
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
            ? 'Ranking categorias'
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

  function renderPanelState(title: string, detail?: string, tone: 'default' | 'loading' | 'locked' = 'default', className = 'mt-12') {
    return (
      <div className={`panel-state panel-state-${tone} ${className}`.trim()}>
        <div className="panel-state-title">{title}</div>
        {detail ? <div className="panel-state-detail">{detail}</div> : null}
      </div>
    )
  }

  return (
    <div>
      <PageHeader
        title="Analisis universal"
        subtitle="Sube un CSV o XLSX y te enseno primero lo importante. El detalle tecnico queda plegado."
        actions={
          <div className="row row-wrap row-center row-end gap-2">
            <span className="badge">{plan}</span>
            {companyId ? (
              <div ref={datasetRef} className="card soft card-pad-xs minw-320">
                <div className="upload-hint">Dataset activo</div>
                <div className="stack mt-1">
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
                    <option value="">Ultimo (auto)</option>
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
                        {activeImport?.createdAt ? new Date(activeImport.createdAt).toLocaleString() : '-'}
                        {activeImport ? ` · ${activeImport.rowCount} filas · ${activeImport.columnCount} columnas` : ''}
                      </>
                    ) : summary?.filename ? (
                      <>
                        {summary.filename} · {summary.createdAt ? new Date(summary.createdAt).toLocaleString() : '-'} ·{' '}
                        {summary.rowCount} filas · {summary.columnCount} columnas
                      </>
                    ) : (
                      '-'
                    )}
                  </div>
                </div>
              </div>
            ) : null}
          </div>
        }
      />

      <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">Ruta recomendada</h3>
          <span className="upload-hint">Para no perderte: sigue este orden.</span>
        </div>
        <div className="row row-wrap gap-2 mt-12">
          <span className="badge">1. Subir dataset</span>
          <span className="badge">2. Revisar calidad</span>
          <span className="badge">3. Leer insights</span>
          <span className="badge">4. Crear dashboard</span>
          <span className="badge">5. Abrir avanzado si hace falta</span>
        </div>
      </div>

      {companyId ? (
        <div className="card section">
          <div className="mini-row row-baseline">
            <h3 className="m-0">Eventos (imports + alertas)</h3>
            <span className="upload-hint">Click en un mes para ir al detalle</span>
          </div>
          <div className="upload-hint mt-2">
            Markers: <span className="code-inline">I</span> = import, <span className="code-inline">A</span> = alertas.
          </div>

          <div className="mt-3">
            <EChart
              module="universal"
              option={activityOption as any}
              actions
              onAxisHover={(p) => setActivityFocus(String(p || '').slice(0, 7) || null)}
              onLeave={() => setActivityFocus(activityMonths[activityMonths.length - 1] || null)}
              onClick={(params) => {
                const period = String(params?.name ?? params?.axisValue ?? '').slice(0, 7)
                if (!period || period.length < 7) return
                const series = String(params?.seriesName || '')
                if (series === 'Imports') {
                  const imp = importsByMonth.get(period)?.[0]
                  if (!imp) {
                    toast.push({ tone: 'warning', title: 'Import', message: 'No hay imports en ese mes.' })
                    return
                  }
                  setActiveImportId(imp.id)
                  try {
                    window.localStorage.setItem(`universal.activeImportId.${companyId}`, String(imp.id))
                  } catch {}
                  setTimeout(() => datasetRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 0)
                  toast.push({ tone: 'success', title: 'Dataset', message: `Seleccionado import #${imp.id} (${period}).` })
                  return
                }
                if (series === 'Alertas') {
                  navigate(`/alerts?period=${encodeURIComponent(period)}`)
                }
              }}
            />
            <ChartNarrative
              title={`Lectura rapida · ${activityFocus || '-'}`}
              see={activityNarrative.see}
              why={activityNarrative.why}
              todo={activityNarrative.todo}
            />
          </div>

          <div className="grid mt-3">
            <div className="card soft">
              <div className="upload-hint">Imports ultimo mes</div>
              <div className="fw-900 mt-1">{impLast}</div>
              <div className="upload-hint mt-1">
                {impDelta == null ? '-' : `Delta vs mes anterior: ${impDelta >= 0 ? '+' : ''}${impDelta}`}
              </div>
            </div>
            <div className="card soft">
              <div className="upload-hint">Alertas ultimo mes</div>
              <div className="fw-900 mt-1">{alertLast}</div>
              <div className="upload-hint mt-1">
                {alertDelta == null ? '-' : `Delta vs mes anterior: ${alertDelta >= 0 ? '+' : ''}${alertDelta}`}
              </div>
            </div>
          </div>
        </div>
      ) : null}

      {companyId && (crossPick || crossDefaults?.dateCandidates?.length || crossDefaults?.numCandidates?.length || crossDefaults?.catCandidates?.length) ? (
        <div className="card section">
          <div className="mini-row row-baseline">
            <h3 className="m-0">Cruce entre graficos</h3>
            <span className="upload-hint">Selecciona una barra o un mes y el otro grafico se actualiza solo.</span>
          </div>

          <div className="grid grid-autofit-220 mt-12">
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Columna fecha</div>
              <select
                className="input mt-6"
                value={xfDateCol}
                onChange={(e) => {
                  setXfDateCol(String(e.target.value || ''))
                  setXfMonth(null)
                }}
              >
                <option value="">—</option>
                {(crossDefaults?.dateCandidates || []).map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Columna numérica</div>
              <select className="input mt-6" value={xfNumCol} onChange={(e) => setXfNumCol(String(e.target.value || ''))}>
                <option value="">—</option>
                {(crossDefaults?.numCandidates || []).map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Columna categoría</div>
              <select
                className="input mt-6"
                value={xfCatCol}
                onChange={(e) => {
                  setXfCatCol(String(e.target.value || ''))
                  setXfCategory(null)
                }}
              >
                <option value="">—</option>
                {(crossDefaults?.catCandidates || []).map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="row row-wrap gap-2 mt-2">
            <span className="badge">{xfCategory ? `Categoría: ${xfCategory}` : 'Categoría: —'}</span>
            <span className="badge">{xfMonth ? `Mes: ${xfMonth}` : 'Mes: —'}</span>
            <button
              className="badge"
              onClick={() => {
                setXfCategory(null)
                setXfMonth(null)
              }}
              disabled={!xfCategory && !xfMonth}
            >
              Limpiar
            </button>
          </div>

          {crossTimeError || crossBarError ? (
            <div className="mt-12">
              <Alert tone="warning">
                No se pudo cargar el cruce: {String((crossTimeError as any)?.message || (crossBarError as any)?.message || '')}
              </Alert>
            </div>
          ) : null}

          {!crossPick ? (
            <div className="mt-12">
              <Alert tone="warning" title="Falta configurar el cruce">
                Elige una columna de fecha, una numerica y una categoria. Si el fichero es raro, revisa los nombres detectados arriba.
              </Alert>
            </div>
          ) : null}

          <div className="grid grid-autofit-320 mt-12">
            <div className="card soft">
              <div className="upload-hint">
                Serie temporal · <span className="code-inline">{crossPick?.dateCol || '—'}</span> →{' '}
                <span className="code-inline">{crossPick?.numCol || '—'}</span>
              </div>
              {!crossPick ? (
                renderPanelState('Grafico pendiente de configurar', 'Define primero las 3 columnas para activar esta vista.')
              ) : crossTimeLoading ? (
                renderPanelState('Preparando serie temporal', 'Estoy calculando la evolucion temporal para las columnas elegidas.', 'loading')
              ) : (crossTime as any)?.labels?.length ? (
                <EChart
                  module="universal"
                  height={320}
                  actions
                  onClick={(params) => {
                    const p = String(params?.name ?? params?.axisValue ?? '').slice(0, 7)
                    if (!p || p.length < 7) return
                    setXfMonth(p)
                  }}
                  option={
                    {
                      tooltip: { trigger: 'axis' },
                      xAxis: { type: 'category', data: (crossTime as any).labels || [] },
                      yAxis: { type: 'value' },
                      series: [
                        {
                          name: (crossTime as any)?.series?.[0]?.name || 'Valor',
                          type: 'line',
                          smooth: true,
                          data: (crossTime as any)?.series?.[0]?.data || []
                        }
                      ]
                    } as any
                  }
                />
              ) : (
                renderPanelState('Sin datos para esta vista', 'Prueba otro mes, otra categoria o cambia las columnas elegidas.')
              )}
              <div className="upload-hint mt-2">Consejo: haz click en un mes para fijarlo como filtro.</div>
            </div>

            <div className="card soft">
              <div className="upload-hint">
                Ranking · <span className="code-inline">{crossPick?.catCol || '—'}</span> →{' '}
                <span className="code-inline">{crossPick?.numCol || '—'}</span>
              </div>
              {!crossPick ? (
                renderPanelState('Grafico pendiente de configurar', 'Define primero las 3 columnas para activar esta vista.')
              ) : crossBarLoading ? (
                renderPanelState('Preparando ranking', 'Estoy agrupando categorias y recalculando el ranking.', 'loading')
              ) : (crossBar as any)?.labels?.length ? (
                <EChart
                  module="universal"
                  height={320}
                  actions
                  onClick={(params) => {
                    const label = String(params?.name ?? params?.axisValue ?? '').trim()
                    if (!label) return
                    setXfCategory(label)
                  }}
                  option={
                    {
                      tooltip: { trigger: 'axis' },
                      xAxis: { type: 'category', data: (crossBar as any).labels || [] },
                      yAxis: { type: 'value' },
                      series: [
                        {
                          name: (crossBar as any)?.series?.[0]?.name || 'Valor',
                          type: 'bar',
                          data: (crossBar as any)?.series?.[0]?.data || []
                        }
                      ]
                    } as any
                  }
                />
              ) : (
                renderPanelState('Sin datos para esta vista', 'Prueba otro mes, otra categoria o cambia las columnas elegidas.')
              )}
              <div className="upload-hint mt-2">Consejo: haz click en una barra para filtrar la serie temporal.</div>
            </div>
          </div>
        </div>
      ) : null}
      <div className="card section">
        <h3 className="h3-reset">Crear dashboard (Auto -&gt; Guiado)</h3>
        <div className="upload-hint">
          Si el fichero no viene preparado, eliges 2-3 columnas y te preparo un dashboard reutilizable para los proximos meses.
        </div>

        {!companyId ? (
          <div className="mt-12">
            <Alert tone="warning">Selecciona una empresa.</Alert>
          </div>
        ) : null}

        {suggestionsAllowed.length || (plan === 'BRONZE' && suggestionsList.length) ? (
          <div className="card soft mt-12">
            <div className="row row-between row-center row-wrap gap-3">
              <div>
                <div className="fw-800">Sugerencias AUTO</div>
                <div className="upload-hint">Te propongo 1-2 lecturas utiles segun las columnas detectadas. Puedes crearlas con un click.</div>
                {suggestionsBlockedCount ? (
                  <div className="upload-hint mt-1">
                    En plan BRONZE ocultamos {suggestionsBlockedCount} sugerencia{suggestionsBlockedCount === 1 ? '' : 's'} avanzada{suggestionsBlockedCount === 1 ? '' : 's'} (scatter/heatmap/pivote).
                  </div>
                ) : null}
              </div>
            </div>
            <div className="stack stack-2 mt-2">
              {!suggestionsAllowed.length ? (
                <div className="upload-hint">
                  No veo una sugerencia clara para este dataset. Puedes usar el modo guiado o subir de plan para vistas avanzadas.
                </div>
              ) : null}
              {suggestionsAllowed.slice(0, 2).map((sug, idx) => (
                <div key={`${sug.title}-${idx}`} className="card card-pad-sm">
                  <div className="row row-between row-center row-wrap gap-3">
                    <div>
                      <div className="fw-800">{sug.title}</div>
                      <div className="upload-hint">{sug.description}</div>
                    </div>
                    <div className="row row-center gap-2 row-wrap">
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

        <div className="upload-row align-end">
          <label className="field">
            <span className="field-label">Tipo</span>
            <select value={builderType} onChange={(e) => setBuilderType(e.target.value as any)}>
              <option value="TIME_SERIES">Serie temporal (fecha -&gt; valor)</option>
              <option value="CATEGORY_BAR">Ranking por categoria (texto -&gt; valor)</option>
              <option value="KPI_CARDS">KPIs (count/sum/avg) + filtro</option>
              {plan === 'BRONZE' ? null : (
                <>
                  <option value="SCATTER">Scatter (X vs Y)</option>
                  <option value="HEATMAP">Heatmap simple (X x Y)</option>
                  <option value="PIVOT_MONTHLY">Tabla pivote (categoria x mes)</option>
                </>
              )}
            </select>
          </label>
          <label className="field minw-220">
            <span className="field-label">Nombre (opcional)</span>
            <input value={builderName} onChange={(e) => setBuilderName(e.target.value)} placeholder="Ej: Ventas por mes" />
          </label>
          <label className="field">
            <span className="field-label">Agregacion</span>
            <select value={builderAgg} onChange={(e) => setBuilderAgg(e.target.value as any)}>
              <option value="sum">Suma</option>
              <option value="avg">Media</option>
            </select>
          </label>
        </div>

        <div className="upload-row tight align-end">
          {builderType === 'TIME_SERIES' || builderType === 'PIVOT_MONTHLY' ? (
            <label className="field">
              <span className="field-label">Fecha</span>
              <select value={builderDateCol} onChange={(e) => setBuilderDateCol(e.target.value)}>
                <option value="">-</option>
                {dateCols.map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {builderType === 'CATEGORY_BAR' || builderType === 'PIVOT_MONTHLY' ? (
            <label className="field">
              <span className="field-label">Categoria</span>
              <select value={builderCatCol} onChange={(e) => setBuilderCatCol(e.target.value)}>
                <option value="">-</option>
                {textCols.map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {builderType === 'SCATTER' || builderType === 'HEATMAP' ? (
            <label className="field">
              <span className="field-label">X</span>
              <select value={builderXCol} onChange={(e) => setBuilderXCol(e.target.value)}>
                <option value="">-</option>
                {(builderType === 'HEATMAP' ? textCols : numberCols).map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {builderType === 'SCATTER' || builderType === 'HEATMAP' ? (
            <label className="field">
              <span className="field-label">Y</span>
              <select value={builderYCol} onChange={(e) => setBuilderYCol(e.target.value)}>
                <option value="">-</option>
                {(builderType === 'HEATMAP' ? textCols : numberCols).map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {builderType !== 'SCATTER' ? (
            <label className="field">
              <span className="field-label">{builderType === 'KPI_CARDS' ? 'Valor (opcional)' : 'Valor'}</span>
              <select value={builderValueCol} onChange={(e) => setBuilderValueCol(e.target.value)}>
                <option value="">-</option>
                {numberCols.map((c: string) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {builderType === 'PIVOT_MONTHLY' || builderType === 'HEATMAP' ? (
            <label className="field w-90">
              <span className="field-label">Top N</span>
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
            <label className="field w-110">
              <span className="field-label">Max puntos</span>
              <input
                type="number"
                min={50}
                max={10000}
                value={builderMaxPoints}
                onChange={(e) => setBuilderMaxPoints(Number(e.target.value || 0))}
              />
            </label>
          ) : null}

          <div className="card soft card-pad-sm minw-420">
            <div className="upload-hint mb-8">
              Filtros guardados (AND)
            </div>
            <div className="stack stack-2">
              {(builderFilters || []).map((f, idx) => (
                <div key={`f-${idx}`} className="upload-row flush align-end">
                  <label className="field minw-180">
                    <span className="field-label">Columna</span>
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
                  <label className="field w-130">
                    <span className="field-label">Op</span>
                    <select
                      value={f.op}
                      onChange={(e) => {
                        const v = e.target.value as any
                        setBuilderFilters((arr) => arr.map((it, i) => (i === idx ? { ...it, op: v } : it)))
                      }}
                    >
                      <option value="eq">=</option>
                      <option value="contains">contiene</option>
                      <option value="year_eq">ano =</option>
                      <option value="gt">&gt;</option>
                      <option value="gte">&gt;=</option>
                      <option value="lt">&lt;</option>
                      <option value="lte">&lt;=</option>
                    </select>
                  </label>
                  <label className="field minw-150">
                    <span className="field-label">Valor</span>
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
                  title="Anadir filtro"
                >
                  + Anadir filtro
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
            <label className="field">
              <span className="field-label">Plantillas</span>
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
          <div className="mt-12">
            <Alert tone="danger">{builderError}</Alert>
          </div>
        ) : null}

        {builderPreview ? (
          <div className="card mt-12">
            <h3 className="h3-reset">Vista previa</h3>
            {String(builderPreview.type || '').toUpperCase() === 'KPI_CARDS' ? (
              <div className="grid grid-min-160 grid-gap-12">
                {builderPreview.labels.map((k, idx) => (
                  <div key={`${k}-${idx}`} className="card soft">
                    <div className="upload-hint ttu">
                      {k}
                    </div>
                    <div className="fs-22 fw-700 mt-1">
                      {String(((builderPreview.series as any)?.[0]?.data || [])[idx] ?? '-')}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <EChart
                module="universal"
                height={320}
                onAxisHover={(label) => setBuilderFocusLabel(String(label || ''))}
                onLeave={() => {
                  const labels = builderPreview?.labels || []
                  setBuilderFocusLabel(labels.length ? String(labels[labels.length - 1] || '') : '')
                }}
                onClick={(params) => {
                  const t = String(builderPreview?.type || '').toUpperCase()
                  if (t === 'SCATTER') {
                    const v = params?.value
                    if (Array.isArray(v) && v.length >= 2) setBuilderFocusLabel(`${v[0]},${v[1]}`)
                    return
                  }
                  if (t === 'HEATMAP') {
                    const v = params?.value
                    const ix = Array.isArray(v) ? v[0] : null
                    const iy = Array.isArray(v) ? v[1] : null
                    const xLabels = builderPreview?.labels || []
                    const yLabels = ((builderPreview?.meta as any)?.yLabels || []) as any[]
                    const xLabel = xLabels?.[Number(ix)] ?? ''
                    const yLabel = yLabels?.[Number(iy)] ?? ''
                    if (xLabel && yLabel) setBuilderFocusLabel(`${xLabel}||${yLabel}`)
                    return
                  }
                  if (t === 'PIVOT_MONTHLY') {
                    const month = String(params?.name ?? params?.axisValue ?? '').trim()
                    const cat = String(params?.seriesName ?? '').trim()
                    if (cat && month) setBuilderFocusLabel(`${cat}||${month}`)
                  }
                }}
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
            <ChartNarrative
              title={`Lectura del grafico · ${builderFocusLabel || '-'}`}
              see={builderNarrative.see}
              why={builderNarrative.why}
              todo={builderNarrative.todo}
              className="mt-2"
            />
            {builderPreview.meta ? (
              <div className="upload-hint mt-8">
                Filas usadas en el calculo: {(builderPreview.meta as any).rowsUsed ?? '-'} · Agregacion: {(builderPreview.meta as any).aggregation ?? '-'}
              </div>
            ) : null}
            {builderPreview.meta &&
            ((builderPreview.meta as any).badDateCount ||
              (builderPreview.meta as any).badNumberCount ||
              (builderPreview.meta as any).badXCount ||
              (builderPreview.meta as any).badYCount) ? (
              <div className="upload-hint mt-1">
                Invalidos: fecha={(builderPreview.meta as any).badDatePct ?? 0}% · num={(builderPreview.meta as any).badNumberPct ?? 0}% · X={(builderPreview.meta as any).badXPct ?? 0}% · Y={(builderPreview.meta as any).badYPct ?? 0}%
              </div>
            ) : null}
            {builderLastRequest ? (
              <div className="row row-wrap gap-2 mt-2">
                {canUseEvidence ? (
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={loadBuilderEvidence}
                    disabled={!companyId || builderEvidenceLoading}
                    loading={builderEvidenceLoading}
                  >
                    Ver filas detras del punto
                  </Button>
                ) : (
                  renderPanelState('Evidencia bloqueada por plan', 'Las filas detras del punto se habilitan en GOLD y PLATINUM.', 'locked', 'mt-0')
                )}
                {builderEvidence?.rows?.length ? (
                  <Button variant="secondary" size="sm" onClick={() => downloadEvidenceCsv(builderEvidence)}>
                    Descargar evidencia CSV
                  </Button>
                ) : null}
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
                  Descargar filas problematicas
                </Button>
              </div>
            ) : null}
            {builderEvidenceError ? (
              <div className="mt-2">
                <Alert tone="danger" title="Evidencia">
                  {builderEvidenceError}
                </Alert>
              </div>
            ) : null}
            {builderEvidence?.rows?.length ? (
              <details className="card soft mt-2">
                <summary className="upload-hint cursor-pointer">
                  Filas que explican este punto · {builderEvidence.rows.length} filas
                </summary>
                <div className="upload-hint mt-2">
                  {(builderEvidence.meta as any)?.rowsMatched != null ? `Coinciden: ${(builderEvidence.meta as any).rowsMatched}` : ''}
                  {(builderEvidence.meta as any)?.rowsScanned != null ? ` · Revisadas: ${(builderEvidence.meta as any).rowsScanned}` : ''}
                </div>
                <div className="overflow-auto mt-12">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>#</th>
                        {(builderEvidence.headers || []).map((h) => (
                          <th key={h}>{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {(builderEvidence.rows || []).slice(0, 60).map((r, idx) => (
                        <tr key={`${idx}`}>
                          <td className="upload-hint">{String((builderEvidence.rowNumbers || [])[idx] ?? '')}</td>
                          {(r || []).map((cell, j) => (
                            <td key={`${idx}-${j}`}>{String(cell ?? '')}</td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>
            ) : null}
            {Array.isArray((builderPreview.meta as any)?.warnings) && (builderPreview.meta as any).warnings.length ? (
              <div className="mt-2">
                <Alert tone="warning" title="Avisos">
                  <ul className="m-0 pl-18">
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
            <div className="mt-12">
              <Alert tone="warning">Selecciona una empresa para subir el archivo.</Alert>
            </div>
          )}
          {file && isXlsx ? (
            <details className="card soft mt-12">
              <summary className="upload-hint cursor-pointer">
                Ajustes XLSX (si las columnas salen raras)
              </summary>
              <div className="mt-2">
                {xlsxLoading ? <div className="upload-hint">Detectando estructura del Excel...</div> : null}
                {!!xlsxPreview?.sheets?.length ? (
                  <div className="upload-row tight">
                    <label className="row row-center gap-2">
                      <span className="w-110 inline-block">Hoja</span>
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
                    <label className="row row-center gap-2">
                      <span className="w-110 inline-block">Encabezado</span>
                      <input
                        type="number"
                        min={1}
                        value={headerRow ?? xlsxPreview.headerRow ?? 1}
                        onChange={(e) => setHeaderRow(Number(e.target.value))}
                        disabled={xlsxLoading}
                        className="w-90"
                      />
                      <small className="upload-hint">Fila (1-based)</small>
                    </label>
                  </div>
                ) : null}
                {!!xlsxPreview?.headers?.length ? (
                  <div className="upload-hint mt-2">
                    Headers detectados: {xlsxPreview.headers.slice(0, 8).join(' · ')}
                    {xlsxPreview.headers.length > 8 ? '...' : ''}
                  </div>
                ) : null}
                {!!xlsxPreview?.sampleRows?.length ? (
                  <div className="mt-2 overflow-auto">
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
            <div className="mt-12">
              <Alert tone="danger">{uploadError}</Alert>
            </div>
          )}
          {uploadOk && (
            <div className="mt-12">
              <Alert tone="success">{uploadOk}</Alert>
            </div>
          )}
        </div>
        <div className="card soft">
          <h3 className="h3-reset">Resumen</h3>
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
              <strong className="fs-14">{summary?.filename ?? '-'}</strong>
            </div>
          </div>
        </div>
      </div>

      {error && <p className="error">{String((error as any).message)}</p>}

      {likelyBadHeaders ? (
        <div className="section">
          <Alert tone="warning" title="El Excel parece mal interpretado">
            Los nombres de columna parecen numeros (posible fila de datos en vez de encabezado). Abre "Ajustes XLSX" y prueba a cambiar la fila de
            encabezado (o exporta a CSV con titulos).
          </Alert>
        </div>
      ) : null}

      <details className="card section">
        <summary className="mini-row cursor-pointer mt-0">
          <strong>Analisis avanzado</strong>
          <span className="upload-hint">assistant, estructura completa y relaciones</span>
        </summary>

      <div className="grid section">
        <div className="card">
          <h3 className="h3-reset">Insights & asesoramiento</h3>
          {!insights.length ? (
            renderPanelState('Sin lectura automatica todavia', 'Sube un CSV o XLSX para generar insights, alertas y primeras recomendaciones.', 'default', 'mt-0')
          ) : (
            <ul className="m-0 pl-18">
              {insights.slice(0, showAllInsights ? insights.length : 3).map((it: any, idx: number) => (
                <li key={`${it.title}-${idx}`} className="mb-8">
                  <strong>{it.title}:</strong> {it.message}
                </li>
              ))}
            </ul>
          )}
          {insights.length > 3 ? (
            <div className="mt-2">
              <Button size="sm" variant="ghost" onClick={() => setShowAllInsights((v) => !v)}>
                {showAllInsights ? 'Ver menos' : `Ver todos (${insights.length})`}
              </Button>
            </div>
          ) : null}
          {plan === 'BRONZE' && (
            <div className="upload-hint mt-2">
              En GOLD/PLATINUM se habilitan correlaciones, distribuciones completas y asesoramiento mas accionable.
            </div>
          )}
        </div>
      </div>

      <div className="grid section">
        <div className="card">
          <h3 className="h3-reset">Assistant (reglas · PLATINUM)</h3>
          <div className="upload-hint mt-1">
            Motor de reglas/heuristicas (no IA generativa). Ver `docs/assistant-vs-ai.md`.
          </div>
          {assistantDisclosure ? <div className="upload-hint mt-2">{assistantDisclosure}</div> : null}
          {!hasPlatinum ? (
            renderPanelState('Assistant bloqueado por plan', 'El asistente consultivo completo se habilita en PLATINUM.', 'locked', 'mt-0')
          ) : (
            <div>
              <div className="row row-wrap gap-2 mb-2">
                <Button variant="secondary" size="sm" onClick={handleDownloadNormalizedCsv}>Descargar CSV normalizado</Button>
                <Button variant="ghost" size="sm" onClick={() => handleLoadRows(50)} disabled={rowsLoading} loading={rowsLoading}>
                  Ver 50 filas
                </Button>
                <Button size="sm" onClick={handleGenerateAdvisorReport}>Descargar informe consultivo</Button>
              </div>
              {rowsError && <div className="error">{rowsError}</div>}
              {!!rowsPreview?.rows?.length && (
                <div className="mb-3 overflow-auto">
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
                  <div className="upload-hint mt-8">
                    Preview de filas del CSV normalizado (drill-down). Para analisis completo usa la descarga.
                  </div>
                </div>
              )}

              <div className="maxh-260 overflow-auto pr-1">
                {assistantMessages.map((m, idx) => (
                  <div key={idx} className="mb-2">
                    <div className="badge inline-block mb-1">
                      {m.role === 'user' ? 'Tu' : 'Assistant'}
                    </div>
                    <div className="pre-wrap">{m.content}</div>
                  </div>
                ))}
              </div>

              {!!assistantPrompts.length && (
                <div className="row row-wrap gap-2 mt-2">
                  {assistantPrompts.slice(0, 6).map((p) => (
                    <Button key={p} variant="ghost" size="sm" onClick={() => sendAssistantMessage(p)} disabled={assistantLoading}>
                      {p}
                    </Button>
                  ))}
                </div>
              )}

              {!!assistantQuestions.length && (
                <div className="mt-2">
                  <div className="upload-hint">Preguntas para afinar:</div>
                  <div className="row row-wrap gap-2 mt-1">
                    {assistantQuestions.slice(0, 6).map((q) => (
                      <Button key={q} variant="ghost" size="sm" onClick={() => sendAssistantMessage(q)} disabled={assistantLoading}>
                        {q}
                      </Button>
                    ))}
                  </div>
                </div>
              )}

              <div className="upload-row tight">
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
                <div className="mt-3">
                  <h4 className="m-0 mb-8">Plan recomendado</h4>
                  <div className="grid">
                    {assistantActions.slice(0, 6).map((a, idx) => (
                      <div key={`${a.title}-${idx}`} className="kpi">
                        <h4>{a.title}</h4>
                        <div className="mini-row">
                          <span className="badge">{a.horizon}</span>
                          <span className="badge">{a.priority}</span>
                        </div>
                        <div className="mt-1">{a.detail}</div>
                        {a.kpi && <div className="upload-hint mt-8">KPI: {a.kpi}</div>}
                        {!!a.evidence?.length && (
                          <div className="mt-2">
                            <div className="upload-hint">Evidencias</div>
                            <div className="mt-1">
                              <ul className="m-0 pl-18">
                                {a.evidence.slice(0, 6).map((e, eidx) => {
                                  const meta = [e.subtitle, e.metric].filter(Boolean).join(' · ')
                                  return (
                                    <li key={`${e.type}-${e.title}-${eidx}`} className="mb-1">
                                      <strong>{e.title}</strong>{meta ? <span className="upload-hint"> ({meta})</span> : null}
                                      {e.detail ? <div className="upload-hint">{e.detail}</div> : null}
                                    </li>
                                  )
                                })}
                              </ul>
                            </div>
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
        <div className="card">
          <div className="row row-between row-center row-wrap gap-10">
            <div>
              <div className="fw-800">Calidad de dato (semaforo)</div>
              <div className="upload-hint">
                {universalQualityLoading ? 'Analizando...' : universalQuality ? `Score: ${universalQuality.score}/100` : '-'}
                {universalQuality?.minDate && universalQuality?.maxDate ? ` · Rango fechas: ${universalQuality.minDate} -> ${universalQuality.maxDate}` : ''}
              </div>
            </div>
            {universalQuality ? (
              <span className={`badge ${qualitySummary(universalQuality)?.badge || ''}`}>
                {qualitySummary(universalQuality)?.label || 'OK'}
              </span>
            ) : (
              <span className="badge">-</span>
            )}
          </div>

          {universalQualityError ? (
            <div className="mt-12">
              <Alert tone="danger">No se pudo calcular la calidad: {String((universalQualityError as any)?.message || universalQualityError)}</Alert>
            </div>
          ) : null}

          {universalQuality ? (
            <div className="grid grid-autofit-180 mt-12">
              <div className="card soft card-pad-sm">
                <div className="upload-hint">Filas analizadas</div>
                <div className="fw-700">{universalQuality.rowsScanned}</div>
              </div>
              <div className="card soft card-pad-sm">
                <div className="upload-hint">Errores fecha/num</div>
                <div className="fw-700">
                  {universalQuality.dateParseErrors}/{universalQuality.numberParseErrors}
                </div>
              </div>
              <div className="card soft card-pad-sm">
                <div className="upload-hint">Filas irregulares</div>
                <div className="fw-700">{universalQuality.irregularRows}</div>
              </div>
              <div className="card soft card-pad-sm">
                <div className="upload-hint">Celdas vacias</div>
                <div className="fw-700">
                  {universalQuality.totalCells ? Math.round((universalQuality.nullCells / Math.max(1, universalQuality.totalCells)) * 100) : 0}%
                </div>
              </div>
            </div>
          ) : null}

          {universalQuality?.issues?.length ? (
            <div className="mt-12">
              <div className="upload-hint">Que revisar</div>
              <ul className="m-0 pl-18 mt-6">
                {universalQuality.issues.slice(0, 6).map((it, idx) => (
                  <li key={`${it.code}-${idx}`}>
                    <strong>{it.title}</strong> <span className="upload-hint">({it.severity})</span>
                    {it.detail ? <div className="upload-hint">{it.detail}</div> : null}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          {universalQuality?.examples?.length ? (
            <details className="mt-12">
              <summary className="upload-hint cursor-pointer">Ejemplos</summary>
              <pre className="code-block mt-12">{universalQuality.examples.join('\n')}</pre>
            </details>
          ) : null}

          <LineagePanel lineage={universalLineage as any} />
        </div>
      </div>

      <div className="section">
        <details className="card">
          <summary className="mini-row cursor-pointer mt-0">
            <strong>Columnas</strong>
            <span className="badge">{columns.length || 0}</span>
          </summary>
          {!columns.length ? (
            renderPanelState('Sin estructura detectada', 'Sube un CSV o XLSX para ver columnas, tipos, nulos y rangos.')
          ) : (
            <>
              <div className="mt-12">
                <Button size="sm" variant="ghost" onClick={() => setShowAllColumns((v) => !v)}>
                  {showAllColumns ? 'Ver menos' : `Ver todas (${columns.length})`}
                </Button>
              </div>
              <div className="mt-12 overflow-auto">
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
          <summary className="mini-row cursor-pointer mt-0">
            <strong>Graficos</strong>
            <span className="upload-hint">distribuciones y fechas</span>
          </summary>
          <div className="grid mt-12">
            <div className="card soft">
              <h3 className="h3-reset">Distribuciones numericas</h3>
              {!numericColumns.length ? (
                renderPanelState('Sin columnas numericas claras', 'Revisa si los importes vienen como texto o con formatos mixtos.', 'default', 'mt-0')
              ) : (
                <div className="grid">
                  {numericColumns.map((c: any) => (
                    <div key={`${c.name}-hist`} className="kpi">
                      <h4>{c.name}</h4>
                      {!c.histogram?.length ? (
                        <span className="badge">sin histograma</span>
                      ) : (
                        <EChart
                          module="universal"
                          height={220}
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
              <h3 className="h3-reset">Series de fechas</h3>
              {!dateColumns.length ? (
                renderPanelState('Sin columnas de fecha claras', 'Prueba a normalizar fechas o revisa la fila de cabecera del fichero.', 'default', 'mt-0')
              ) : (
                <div className="grid">
                  {dateColumns.map((c: any) => (
                    <div key={`${c.name}-dates`} className="kpi">
                      <h4>{c.name}</h4>
                      {!c.dateSeries?.length ? (
                        <span className="badge">sin serie</span>
                      ) : (
                        <EChart
                          module="universal"
                          height={240}
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
          <summary className="mini-row cursor-pointer mt-0">
            <strong>Relaciones y categorias</strong>
            <span className="upload-hint">valores frecuentes y correlaciones</span>
          </summary>
          <div className="grid mt-12">
            <div className="card soft">
              <h3 className="h3-reset">Top valores (categorias)</h3>
              {!categoricalColumns.length ? (
                renderPanelState('Sin columnas categoricas claras', 'Puede que el dataset tenga pocos textos utiles o demasiados valores unicos.', 'default', 'mt-0')
              ) : (
                <div className="grid">
                  {categoricalColumns.map((c: any) => (
                    <div key={`${c.name}-top`} className="kpi">
                      <h4>{c.name}</h4>
                      <EChart
                        module="universal"
                        height={220}
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
              <h3 className="h3-reset">Correlaciones (numericas)</h3>
              {!correlations.length ? (
                plan === 'BRONZE'
                  ? renderPanelState('Correlaciones bloqueadas por plan', 'Esta lectura se habilita en GOLD o superior.', 'locked', 'mt-0')
                  : renderPanelState('Sin correlaciones utiles', 'No veo relaciones numericas fuertes con este dataset.', 'default', 'mt-0')
              ) : (
                <div>
                  {corrHeatmap ? (
                    <div className="mb-12">
                      <EChart
                        module="universal"
                        height={320}
                        option={{
                          tooltip: {
                            trigger: 'item',
                            formatter: (p: any) => {
                              const a = corrHeatmap.cols?.[p.value?.[0]] ?? ''
                              const b = corrHeatmap.cols?.[p.value?.[1]] ?? ''
                              const v = Number(p.value?.[2] ?? 0)
                              return `${a} vs ${b}\n${v.toFixed(3)}`
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
                      <div className="upload-hint mt-1">
                        Matriz de correlacion (subconjunto). Valores cerca de 1/-1 implican relacion fuerte.
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
                  <details className="mt-12">
                    <summary className="upload-hint cursor-pointer">
                      Ver tabla completa
                    </summary>
                    <div className="mt-2 overflow-auto">
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
                  </details>
                </div>
              )}
            </div>
          </div>
        </details>
      </div>
      </details>
    </div>
  )
}

