import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import '../components/charts/echarts-advanced'
import EChart from '../components/charts/EChart'
import ChartNarrative from '../components/charts/ChartNarrative'
import ExplainThisChart from '../components/charts/ExplainThisChart'
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
  type UniversalIntakeDiagnosis,
  type UniversalAutoSuggestion,
  type UniversalChartData,
  type UniversalEvidenceDto,
  type UniversalImportQualityDto,
  type UniversalRows,
  type UniversalSummaryDto,
  type UniversalViewDto,
  type UniversalViewRequest,
  type UniversalXlsxPreview
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'
import { intakeDetail, intakeDisplayLabel, intakePrimaryActionLabel, intakeRecommendedRoute, intakeKind, isAnnualBudgetDiagnosis } from '../utils/intakeDiagnosis'
import { buildUniversalChartNarrative } from '../utils/universalChartNarrative'
import { EMPTY_DATA_TEXT, EMPTY_VALUE, formatDateTime } from '../utils/format'

type AggregationMode = NonNullable<UniversalViewRequest['aggregationMode']>

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

function formatCompactNumber(value: number | null | undefined, digits = 1) {
  if (value == null || Number.isNaN(Number(value))) return '-'
  return new Intl.NumberFormat('es-ES', { maximumFractionDigits: digits }).format(Number(value))
}

function formatCompactPercent(value: number | null | undefined, digits = 0) {
  if (value == null || Number.isNaN(Number(value))) return '-'
  return `${new Intl.NumberFormat('es-ES', { maximumFractionDigits: digits }).format(Number(value))}%`
}

function prettyColumnName(name: string) {
  const key = String(name || '').trim().toLowerCase()
  const aliases: Record<string, string> = {
    gross_pay: 'salario bruto',
    net_pay: 'salario neto',
    employer_cpp: 'CPP empresa',
    employer_ei: 'EI empresa',
    federal_tax: 'impuesto federal',
    provincial_tax: 'impuesto provincial',
    cpp: 'CPP',
    ei: 'EI',
    pay_date: 'fecha de pago',
    employee_name: 'empleado',
    role: 'rol'
  }
  if (aliases[key]) return aliases[key]
  return String(name || '')
    .replace(/[_-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function semanticLabel(value: string | null | undefined) {
  const key = String(value || '').trim().toUpperCase()
  const labels: Record<string, string> = {
    ACCOUNTING_ENTRY_LINE: 'LÃ­nea contable',
    DOCUMENT_LINE: 'LÃ­nea de documento',
    INVOICE: 'Factura',
    DOCUMENT: 'Documento',
    PARTY: 'Tercero',
    ACCOUNT: 'Cuenta',
    ENTRY: 'Asiento',
    MEASURE: 'MÃ©trica',
    TEMPORAL: 'Temporal',
    CATEGORICAL: 'CategorÃ­a',
    IDENTIFIER: 'Identificador',
    ACCOUNT_CODE: 'Cuenta contable',
    ACCOUNT_NAME: 'Nombre de cuenta',
    ENTRY_ID: 'Asiento',
    DOCUMENT_ID: 'Documento',
    INVOICE_ID: 'Factura',
    PARTY_ID: 'Tercero',
    PARTY_NAME: 'Nombre tercero',
    PROJECT_ID: 'Proyecto',
    PROJECT_NAME: 'Nombre proyecto',
    DATE: 'Fecha',
    DEBIT_AMOUNT: 'Importe debe',
    CREDIT_AMOUNT: 'Importe haber',
    SIGNED_AMOUNT: 'Importe neto',
    CURRENCY: 'Importe',
    BOOLEAN: 'Bandera',
    TRI_STATE_BOOLEAN: 'Estado aplicable',
    STATUS: 'Estado',
    CATEGORICAL_DIMENSION: 'DimensiÃ³n',
    TAX_IDENTIFIER: 'NIF/CIF',
    FREE_TEXT: 'Texto libre'
  }
  return labels[key] || prettyColumnName(String(value || 'sin clasificar'))
}

function aggregationLabel(value: string | null | undefined) {
  const key = String(value || '').trim().toUpperCase()
  const labels: Record<string, string> = {
    ROW_COUNT: 'filas',
    DISTINCT_COUNT: 'distintos',
    DISTINCT_ENTRY_COUNT: 'asientos',
    DISTINCT_DOCUMENT_COUNT: 'documentos',
    DISTINCT_INVOICE_COUNT: 'facturas',
    DISTINCT_PARTY_COUNT: 'terceros',
    SUM_DEBIT: 'debe',
    SUM_CREDIT: 'haber',
    NET_BALANCE: 'saldo',
    SUM_AMOUNT: 'importe',
    SUM_VALUE: 'suma',
    SUM_DISTINCT_VALUE: 'suma deduplicada',
    AVG_VALUE: 'media',
    MEDIAN: 'mediana',
    SHARE: 'porcentaje',
    TIME_SERIES: 'evoluciÃ³n',
    APPLICABLE_ROW_COUNT: 'aplicables',
    APPLICABLE_RATE: '% aplicable'
  }
  return labels[key] || prettyColumnName(String(value || ''))
}

function viewTypeLabel(value: string | null | undefined) {
  const key = String(value || '').trim().toUpperCase()
  const labels: Record<string, string> = {
    TIME_SERIES: 'Serie temporal',
    CATEGORY_BAR: 'Ranking',
    KPI_CARDS: 'KPIs',
    SCATTER: 'Relacion X/Y',
    HEATMAP: 'Mapa cruzado',
    PIVOT_MONTHLY: 'Pivote mensual'
  }
  return labels[key] || prettyColumnName(String(value || 'vista'))
}

function columnSupportsAggregation(column: any, mode: string) {
  const wanted = String(mode || '').trim().toUpperCase()
  return Array.isArray(column?.validAggregations)
    ? column.validAggregations.some((item: any) => String(item || '').trim().toUpperCase() === wanted)
    : false
}

function aggregationNeedsValue(mode: AggregationMode | null | undefined) {
  return !mode || mode === 'SUM_AMOUNT' || mode === 'AVG_VALUE'
}

function isMeasureColumn(column: any) {
  return String(column?.analyticalType || '').toUpperCase() === 'MEASURE' && String(column?.detectedType || '').toLowerCase() === 'number'
}

function isTemporalColumn(column: any) {
  return String(column?.analyticalType || '').toUpperCase() === 'TEMPORAL' || String(column?.detectedType || '').toLowerCase() === 'date'
}

function isCategoryColumn(column: any) {
  const analyticalType = String(column?.analyticalType || '').toUpperCase()
  return ['CATEGORICAL', 'IDENTIFIER', 'STATUS'].includes(analyticalType) || String(column?.semanticType || '').toUpperCase() === 'ACCOUNT_CODE'
}

function entitySummary(summary: any) {
  const entities = Array.isArray(summary?.detectedEntities) ? summary.detectedEntities : []
  if (!entities.length) return 'Sin entidad dominante detectada'
  return entities
    .slice(0, 3)
    .map((entity: any) => `${semanticLabel(entity?.entityType)} ${formatCompactNumber(Number(entity?.distinctCount || 0), 0)}`)
    .join('  Â·  ')
}

function insightTone(value: string | null | undefined) {
  const key = String(value || '').trim().toLowerCase()
  if (key === 'warning') return 'warn'
  if (key === 'opportunity' || key === 'advisor') return 'ok'
  return 'default'
}

function insightAudienceLabel(value: string | null | undefined) {
  const key = String(value || '').trim().toLowerCase()
  if (key === 'warning') return 'Riesgo'
  if (key === 'opportunity') return 'Oportunidad'
  if (key === 'advisor') return 'Lectura consultiva'
  return 'Lectura base'
}

function trimInsightMessage(message: string | null | undefined) {
  const text = String(message || '').trim()
  if (!text) return ''
  const normalized = text.replace(/\s+/g, ' ')
  if (normalized.length <= 170) return normalized
  const cut = normalized.slice(0, 167).trimEnd()
  return `${cut}...`
}

function parseRangeLabel(label: string) {
  const values =
    String(label || '')
      .match(/-?\d+(?:[.,]\d+)?/g)
      ?.map((part) => Number(String(part).replace(',', '.')))
      .filter((value) => Number.isFinite(value)) || []
  if (values.length >= 2) return { from: values[0], to: values[1] }
  if (values.length === 1) return { from: values[0], to: values[0] }
  return null
}

function describeDistribution(column: any) {
  const histogram = Array.isArray(column?.histogram) ? column.histogram : []
  const total = histogram.reduce((sum: number, bucket: any) => sum + Number(bucket?.count || 0), 0)
  if (!histogram.length || total <= 0) {
    return {
      see: ['TodavÃ­a no hay suficiente informaciÃ³n para resumir esta distribuciÃ³n.'],
      why: ['Sin histograma no puedo decir si la mÃ©trica estÃ¡ concentrada, dispersa o tiene valores raros.'],
      todo: ['Revisa si la columna viene bien importada como nÃºmero y no como texto.']
    }
  }

  const nonZero = histogram.filter((bucket: any) => Number(bucket?.count || 0) > 0)
  const sorted = [...nonZero].sort((a: any, b: any) => Number(b?.count || 0) - Number(a?.count || 0))
  const dominant = sorted[0]
  const top3 = sorted.slice(0, 3).reduce((sum: number, bucket: any) => sum + Number(bucket?.count || 0), 0)
  const dominantShare = (Number(dominant?.count || 0) / total) * 100
  const top3Share = (top3 / total) * 100
  const range = parseRangeLabel(String(dominant?.label || ''))
  const min = column?.min
  const max = column?.max
  const nullCount = Number(column?.nullCount || 0)
  const spreadText =
    nonZero.length <= 3
      ? 'muy concentrada'
      : nonZero.length <= Math.max(4, Math.ceil(histogram.length / 2))
        ? 'bastante concentrada'
        : 'bastante repartida'
  const dominantText = range
    ? `La mayor concentraciÃ³n de ${prettyColumnName(column?.name)} estÃ¡ entre ${formatCompactNumber(range.from, 2)} y ${formatCompactNumber(range.to, 2)} (${formatCompactPercent(dominantShare)} de las filas).`
    : `El tramo con mÃ¡s peso en ${prettyColumnName(column?.name)} es ${String(dominant?.label || '-')}, con ${formatCompactPercent(dominantShare)} de las filas.`

  const why = min != null && max != null
    ? `La distribuciÃ³n estÃ¡ ${spreadText}: el rango observado va de ${formatCompactNumber(Number(min), 2)} a ${formatCompactNumber(Number(max), 2)} y los 3 tramos mÃ¡s frecuentes concentran ${formatCompactPercent(top3Share)}.`
    : `La distribuciÃ³n estÃ¡ ${spreadText}: los 3 tramos mÃ¡s frecuentes concentran ${formatCompactPercent(top3Share)} del dataset.`

  const todo =
    nullCount > 0
      ? `Hay ${formatCompactNumber(nullCount, 0)} filas vacÃ­as en esta mÃ©trica. Conviene revisar si faltan importes o si el origen no la rellena siempre.`
      : nonZero.length <= 2
        ? 'Tiene muy poca variaciÃ³n. Si esperabas mÃ¡s casuÃ­stica, revisa si la columna se ha agregado demasiado o si el origen trae valores repetidos.'
        : 'Ãšsala para segmentar o cruzarla con una categorÃ­a/fecha: aquÃ­ hay suficiente variaciÃ³n para sacar lectura Ãºtil.'

  return { see: [dominantText], why: [why], todo: [todo] }
}

function describeDateSeries(column: any) {
  const series = Array.isArray(column?.dateSeries) ? column.dateSeries : []
  type DatePoint = { label: string; count: number }
  const points = series
    .map((item: any) => ({ label: String(item?.label || '-'), count: Number(item?.count || 0) }))
    .filter((item: DatePoint) => Number.isFinite(item.count))

  if (!points.length) {
    return {
      see: ['TodavÃ­a no hay suficiente informaciÃ³n temporal para resumir esta serie.'],
      why: ['Sin serie temporal no puedo ver si la carga es estable, si faltan periodos o si hay picos.'],
      todo: ['Revisa que la columna de fecha se haya detectado bien y que tenga valores vÃ¡lidos.']
    }
  }

  const total = points.reduce((sum: number, point: DatePoint) => sum + point.count, 0)
  const avg = total / points.length
  const peak = points.reduce((best: DatePoint, point: DatePoint) => (point.count > best.count ? point : best), points[0])
  const floor = points.reduce((best: DatePoint, point: DatePoint) => (point.count < best.count ? point : best), points[0])
  const emptyPeriods = points.filter((point: DatePoint) => point.count === 0).length
  const volatility = avg > 0 ? (peak.count - floor.count) / avg : 0
  const cadence =
    volatility < 0.35
      ? 'bastante estable'
      : volatility < 0.9
        ? 'estable con algunos picos'
        : 'irregular'

  const see =
    volatility < 0.35
      ? `El ritmo de ${prettyColumnName(column?.name)} es ${cadence}: media de ${formatCompactNumber(avg, 0)} filas por periodo, con pico en ${peak.label} (${formatCompactNumber(peak.count, 0)}).`
      : `Hay picos claros en ${prettyColumnName(column?.name)}: el mÃ¡ximo estÃ¡ en ${peak.label} con ${formatCompactNumber(peak.count, 0)} filas, frente a un mÃ­nimo de ${formatCompactNumber(floor.count, 0)} en ${floor.label}.`

  const why =
    emptyPeriods > 0
      ? `Hay ${formatCompactNumber(emptyPeriods, 0)} periodos vacÃ­os. Esto puede indicar meses sin carga, huecos en el fichero o un calendario incompleto.`
      : `No se ven periodos vacÃ­os y el volumen total asciende a ${formatCompactNumber(total, 0)} filas. Sirve para saber si tu dataset llega de forma regular o con tandas.`

  const todo =
    emptyPeriods > 0
      ? 'Revisa esos periodos vacÃ­os antes de sacar conclusiones de negocio: pueden distorsionar comparativas y deltas.'
      : volatility >= 0.9
        ? 'Investiga quÃ© pasÃ³ en los picos: normalmente seÃ±alan cargas masivas, cierres de periodo o campaÃ±as concretas.'
        : 'Si esta fecha es operativa, ya estÃ¡ lista para comparar meses, detectar estacionalidad y construir alertas.'

  return { see: [see], why: [why], todo: [todo] }
}

function describeCategory(column: any) {
  const topValues = Array.isArray(column?.topValues) ? column.topValues : []
  const items = topValues
    .map((item: any) => ({ value: String(item?.value || '-').trim() || '-', count: Number(item?.count || 0) }))
    .filter((item: { value: string; count: number }) => Number.isFinite(item.count) && item.count > 0)

  if (!items.length) {
    return {
      see: ['TodavÃ­a no hay suficiente informaciÃ³n para resumir esta categorÃ­a.'],
      why: ['Sin valores frecuentes no puedo decir quÃ© segmentos pesan mÃ¡s ni si la columna sirve para agrupar.'],
      todo: ['Revisa si esta columna tiene demasiados valores Ãºnicos o si el texto viene sucio.']
    }
  }

  const total = items.reduce((sum: number, item: { value: string; count: number }) => sum + item.count, 0)
  const leader = items[0]
  const top3 = items.slice(0, 3).reduce((sum: number, item: { value: string; count: number }) => sum + item.count, 0)
  const concentration = total > 0 ? (top3 / total) * 100 : 0
  const uniquePreview = items.length

  const see = `El valor que mÃ¡s aparece en ${prettyColumnName(column?.name)} es ${leader.value} con ${formatCompactNumber(leader.count, 0)} filas (${formatCompactPercent((leader.count / total) * 100)} del top visible).`
  const why =
    concentration >= 70
      ? `La categorÃ­a estÃ¡ muy concentrada: los 3 valores principales ya explican ${formatCompactPercent(concentration)}. Esto ayuda a priorizar segmentos rÃ¡pido.`
      : `La categorÃ­a estÃ¡ mÃ¡s repartida: los 3 valores principales suman ${formatCompactPercent(concentration)}. Hay variedad suficiente para comparar grupos.`
  const todo =
    uniquePreview <= 2
      ? 'Tiene poca diversidad. QuizÃ¡ no sea la mejor columna para segmentar dashboards o filtros.'
      : `Ãšsala para rankings, filtros y cruces: aquÃ­ sÃ­ tienes segmentos con peso real para contar una historia.`

  return { see: [see], why: [why], todo: [todo] }
}

function describeCorrelation(entry: any) {
  const value = Number(entry?.correlation || 0)
  const strength = Math.abs(value)
  const label =
    strength >= 0.85 ? 'muy fuerte' :
    strength >= 0.65 ? 'fuerte' :
    strength >= 0.4 ? 'moderada' :
    'dÃ©bil'
  const direction = value >= 0 ? 'positiva' : 'negativa'
  return `${prettyColumnName(entry?.columnA)} y ${prettyColumnName(entry?.columnB)} tienen una relaciÃ³n ${label} y ${direction} (${value.toFixed(3)}).`
}

export default function UniversalDashboardPage() {
  const { id: companyId, plan } = useCompanySelection()
  const hasPlatinum = plan === 'PLATINUM'
  const canUseEvidence = plan === 'GOLD' || plan === 'PLATINUM'
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()
  const datasetRef = useRef<HTMLDivElement | null>(null)
  const supportRef = useRef<HTMLDetailsElement | null>(null)
  const previewRef = useRef<HTMLDivElement | null>(null)
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
  const [builderAggMode, setBuilderAggMode] = useState<AggregationMode>('SUM_AMOUNT')
  const [builderPreview, setBuilderPreview] = useState<UniversalChartData | null>(null)
  const [builderLastRequest, setBuilderLastRequest] = useState<UniversalViewRequest | null>(null)
  const [builderLoading, setBuilderLoading] = useState(false)
  const [builderError, setBuilderError] = useState<string | null>(null)
  const [builderFocusLabel, setBuilderFocusLabel] = useState('')
  const [builderEvidence, setBuilderEvidence] = useState<UniversalEvidenceDto | null>(null)
  const [builderEvidenceLoading, setBuilderEvidenceLoading] = useState(false)
  const [builderEvidenceError, setBuilderEvidenceError] = useState<string | null>(null)
  const [showDatasetPlaybook, setShowDatasetPlaybook] = useState(true)

  useEffect(() => {
    const labels = builderPreview?.labels || []
    setBuilderFocusLabel(labels.length ? String(labels[labels.length - 1] || '') : '')
    setBuilderEvidence(null)
    setBuilderEvidenceError(null)
  }, [builderPreview?.labels?.join('|')])

  useEffect(() => {
    if (!builderPreview) return
    const node = previewRef.current
    if (!node) return
    const timer = window.setTimeout(() => {
      node.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 80)
    return () => window.clearTimeout(timer)
  }, [builderPreview])

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
      const msg = e?.message || 'No se pudo cargar.'
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

  const selectedSavedView = useMemo(
    () => (((views || []) as UniversalViewDto[]).find((view) => view.id === selectedViewId) || null),
    [selectedViewId, views]
  )

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
      const diagnosis = (res?.intakeDiagnosis || null) as UniversalIntakeDiagnosis | null
      const diagnosisKind = intakeKind(diagnosis)
      setFile(null)
      const kind = file.name.toLowerCase().endsWith('.xlsx') ? 'XLSX' : 'CSV'
      if (isAnnualBudgetDiagnosis(diagnosis)) {
        setUploadOk('Plan anual detectado. Abrimos el flujo anual.')
        toast.push({ tone: 'success', title: 'Plan anual', message: 'Presupuesto anual detectado correctamente.' })
        navigate('/budget?source=upload')
        return
      }
      if (diagnosisKind === 'CASH_TRANSACTIONS') {
        setUploadOk('Fichero de caja detectado. La lectura tecnica queda en Universal y la ruta natural es Caja.')
        toast.push({ tone: 'info', title: 'Caja detectada', message: 'Si quieres cierre mensual, el siguiente paso natural es Caja.' })
        return
      }
      if (diagnosisKind === 'TRIBUNAL_PORTFOLIO') {
        setUploadOk('Cartera operativa detectada. La lectura tecnica queda en Universal y la ruta natural es Tribunal.')
        toast.push({ tone: 'info', title: 'Tribunal detectado', message: 'Este fichero encaja mejor en cartera operativa.' })
        return
      }
      const diagnosisLabel = intakeDisplayLabel(diagnosis, kind)
      const diagnosisText = intakeDetail(diagnosis, `${kind} analizado correctamente.`)
      setUploadOk(`${diagnosisLabel}. ${diagnosisText}`)
      toast.push({ tone: 'success', title: 'Analisis', message: `${kind} analizado correctamente.` })
    } catch (err: any) {
      setUploadError(err?.message || 'Error subiendo archivo.')
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'Error subiendo archivo.' })
    } finally {
      setUploading(false)
    }
  }

  const summary = ((data as UniversalSummaryDto | null | undefined) || null) as UniversalSummaryDto | null
  const intakeDiagnosis = ((summary?.intakeDiagnosis || (universalLineage as any)?.analysis?.intakeDiagnosis || null) as UniversalIntakeDiagnosis | null)
  const intakeDiagnosisKind = intakeKind(intakeDiagnosis)
  const columns = summary?.columns || []
  const correlations = summary?.correlations || []
  const insights = summary?.insights || []
  const normalizedColumnNames = useMemo(
    () => new Set((Array.isArray(columns) ? columns : []).map((c: any) => String(c?.name || '').trim().toLowerCase())),
    [columns]
  )
  const looksLikePayrollDataset =
    normalizedColumnNames.has('pay_date') &&
    normalizedColumnNames.has('role') &&
    (normalizedColumnNames.has('gross_pay') || normalizedColumnNames.has('net_pay'))
  const suggestionsList = (suggestions as UniversalAutoSuggestion[] | undefined) || []
  const allowedSuggestionTypes = plan === 'BRONZE' ? new Set(['TIME_SERIES', 'CATEGORY_BAR', 'KPI_CARDS']) : null
  const suggestionsAllowed = allowedSuggestionTypes ? suggestionsList.filter((s) => allowedSuggestionTypes.has(String(s?.request?.type || '').toUpperCase())) : suggestionsList
  const suggestionsBlockedCount = allowedSuggestionTypes ? suggestionsList.length - suggestionsAllowed.length : 0

  const quickStartViews = useMemo(() => {
    if (looksLikePayrollDataset) {
      const presets: Array<{ title: string; description: string; request: UniversalViewRequest }> = []
      if (normalizedColumnNames.has('gross_pay')) {
        presets.push({
          title: 'Coste bruto por mes',
          description: 'Serie temporal para ver cuÃ¡nto cuesta la nÃ³mina en bruto cada mes.',
          request: {
            name: 'Coste bruto por mes',
            type: 'TIME_SERIES',
            dateColumn: 'Pay_Date',
            valueColumn: 'Gross_Pay',
            aggregationMode: 'SUM_AMOUNT',
            aggregation: 'sum'
          }
        })
      }
      if (normalizedColumnNames.has('net_pay')) {
        presets.push({
          title: 'Neto pagado por rol',
          description: 'Ranking para detectar quÃ© rol concentra mÃ¡s pago neto.',
          request: {
            name: 'Neto pagado por rol',
            type: 'CATEGORY_BAR',
            categoryColumn: 'Role',
            valueColumn: 'Net_Pay',
            aggregationMode: 'SUM_AMOUNT',
            aggregation: 'sum',
            topN: 8
          }
        })
      }
      if (normalizedColumnNames.has('employee_name') && normalizedColumnNames.has('gross_pay')) {
        presets.push({
          title: 'Peso salarial por empleado',
          description: 'Comparativa por empleado para ver quiÃ©n pesa mÃ¡s en el coste total.',
          request: {
            name: 'Peso salarial por empleado',
            type: 'CATEGORY_BAR',
            categoryColumn: 'Employee_Name',
            valueColumn: 'Gross_Pay',
            aggregationMode: 'SUM_AMOUNT',
            aggregation: 'sum',
            topN: 10
          }
        })
      }
      if (normalizedColumnNames.has('role') && normalizedColumnNames.has('employer_cpp')) {
        presets.push({
          title: 'CotizaciÃ³n empresa por rol',
          description: 'Comparativa del coste empresa adicional para explicar cargas sociales.',
          request: {
            name: 'CotizaciÃ³n empresa por rol',
            type: 'CATEGORY_BAR',
            categoryColumn: 'Role',
            valueColumn: 'Employer_CPP',
            aggregationMode: 'SUM_AMOUNT',
            aggregation: 'sum',
            topN: 8
          }
        })
      }
      return presets.slice(0, 4)
    }

    return suggestionsAllowed.slice(0, 3).map((sug) => ({
      title: sug.title,
      description: sug.description,
      request: sug.request
    }))
  }, [looksLikePayrollDataset, normalizedColumnNames, suggestionsAllowed])
  const primaryQuickStartView = quickStartViews[0] || null
  const secondaryQuickStartViews = quickStartViews.slice(1)

  const nextStepCards = useMemo(() => {
    if (!summary?.filename) {
      return [
        { title: '1. Subir dataset', detail: 'Empieza cargando un CSV/XLSX para que Universal detecte estructura y primeras lecturas.' },
        { title: '2. Revisar calidad', detail: 'Comprueba si fechas, importes y columnas se han entendido bien.' },
        { title: '3. Crear una vista', detail: 'Usa una recomendaciÃ³n rÃ¡pida antes de entrar en la configuraciÃ³n avanzada.' }
      ]
    }
    if (looksLikePayrollDataset) {
      return [
        { title: '1. Lee el coste mensual', detail: 'Empieza por Gross_Pay o Net_Pay por mes para entender la pelÃ­cula general.' },
        { title: '2. Mira quiÃ©n pesa mÃ¡s', detail: 'Compara Role o Employee_Name para detectar concentraciÃ³n salarial.' },
        { title: '3. Explica el coste empresa', detail: 'AÃ±ade Employer_CPP o Employer_EI para contar el coste oculto de nÃ³mina.' }
      ]
    }
    return [
      { title: '1. Revisa la calidad', detail: 'Si el score es bueno, confÃ­a en las vistas rÃ¡pidas; si no, corrige antes de interpretar.' },
      { title: '2. Usa una sugerencia AUTO', detail: 'La forma mÃ¡s rÃ¡pida de sacar valor es empezar por una lectura ya propuesta.' },
      { title: '3. Abre avanzado solo si hace falta', detail: 'La configuraciÃ³n manual queda para casos raros o preguntas muy concretas.' }
    ]
  }, [looksLikePayrollDataset, summary?.filename])

  const legacyUniversalDecisionState = useMemo(() => {
    if (!companyId) {
      return {
        title: 'Selecciona una empresa',
        detail: 'Activa primero la empresa gestionada para poder leer un dataset y guardar una vista Ãºtil.'
      }
    }
    if (!summary?.filename) {
      return {
        title: 'Sin dataset activo',
        detail: 'Sube un CSV o XLSX y deja la exploraciÃ³n tÃ©cnica para despuÃ©s: primero necesitamos una base legible.'
      }
    }
    if (looksLikePayrollDataset) {
      return {
        title: 'Dataset de nÃ³minas detectado',
        detail: 'Universal ya estÃ¡ orientado a coste salarial, neto pagado y coste empresa. Empieza por una vista sencilla.'
      }
    }
    if (quickStartViews.length) {
      return {
        title: 'Lectura lista para arrancar',
        detail: `Ya tienes ${quickStartViews.length} vista${quickStartViews.length === 1 ? '' : 's'} recomendada${quickStartViews.length === 1 ? '' : 's'} para sacar valor sin entrar aÃºn en configuraciÃ³n manual.`
      }
    }
    return {
      title: 'Dataset activo',
      detail: 'Empieza por calidad e insights, y baja al constructor solo si la lectura rÃ¡pida no responde la pregunta.'
    }
  }, [companyId, looksLikePayrollDataset, quickStartViews.length, summary?.filename])

  const universalDecisionState = useMemo(() => {
    if (!companyId) {
      return {
        title: 'Selecciona una empresa',
        detail: 'Activa primero la empresa gestionada para poder leer un dataset y guardar una vista util.'
      }
    }
    if (!summary?.filename) {
      return {
        title: 'Sin dataset activo',
        detail: 'Sube un CSV o XLSX y deja la exploracion tecnica para despues: primero necesitamos una base legible.'
      }
    }
    if (isAnnualBudgetDiagnosis(intakeDiagnosis)) {
      return {
        title: intakeDisplayLabel(intakeDiagnosis, 'Parece un plan anual'),
        detail: intakeDetail(intakeDiagnosis, 'Este fichero encaja mejor en Plan anual que en Universal.')
      }
    }
    if (intakeDiagnosisKind === 'CASH_TRANSACTIONS') {
      return {
        title: intakeDisplayLabel(intakeDiagnosis, 'Parece un fichero de caja'),
        detail: intakeDetail(intakeDiagnosis, 'La lectura natural es Caja si quieres cierre mensual.')
      }
    }
    if (intakeDiagnosisKind === 'TRIBUNAL_PORTFOLIO') {
      return {
        title: intakeDisplayLabel(intakeDiagnosis, 'Parece una cartera operativa'),
        detail: intakeDetail(intakeDiagnosis, 'La ruta natural es Tribunal antes que un dashboard generico.')
      }
    }
    if (looksLikePayrollDataset) {
      return {
        title: 'Dataset de nominas detectado',
        detail: 'Universal ya esta orientado a coste salarial, neto pagado y coste empresa. Empieza por una vista sencilla.'
      }
    }
    if (intakeDiagnosisKind === 'ACCOUNTING_LEDGER' || intakeDiagnosisKind === 'PAYROLL_DATASET') {
      return {
        title: intakeDisplayLabel(intakeDiagnosis, 'Lectura lista para arrancar'),
        detail: intakeDetail(intakeDiagnosis, 'Universal ya puede abrir una lectura util de esta base.')
      }
    }
    if (quickStartViews.length) {
      return {
        title: 'Lectura lista para arrancar',
        detail: `Ya tienes ${quickStartViews.length} vista${quickStartViews.length === 1 ? '' : 's'} recomendada${quickStartViews.length === 1 ? '' : 's'} para sacar valor sin entrar aun en configuracion manual.`
      }
    }
    return legacyUniversalDecisionState
  }, [companyId, intakeDiagnosis, intakeDiagnosisKind, legacyUniversalDecisionState, looksLikePayrollDataset, quickStartViews.length, summary?.filename])

  const executiveInsights = useMemo(() => {
    if (!Array.isArray(insights) || !insights.length) return []
    const preferred = ['warning', 'advisor', 'opportunity', 'info']
    return [...insights]
      .sort((a: any, b: any) => {
        const aIdx = preferred.indexOf(String(a?.level || '').toLowerCase())
        const bIdx = preferred.indexOf(String(b?.level || '').toLowerCase())
        return (aIdx === -1 ? 99 : aIdx) - (bIdx === -1 ? 99 : bIdx)
      })
      .filter((item: any, index: number, arr: any[]) => arr.findIndex((other: any) => String(other?.title || '') === String(item?.title || '')) === index)
      .slice(0, 3)
  }, [insights])

  const legacyPrimaryAction = useMemo(() => {
    if (!summary?.filename) {
      return {
        label: 'Subir dataset',
        helper: 'Empieza por la carga y deja la configuraciÃ³n avanzada para despuÃ©s.',
        action: () => datasetRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
      }
    }
    if (quickStartViews.length) {
      return {
        label: 'Abrir vista sugerida',
        helper: 'La forma mÃ¡s rÃ¡pida de sacar valor es abrir una lectura ya propuesta.',
        action: () => previewPreset(quickStartViews[0].request, 'Vista sugerida cargada.')
      }
    }
    return {
      label: 'Revisar calidad',
      helper: 'Si la base no estÃ¡ limpia, todo lo demÃ¡s pierde valor.',
      action: () => supportRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  }, [activeImportId, companyId, quickStartViews, summary?.filename])

  const primaryAction = useMemo(() => {
    if (!summary?.filename) {
      return {
        label: 'Subir dataset',
        helper: 'Empieza por la carga y deja la configuracion avanzada para despues.',
        action: () => datasetRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
      }
    }
    if (isAnnualBudgetDiagnosis(intakeDiagnosis)) {
      return {
        label: intakePrimaryActionLabel(intakeDiagnosis, 'Abrir plan anual'),
        helper: 'Este fichero encaja mejor como presupuesto anual que como vista libre.',
        action: () => navigate('/budget')
      }
    }
    if (intakeDiagnosisKind === 'CASH_TRANSACTIONS') {
      return {
        label: intakePrimaryActionLabel(intakeDiagnosis, 'Usar modulo Caja'),
        helper: 'Si quieres cierre mensual, vuelve a subirlo por Caja con un periodo operativo claro.',
        action: () => navigate(intakeRecommendedRoute(intakeDiagnosis, '/imports?mode=transactions'))
      }
    }
    if (intakeDiagnosisKind === 'TRIBUNAL_PORTFOLIO') {
      return {
        label: intakePrimaryActionLabel(intakeDiagnosis, 'Abrir Tribunal'),
        helper: 'Este dataset encaja mejor en cartera operativa que en un constructor libre.',
        action: () => navigate(intakeRecommendedRoute(intakeDiagnosis, '/tribunal'))
      }
    }
    if (quickStartViews.length) {
      return {
        label: 'Abrir vista sugerida',
        helper: 'La forma mas rapida de sacar valor es abrir una lectura ya propuesta.',
        action: () => previewPreset(quickStartViews[0].request, 'Vista sugerida cargada.')
      }
    }
    return legacyPrimaryAction
  }, [intakeDiagnosis, intakeDiagnosisKind, legacyPrimaryAction, navigate, quickStartViews, summary?.filename])

  const crossDefaults = useMemo(() => {
    const cols = Array.isArray(columns) ? columns : []
    const dateCol =
      cols.find((c: any) => isTemporalColumn(c) || c?.dateMin || (c?.dateSeries || []).length)?.name ||
      ''
    const numCol =
      cols.find((c: any) => isMeasureColumn(c) || c?.mean != null || c?.median != null)?.name || ''
    const catCol =
      cols.find((c: any) => isCategoryColumn(c) && Number(c?.uniqueCount || 0) > 1)?.name || ''

    const dateCandidates = cols
      .filter((c: any) => isTemporalColumn(c) || c?.dateMin || (c?.dateSeries || []).length)
      .map((c: any) => String(c?.name || ''))
      .filter(Boolean)
    const numCandidates = cols
      .filter((c: any) => isMeasureColumn(c) || c?.mean != null || c?.median != null)
      .map((c: any) => String(c?.name || ''))
      .filter(Boolean)
    const catCandidates = cols
      .filter((c: any) => isCategoryColumn(c) && Number(c?.uniqueCount || 0) > 1)
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
      aggregationMode: 'SUM_AMOUNT',
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
      aggregationMode: 'SUM_AMOUNT',
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

  const pickColumnsByName = (items: any[], preferredNames: string[], fallbackCount: number) => {
    const normalizedPreferred = preferredNames.map((name) => name.trim().toLowerCase())
    const preferred = normalizedPreferred
      .map((name) => items.find((item) => String(item?.name || '').trim().toLowerCase() === name))
      .filter(Boolean)
    const seen = new Set(preferred.map((item) => String(item?.name || '').trim().toLowerCase()))
    const fallback = items.filter((item) => !seen.has(String(item?.name || '').trim().toLowerCase()))
    return [...preferred, ...fallback].slice(0, fallbackCount)
  }

  const payrollNumericColumns = pickColumnsByName(
    columns.filter((c: any) => isMeasureColumn(c)),
    ['gross_pay', 'net_pay', 'employer_cpp', 'employer_ei', 'federal_tax', 'provincial_tax', 'cpp', 'ei'],
    4
  )
  const payrollDateColumns = pickColumnsByName(columns.filter((c: any) => isTemporalColumn(c)), ['pay_date'], 1)
  const payrollCategoryColumns = pickColumnsByName(
    columns.filter((c: any) => isCategoryColumn(c) && (c.topValues?.length || 0) > 0),
    ['role', 'employee_name'],
    2
  )

  const numericColumns = looksLikePayrollDataset
    ? payrollNumericColumns
    : columns.filter((c: any) => isMeasureColumn(c)).slice(0, 3)
  const dateColumns = looksLikePayrollDataset
    ? payrollDateColumns
    : columns.filter((c: any) => isTemporalColumn(c)).slice(0, 2)
  const categoricalColumns = looksLikePayrollDataset
    ? payrollCategoryColumns
    : columns.filter((c: any) => isCategoryColumn(c) && (c.topValues?.length || 0) > 0).slice(0, 6)
  const topCorrelations = correlations.slice(0, 5)
  const hiddenNumericColumnsCount = Math.max(0, columns.filter((c: any) => isMeasureColumn(c)).length - numericColumns.length)
  const hiddenCategoryColumnsCount = Math.max(
    0,
    columns.filter((c: any) => isCategoryColumn(c) && (c.topValues?.length || 0) > 0).length - categoricalColumns.length
  )

  const dateCols = columns.filter((c: any) => isTemporalColumn(c)).map((c: any) => String(c.name))
  const numberCols = columns.filter((c: any) => isMeasureColumn(c)).map((c: any) => String(c.name))
  const textCols = columns.filter((c: any) => isCategoryColumn(c)).map((c: any) => String(c.name))
  const allCols = columns.map((c: any) => String(c.name))
  const detectedEntities = Array.isArray(summary?.detectedEntities) ? summary.detectedEntities : []

  const builderAggregationOptions = useMemo(() => {
    if (builderType === 'SCATTER') {
      return [{ value: 'ROW_COUNT' as AggregationMode, label: 'Puntos', detail: 'Scatter trabaja a nivel de fila y mantiene cada punto individual.' }]
    }

    const options: Array<{ value: AggregationMode; label: string; detail: string }> = []
    const seen = new Set<string>()
    const add = (value: AggregationMode, detail: string) => {
      if (seen.has(value)) return
      seen.add(value)
      options.push({ value, label: aggregationLabel(value), detail })
    }

    add('ROW_COUNT', 'Cuenta filas vÃ¡lidas del recorte activo.')

    if (detectedEntities.some((entity: any) => String(entity?.entityType || '').toUpperCase() === 'ENTRY' && entity?.keyColumn)) {
      add('DISTINCT_ENTRY_COUNT', 'Cuenta asientos distintos sin duplicar lÃ­neas contables.')
    }
    if (detectedEntities.some((entity: any) => String(entity?.entityType || '').toUpperCase() === 'DOCUMENT' && entity?.keyColumn)) {
      add('DISTINCT_DOCUMENT_COUNT', 'Cuenta documentos Ãºnicos para no medir varias veces el mismo soporte.')
    }
    if (detectedEntities.some((entity: any) => String(entity?.entityType || '').toUpperCase() === 'INVOICE' && entity?.keyColumn)) {
      add('DISTINCT_INVOICE_COUNT', 'Cuenta facturas distintas aunque el dataset venga a nivel de lÃ­nea.')
    }
    if (detectedEntities.some((entity: any) => String(entity?.entityType || '').toUpperCase() === 'PARTY' && entity?.keyColumn)) {
      add('DISTINCT_PARTY_COUNT', 'Cuenta terceros distintos para leer concentraciÃ³n de clientes o proveedores.')
    }

    const hasNumericValue = numberCols.length > 0 || columns.some((column: any) => columnSupportsAggregation(column, 'SUM_DISTINCT_VALUE'))
    if (hasNumericValue) {
      add('SUM_AMOUNT', 'Suma importes. Si hay clave documental, Universal evita el doble conteo.')
      add('AVG_VALUE', 'Calcula la media de la columna de valor seleccionada.')
    }
    if (columns.some((column: any) => String(column?.semanticType || '').toUpperCase() === 'DEBIT_AMOUNT' || columnSupportsAggregation(column, 'SUM_DEBIT'))) {
      add('SUM_DEBIT', 'Suma el debe detectado semÃ¡nticamente.')
    }
    if (columns.some((column: any) => String(column?.semanticType || '').toUpperCase() === 'CREDIT_AMOUNT' || columnSupportsAggregation(column, 'SUM_CREDIT'))) {
      add('SUM_CREDIT', 'Suma el haber detectado semÃ¡nticamente.')
    }
    if (
      columns.some(
        (column: any) =>
          String(column?.semanticType || '').toUpperCase() === 'SIGNED_AMOUNT' || columnSupportsAggregation(column, 'NET_BALANCE')
      )
    ) {
      add('NET_BALANCE', 'Calcula saldo neto usando importe firmado o debe menos haber.')
    }

    return options
  }, [builderType, columns, detectedEntities, numberCols.length])

  const builderNeedsValue = aggregationNeedsValue(builderAggMode)

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

  useEffect(() => {
    if (!builderAggregationOptions.length) return
    if (!builderAggregationOptions.some((option) => option.value === builderAggMode)) {
      setBuilderAggMode(builderAggregationOptions[0].value)
    }
  }, [builderAggMode, builderAggregationOptions])

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
  const universalSupportOpen = !summary?.filename || !!uploadError

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
        if (!cancelled) setUploadError(e?.message || 'No se pudo previsualizar.')
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
      setRowsError(e?.message || 'No se pudo cargar.')
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
        valueColumn: builderNeedsValue ? builderValueCol || undefined : undefined,
        xColumn: builderType === 'SCATTER' || builderType === 'HEATMAP' ? builderXCol : undefined,
        yColumn: builderType === 'SCATTER' || builderType === 'HEATMAP' ? builderYCol : undefined,
        aggregationMode: builderAggMode,
        aggregation: builderAggMode === 'AVG_VALUE' ? 'avg' : 'sum',
        filters: filters.length ? (filters as any) : undefined,
        topN: builderType === 'PIVOT_MONTHLY' || builderType === 'HEATMAP' ? builderTopN : undefined,
        maxPoints: builderType === 'SCATTER' ? builderMaxPoints : undefined
      }
      setBuilderLastRequest(body)
      const res = await previewUniversalViewForImport(companyId as number, body, activeImportId)
      setBuilderPreview(res)
    } catch (e: any) {
      setBuilderPreview(null)
      setBuilderError(e?.message || 'No se pudo previsualizar.')
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
            ? 'Ranking categorÃ­as'
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
        valueColumn: builderNeedsValue ? builderValueCol || undefined : undefined,
        xColumn: builderType === 'SCATTER' || builderType === 'HEATMAP' ? builderXCol : undefined,
        yColumn: builderType === 'SCATTER' || builderType === 'HEATMAP' ? builderYCol : undefined,
        aggregationMode: builderAggMode,
        aggregation: builderAggMode === 'AVG_VALUE' ? 'avg' : 'sum',
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

  async function previewPreset(request: UniversalViewRequest, successMessage = 'Preview cargada.') {
    if (!companyId) return
    setBuilderLoading(true)
    setBuilderError(null)
    try {
      const res = await previewUniversalViewForImport(companyId as number, request, activeImportId)
      setBuilderPreview(res)
      setBuilderLastRequest(request)
      toast.push({ tone: 'success', title: 'Vista previa', message: successMessage })
    } catch (e: any) {
      setBuilderPreview(null)
      setBuilderError(e?.message || 'No se pudo previsualizar.')
      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo abrir la vista.' })
    } finally {
      setBuilderLoading(false)
    }
  }

  async function createPreset(request: UniversalViewRequest, fallbackName: string) {
    if (!companyId) return
    try {
      const created = await createUniversalViewForImport(
        companyId as number,
        {
          ...request,
          name: (request?.name || fallbackName || 'Dashboard').trim()
        },
        activeImportId
      )
      await queryClient.invalidateQueries({ queryKey: ['universal-views', companyId] })
      setSelectedViewId(created.id)
      toast.push({ tone: 'success', title: 'Dashboard', message: 'Plantilla creada.' })
    } catch (e: any) {
      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo guardar.' })
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
      setBuilderError(e?.message || 'No se pudo cargar.')
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
          ? !!builderXCol && !!builderYCol && (!builderNeedsValue || !!builderValueCol)
          : builderType === 'PIVOT_MONTHLY'
            ? !!builderDateCol && !!builderCatCol && (!builderNeedsValue || !!builderValueCol)
            : builderType === 'TIME_SERIES'
              ? !!builderDateCol && (!builderNeedsValue || !!builderValueCol)
              : builderType === 'CATEGORY_BAR'
                ? !!builderCatCol && (!builderNeedsValue || !!builderValueCol)
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
        title="Universal"
        subtitle="Sube una base, valida la lectura y abre una vista util."
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
                    title="Elige dataset"
                  >
                    <option value="">Ultimo (auto)</option>
                    {importsList.slice(0, 20).map((imp) => (
                      <option key={imp.id} value={imp.id}>
                        #{imp.id}  -  {imp.filename}
                      </option>
                    ))}
                  </select>
                  <div className="upload-hint">
                    {activeImportId ? (
                      <>
                        {activeImport?.filename || `Import #${activeImportId}`}  - {activeImport?.createdAt ? formatDateTime(activeImport.createdAt) : EMPTY_VALUE}
                      </>
                    ) : summary?.filename ? (
                      <>
                        {summary.filename}  -  {summary.createdAt ? formatDateTime(summary.createdAt) : EMPTY_VALUE}  - {summary.rowCount} filas
                      </>
                    ) : (
                      EMPTY_VALUE
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
          <h3 className="m-0">Estado actual</h3>
          <span className="upload-hint">Lectura principal y siguiente paso.</span>
        </div>
        <div className="universal-executive-shell mt-12">
          <div className="universal-executive-main">
            <div className="upload-hint">Situacion</div>
            <div className="fw-900 mt-1">{universalDecisionState.title}</div>
            <div className="upload-hint mt-1">{universalDecisionState.detail}</div>
            <div className="universal-executive-metrics">
              <div className="universal-executive-metric">
                <span>Calidad</span>
                <strong>
                  {summary?.filename
                    ? universalQuality
                      ? `${universalQuality.score}/100`
                      : 'Pendiente'
                    : 'Sin datos'}
                </strong>
                <small>
                  {summary?.filename
                    ? universalQuality
                      ? qualitySummary(universalQuality)?.label || 'Lista para revisar'
                      : 'Pendiente de leer'
                    : 'Sin base'}
                </small>
              </div>
              <div className="universal-executive-metric">
                <span>Unidad</span>
                <strong>{summary?.rowGranularity ? semanticLabel(summary.rowGranularity) : 'Sin granularidad'}</strong>
                <small>
                  {summary?.filename
                    ? intakeDiagnosis
                      ? `${intakeDisplayLabel(intakeDiagnosis, 'Base detectada')} · ${entitySummary(summary)}`
                      : entitySummary(summary)
                    : 'Aun no hay entidad detectada.'}
                </small>
              </div>
            </div>
          </div>
          <div className="universal-executive-action">
            <div className="upload-hint">Siguiente paso</div>
            <strong>{primaryAction.label}</strong>
            <p>{primaryAction.helper}</p>
            <Button onClick={primaryAction.action}>{primaryAction.label}</Button>
          </div>
        </div>

        <div className="universal-executive-insights mt-12">
          {!executiveInsights.length ? (
            <div className="universal-playbook-card">
              <strong>Sin lectura automatica</strong>
              <p>Sube un CSV o XLSX para empezar.</p>
            </div>
          ) : (
            executiveInsights.map((it: any, idx: number) => (
              <div key={`${it.title}-${idx}`} className={`universal-executive-insight tone-${insightTone(it.level)}`}>
                <span className="badge">{insightAudienceLabel(it.level)}</span>
                <strong>{it.title}</strong>
                <p>{trimInsightMessage(it.message)}</p>
              </div>
            ))
          )}
        </div>
      </div>

      <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">1. Cargar dataset</h3>
          <span className="upload-hint">Primero sube la base.</span>
        </div>
        <div className="universal-executive-shell mt-12">
          <div className="universal-executive-main">
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
            {!companyId ? (
              <div className="mt-12">
                <Alert tone="warning">Selecciona una empresa.</Alert>
              </div>
            ) : null}
            {uploadError ? (
              <div className="mt-12">
                <Alert tone="danger">{uploadError}</Alert>
              </div>
            ) : null}
            {uploadOk ? (
              <div className="mt-12">
                <Alert tone="success">{uploadOk}</Alert>
              </div>
            ) : null}
            {likelyBadHeaders ? (
              <div className="mt-12">
                <Alert tone="warning" title="Revisa la cabecera">
                  El Excel parece tener la fila de encabezado mal tomada.
                </Alert>
              </div>
            ) : null}
          </div>
          <div className="universal-executive-action">
            <div className="upload-hint">Estado</div>
            <strong>{summary?.filename ? 'Base cargada' : 'Sin base'}</strong>
            <p>
              {summary?.filename
                ? `${summary.filename} - ${summary.rowCount ?? 0} filas - ${summary.columnCount ?? 0} columnas`
                : 'Aun no hay base activa.'}
            </p>
            {file && isXlsx ? (
              <details className="universal-inline-details">
                <summary>Ajustar XLSX</summary>
                <div className="mt-12">
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
                      </label>
                    </div>
                  ) : null}
                </div>
              </details>
            ) : null}
          </div>
        </div>
      </div>

      <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">2. Abrir una vista util</h3>
          <span className="upload-hint">Una sola recomendacion visible.</span>
        </div>
        <div className="mt-12">
          {!primaryQuickStartView ? (
            <div className="universal-playbook-card">
              <strong>Sin recomendacion</strong>
              <p>Primero carga una base legible.</p>
            </div>
          ) : (
            <div className="card card-pad-sm universal-preset-card universal-preset-card-primary">
              <div className="row row-between row-center row-wrap gap-3">
                <div>
                  <div className="upload-hint">Vista recomendada</div>
                  <div className="fw-800">{primaryQuickStartView.title}</div>
                  <div className="upload-hint">{primaryQuickStartView.description}</div>
                </div>
                <div className="row row-center gap-2 row-wrap">
                  <Button size="sm" onClick={() => previewPreset(primaryQuickStartView.request)}>
                    Abrir recomendacion
                  </Button>
                  <Button variant="secondary" size="sm" onClick={() => createPreset(primaryQuickStartView.request, primaryQuickStartView.title)}>
                    Guardar vista
                  </Button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {builderPreview ? (
        <div ref={previewRef} className="card section soft">
          <div className="mini-row row-baseline">
            <h3 className="m-0">3. Validar la lectura</h3>
            <span className="upload-hint">Solo el grafico principal.</span>
          </div>
          <div className="card mt-12">
            <h3 className="h3-reset">Resultado</h3>
            {String(builderPreview.type || '').toUpperCase() === 'KPI_CARDS' ? (
              <div className="grid grid-min-160 grid-gap-12">
                {builderPreview.labels.map((k, idx) => (
                  <div key={`${k}-${idx}`} className="card soft">
                    <div className="upload-hint ttu">{k}</div>
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
                option={
                  builderPreview.type === 'CATEGORY_BAR'
                    ? ({
                        tooltip: { trigger: 'axis' },
                        xAxis: { type: 'category', data: builderPreview.labels },
                        yAxis: { type: 'value' },
                        series: [{ name: (builderPreview.series as any)?.[0]?.name || 'Valor', type: 'bar', data: (builderPreview.series as any)?.[0]?.data || [] }]
                      } as any)
                    : ({
                        tooltip: { trigger: 'axis' },
                        xAxis: { type: 'category', data: builderPreview.labels },
                        yAxis: { type: 'value' },
                        series: [{ name: (builderPreview.series as any)?.[0]?.name || 'Valor', type: 'line', smooth: true, data: (builderPreview.series as any)?.[0]?.data || [] }]
                      } as any)
                }
              />
            )}
            <div className="mt-12">
              <div className="upload-hint">Que mide esta vista</div>
              <div className="fw-800 mt-1">
                {aggregationLabel((builderPreview.meta as any)?.aggregationMode || (builderPreview.meta as any)?.aggregation || '-')}
              </div>
              <div className="upload-hint mt-1">
                {builderNarrative.see?.[0] || 'Lectura lista para revisar.'}
              </div>
            </div>
            {builderLastRequest && canUseEvidence ? (
              <details className="universal-inline-details mt-12">
                <summary>Ver soporte</summary>
                <div className="row row-wrap gap-2 mt-12">
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={loadBuilderEvidence}
                    disabled={!companyId || builderEvidenceLoading}
                    loading={builderEvidenceLoading}
                  >
                    Ver filas de soporte
                  </Button>
                  {builderEvidence?.rows?.length ? (
                    <Button variant="secondary" size="sm" onClick={() => downloadEvidenceCsv(builderEvidence)}>
                      Descargar soporte CSV
                    </Button>
                  ) : null}
                </div>
              </details>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  )


}
