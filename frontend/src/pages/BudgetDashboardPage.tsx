import { useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  getBudgetAnalysis,
  downloadBudgetLongCsv,
  downloadBudgetReportPdf,
  getBudgetItemDetail,
  getBudgetLongPreview,
  getUserRole,
  type BudgetAnalysisBundle,
  type BudgetItemDetail,
  type BudgetItemInsight,
  type BudgetLongInsights,
  type BudgetLongPreview,
  type BudgetSummary,
  type CashflowSummary
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import Section from '../components/ui/Section'
import EChart from '../components/charts/EChart'
import { formatMoney, formatText, normalizeText } from '../utils/format'
import { buildBudgetCashChartSeries } from '../utils/budgetCashChart'
import { useToast } from '../components/ui/ToastProvider'

function isGoldPlan(planRaw?: string | null) {
  const normalized = String(planRaw || '')
    .trim()
    .toUpperCase()
    .replace(/\s+/g, '')
    .replace(/[._]/g, '-')
  return normalized === 'GOLD' || normalized === 'PLATINUM' || normalized.startsWith('GOLD-') || normalized.startsWith('PLATINUM-')
}

function fmtDelta(value?: number | null) {
  if (value == null || Number.isNaN(Number(value))) return '-'
  const num = Number(value)
  return `${num > 0 ? '+' : ''}${formatMoney(num)}`
}

function rowKey(item: BudgetItemInsight, index: number) {
  return item.canonicalRowId || item.canonicalIdentity || `${item.code || 'row'}-${index}`
}

function humanizeTechnicalValue(value?: string | null) {
  const raw = String(value || '').trim()
  if (!raw) return '-'
  const mapped: Record<string, string> = {
    CANONICAL_ROW_ID: 'Coincidencia exacta',
    CANONICAL_IDENTITY: 'Coincidencia canonica',
    LABEL_MATCH: 'Coincidencia por etiqueta',
    CODE_MATCH: 'Coincidencia por codigo',
    FALLBACK: 'Coincidencia de respaldo'
  }
  if (mapped[raw]) return mapped[raw]
  if (/^[A-Z0-9_]+$/.test(raw)) {
    return raw
      .toLowerCase()
      .replace(/_/g, ' ')
      .replace(/\b\w/g, (letter) => letter.toUpperCase())
  }
  return formatText(raw)
}

function buildDriverDetailChart(detail: BudgetItemDetail) {
  return {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: detail.months.map((month) => month.monthLabel) },
    yAxis: { type: 'value', axisLabel: { formatter: (value: number) => formatMoney(value) } },
    series: [
      {
        name: detail.label || 'Partida',
        type: 'bar',
        barMaxWidth: 28,
        data: detail.months.map((month) => Number(month.amount || 0))
      }
    ]
  }
}

const DRIVER_DONUT_LIMIT = 5
const DRIVER_DONUT_COLORS = [
  { color: '#60a5fa', glow: 'rgba(96, 165, 250, 0.28)' },
  { color: '#14b8a6', glow: 'rgba(20, 184, 166, 0.26)' },
  { color: '#a855f7', glow: 'rgba(168, 85, 247, 0.26)' },
  { color: '#f97316', glow: 'rgba(249, 115, 22, 0.24)' },
  { color: '#facc15', glow: 'rgba(250, 204, 21, 0.24)' },
  { color: '#38bdf8', glow: 'rgba(56, 189, 248, 0.24)' },
  { color: '#64748b', glow: 'rgba(100, 116, 139, 0.18)' }
]

type DriverDonutPanel = {
  key: string
  theme: 'income' | 'expense'
  title: string
  subtitle: string
  centerLabel: string
  concentrationPct: number
  concentrationReading: string
  slices: Array<{
    key: string
    name: string
    value: number
    rawValue: number
    pct: number
    color: string
    glow: string
  }>
  option: Record<string, unknown>
}

function buildDriverExecutiveNarrative(panels: DriverDonutPanel[]) {
  const incomePanel = panels.find((panel) => panel.theme === 'income')
  const expensePanel = panels.find((panel) => panel.theme === 'expense')

  const incomeReading = incomePanel
    ? incomePanel.concentrationPct >= 70
      ? 'Los ingresos dependen de pocas partidas fuertes.'
      : incomePanel.concentrationPct >= 50
        ? 'Los ingresos mantienen una concentracion intermedia.'
        : 'Los ingresos se reparten de forma bastante diversificada.'
    : ''

  const expenseReading = expensePanel
    ? expensePanel.concentrationPct >= 70
      ? 'El coste operativo esta muy concentrado y merece seguimiento cercano.'
      : expensePanel.concentrationPct >= 50
        ? 'El coste operativo muestra focos claros de presion.'
        : 'El coste operativo esta relativamente repartido.'
    : ''

  return [incomeReading, expenseReading].filter(Boolean).join(' ')
}

function buildDriverDonutVisual(
  insights: BudgetLongInsights | null | undefined,
  config: {
    key: string
    theme: 'income' | 'expense'
    title: string
    subtitle: string
    centerLabel: string
    centerAccent: string
    centerSoft: string
    kinds: string[]
    colors: typeof DRIVER_DONUT_COLORS
  }
): DriverDonutPanel | null {
  const drivers = Array.isArray(insights?.topDrivers) ? [...insights.topDrivers] : []
  if (!drivers.length) return null

  const sorted = drivers
    .filter((item) => config.kinds.includes(String(item.financialNature || '').toUpperCase()))
    .filter((item) => Math.abs(Number(item.annualTotal || 0)) > 0 || Number(item.shareAbsPct || 0) > 0)
    .sort((left, right) => Math.abs(Number(right.annualTotal || 0)) - Math.abs(Number(left.annualTotal || 0)))

  if (!sorted.length) return null

  const featured = sorted.slice(0, DRIVER_DONUT_LIMIT)
  const remainder = sorted.slice(DRIVER_DONUT_LIMIT)

  const slices = featured.map((item, index) => {
    const tone = config.colors[index % config.colors.length]
    return {
      key: rowKey(item, index),
      name: formatText(item.label || item.code || `Driver ${index + 1}`),
      value: Math.abs(Number(item.annualTotal || 0)),
      rawValue: Number(item.annualTotal || 0),
      pct: Number(item.shareAbsPct || 0),
      color: tone.color,
      glow: tone.glow
    }
  })

  if (remainder.length) {
    const tone = config.colors[config.colors.length - 1]
    const annualAbs = remainder.reduce((sum, item) => sum + Math.abs(Number(item.annualTotal || 0)), 0)
    const annualRaw = remainder.reduce((sum, item) => sum + Number(item.annualTotal || 0), 0)
    const totalAbs = sorted.reduce((sum, item) => sum + Math.abs(Number(item.annualTotal || 0)), 0)
    const pct = totalAbs > 0 ? (annualAbs / totalAbs) * 100 : 0
    if (annualAbs > 0 || pct > 0) {
      slices.push({
        key: 'otros',
        name: 'Otros',
        value: annualAbs,
        rawValue: annualRaw,
        pct,
        color: tone.color,
        glow: tone.glow
      })
    }
  }

  const totalAbs = sorted.reduce((sum, item) => sum + Math.abs(Number(item.annualTotal || 0)), 0)
  const top3Abs = sorted.slice(0, 3).reduce((sum, item) => sum + Math.abs(Number(item.annualTotal || 0)), 0)
  const centerPct = totalAbs > 0 ? (top3Abs / totalAbs) * 100 : 0
  const concentrationReading =
    centerPct >= 70
      ? 'Mas concentrado'
      : centerPct >= 50
        ? 'Concentracion media'
        : 'Mas diversificado'

  return {
    key: config.key,
    theme: config.theme,
    title: config.title,
    subtitle: config.subtitle,
    centerLabel: config.centerLabel,
    concentrationPct: centerPct,
    concentrationReading,
    slices,
    option: {
      tooltip: {
        trigger: 'item',
        formatter: (params: any) => {
          const slice = params?.data as { name?: string; rawValue?: number; pct?: number } | undefined
          return `${formatText(slice?.name || '')}\nImporte anual: ${formatMoney(slice?.rawValue || 0)}\nPeso absoluto: ${Number(slice?.pct || 0).toFixed(2)}%`
        }
      },
      legend: { show: false },
      graphic: [
        {
          type: 'group',
          left: 'center',
          top: 'middle',
          z: 100,
          children: [
            {
              type: 'text',
              x: -52,
              y: -34,
              style: {
                text: config.centerLabel,
                fill: config.centerSoft,
                font: '600 12px system-ui, sans-serif'
              }
            },
            {
              type: 'text',
              x: -18,
              y: -12,
              style: {
                text: 'Top 3',
                fill: config.centerAccent,
                font: '700 14px system-ui, sans-serif'
              }
            },
            {
              type: 'text',
              x: -44,
              y: 14,
              style: {
                text: `${centerPct.toFixed(2)}%`,
                fill: config.centerAccent,
                font: '700 28px system-ui, sans-serif'
              }
            }
          ]
        }
      ],
      series: [
        {
          name: config.title,
          type: 'pie',
          radius: ['60%', '82%'],
          center: ['50%', '52%'],
          startAngle: 90,
          minAngle: 3,
          avoidLabelOverlap: true,
          label: { show: false },
          labelLine: { show: false },
          itemStyle: {
            borderColor: 'rgba(8, 15, 28, 0.94)',
            borderWidth: 5,
            shadowBlur: 18,
            shadowColor: 'rgba(2, 8, 20, 0.48)'
          },
          emphasis: {
            scale: true,
            scaleSize: 10,
            itemStyle: {
              shadowBlur: 34,
              shadowColor: 'rgba(15, 23, 42, 0.68)'
            }
          },
          data: slices.map((slice) => ({
            name: slice.name,
            value: slice.value,
            rawValue: slice.rawValue,
            pct: slice.pct,
            itemStyle: {
              color: slice.color,
              shadowBlur: 22,
              shadowColor: slice.glow
            }
          }))
        }
      ]
    }
  }
}

export default function BudgetDashboardPage() {
  const { id: companyId, plan } = useCompanySelection()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const role = getUserRole()
  const isClient = role === 'CLIENTE'
  const hasGold = isGoldPlan(plan)
  const toast = useToast()
  const detailSectionRef = useRef<HTMLDivElement | null>(null)

  const [downloadingPdf, setDownloadingPdf] = useState(false)
  const [downloadingLongCsv, setDownloadingLongCsv] = useState(false)
  const [showLongPreview, setShowLongPreview] = useState(false)
  const [longPreviewLoading, setLongPreviewLoading] = useState(false)
  const [longPreviewError, setLongPreviewError] = useState('')
  const [longPreview, setLongPreview] = useState<BudgetLongPreview | null>(null)
  const [selectedItem, setSelectedItem] = useState<BudgetItemInsight | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState('')
  const [detail, setDetail] = useState<BudgetItemDetail | null>(null)
  const [hoveredDriverSlice, setHoveredDriverSlice] = useState<Record<string, number | null>>({})

  const { data: analysisData, error: summaryError } = useQuery({
    queryKey: ['budget-analysis', companyId],
    queryFn: () => getBudgetAnalysis(companyId as number),
    enabled: !!companyId && hasGold
  })

  const analysis = analysisData as BudgetAnalysisBundle | undefined
  const workflow = analysis?.workflow
  const summary = (analysis?.summary || undefined) as BudgetSummary | undefined
  const cashflow = (analysis?.cashflow || undefined) as CashflowSummary | undefined
  const longInsights = (analysis?.insights || undefined) as BudgetLongInsights | undefined
  const cashflowError = null
  const longInsightsError = null

  const months = summary?.months || []
  const sourceFilename = normalizeText(analysis?.sourceFilename || summary?.sourceFilename || workflow?.sourceFilename || '', '')
  const sourceSheetIndex = workflow?.sourceSheetIndex
  const sourceHeaderRow = workflow?.sourceHeaderRow
  const sourcePresent = Boolean(workflow?.sourcePresent || sourceFilename)
  const structureValidated = Boolean(workflow?.structureValidated || months.length)
  const annualInsightsReady = Boolean(workflow?.annualInsightsReady || months.length)
  const needsValidation = sourcePresent && !structureValidated
  const waitingForAnalysis = sourcePresent && structureValidated && !annualInsightsReady
  const cashMonths = cashflow?.months || []
  const cashByKey = new Map(cashMonths.map((month) => [month.monthKey, month]))
  const { labels, cashNet, closingBalance: cashBalance } = buildBudgetCashChartSeries(months, cashMonths)
  const income = months.map((month) => Number(month.income || 0))
  const expense = months.map((month) => Number(month.expense || 0))
  const margin = months.map((month) => Number(month.margin || 0))

  const lastMonth = months.length ? months[months.length - 1] : null
  const previousMonth = months.length > 1 ? months[months.length - 2] : null
  const marginDelta = lastMonth && previousMonth ? Number(lastMonth.margin || 0) - Number(previousMonth.margin || 0) : null
  const driverConcentrationPct = Number(longInsights?.concentrationTop3AbsPct || 0)
  const technicalStatusTitle = months.length
    ? 'Analisis tecnico activo'
    : needsValidation
      ? 'Validacion pendiente'
      : waitingForAnalysis
        ? 'Lectura en curso'
        : sourcePresent
          ? 'Carga anual detectada'
          : 'Sin plan anual'
  const technicalStatusDetail = months.length
    ? 'La lectura tecnica ya esta consolidada para revisar estructura, concentracion, ajustes y tesoreria.'
    : needsValidation
      ? 'El fichero anual esta dentro, pero falta fijar hoja y cabecera para convertirlo en una lectura fiable.'
      : waitingForAnalysis
        ? 'La estructura ya se detecto y la lectura tecnica todavia se esta completando.'
        : sourcePresent
          ? 'Existe una carga anual detectada, pero todavia no ofrece una lectura tecnica completa.'
          : 'Sube un presupuesto anual valido para activar el analisis tecnico.'
  const traceLabel =
    sourceSheetIndex != null || sourceHeaderRow != null
      ? `Hoja ${sourceSheetIndex != null ? Number(sourceSheetIndex) + 1 : '-'} | fila ${sourceHeaderRow ?? '-'}`
      : 'Sin traza fijada'
  const heroSummary = months.length
    ? [
        `${months.length} meses listos`,
        `${driverConcentrationPct.toFixed(2)}% Top 3`,
        `${formatMoney(cashflow?.endingBalance)} saldo final`
      ]
    : [technicalStatusTitle, traceLabel]

  const incomeExpenseChart = useMemo(
    () => ({
      tooltip: { trigger: 'axis' },
      legend: { data: ['Ingresos', 'OPEX', 'EBITDA'] },
      xAxis: { type: 'category', data: labels },
      yAxis: { type: 'value', axisLabel: { formatter: (value: number) => formatMoney(value) } },
      series: [
        { name: 'Ingresos', type: 'bar', data: income, barMaxWidth: 24 },
        { name: 'OPEX', type: 'bar', data: expense, barMaxWidth: 24 },
        { name: 'EBITDA', type: 'line', data: margin, smooth: true, lineStyle: { width: 3 } }
      ]
    }),
    [labels, income, expense, margin]
  )

  const cashChart = useMemo(
    () => ({
      tooltip: { trigger: 'axis' },
      legend: { data: ['Cash neto', 'Saldo final'] },
      xAxis: { type: 'category', data: labels },
      yAxis: { type: 'value', axisLabel: { formatter: (value: number) => formatMoney(value) } },
      series: [
        { name: 'Cash neto', type: 'line', data: cashNet, smooth: true, lineStyle: { width: 3 } },
        { name: 'Saldo final', type: 'line', data: cashBalance, smooth: true, lineStyle: { width: 3 } }
      ]
    }),
    [labels, cashNet, cashBalance]
  )

  const detailChart = useMemo(() => (detail?.months?.length ? buildDriverDetailChart(detail) : {}), [detail])
  const driverDonutPanels = useMemo(
    () =>
      [
        buildDriverDonutVisual(longInsights, {
          key: 'income',
          theme: 'income',
          title: 'Drivers de ingresos',
          subtitle: 'Concentracion de partidas que empujan la facturacion.',
          centerLabel: 'Ingresos',
          centerAccent: '#7dd3fc',
          centerSoft: 'rgba(125, 211, 252, 0.78)',
          kinds: ['REVENUE', 'OTHER_OPERATING_INCOME'],
          colors: [
            { color: '#6ea8ff', glow: 'rgba(110, 168, 255, 0.32)' },
            { color: '#27d3c3', glow: 'rgba(39, 211, 195, 0.3)' },
            { color: '#3ec8ff', glow: 'rgba(62, 200, 255, 0.28)' },
            { color: '#4ade80', glow: 'rgba(74, 222, 128, 0.24)' },
            { color: '#8b5cf6', glow: 'rgba(139, 92, 246, 0.24)' },
            { color: '#64748b', glow: 'rgba(100, 116, 139, 0.18)' }
          ]
        }),
        buildDriverDonutVisual(longInsights, {
          key: 'expense',
          theme: 'expense',
          title: 'Drivers de gastos',
          subtitle: 'Concentracion de partidas que mas presionan el OPEX.',
          centerLabel: 'Gastos',
          centerAccent: '#fdba74',
          centerSoft: 'rgba(253, 186, 116, 0.8)',
          kinds: ['OPEX'],
          colors: [
            { color: '#ff8a3d', glow: 'rgba(255, 138, 61, 0.3)' },
            { color: '#ff5d73', glow: 'rgba(255, 93, 115, 0.28)' },
            { color: '#f97316', glow: 'rgba(249, 115, 22, 0.28)' },
            { color: '#ef4444', glow: 'rgba(239, 68, 68, 0.26)' },
            { color: '#facc15', glow: 'rgba(250, 204, 21, 0.22)' },
            { color: '#64748b', glow: 'rgba(100, 116, 139, 0.18)' }
          ]
        })
      ].filter(Boolean) as DriverDonutPanel[],
    [longInsights]
  )
  const driverExecutiveNarrative = useMemo(() => buildDriverExecutiveNarrative(driverDonutPanels), [driverDonutPanels])

  const getDetailQueryKey = (canonicalRowId: string) => ['budget-item-detail', companyId, canonicalRowId]

  const prefetchDetail = (item: BudgetItemInsight) => {
    if (!companyId || !item.canonicalRowId) return
    void queryClient.prefetchQuery({
      queryKey: getDetailQueryKey(item.canonicalRowId),
      queryFn: () => getBudgetItemDetail(companyId as number, item.canonicalRowId as string),
      staleTime: 5 * 60 * 1000
    })
  }

  const handleOpenDetail = async (item: BudgetItemInsight) => {
    setSelectedItem(item)
    if (!companyId || !item.canonicalRowId) {
      setDetailError('Esta fila no trae identidad canonica suficiente para abrir el detalle.')
      return
    }
    detailSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    setDetailError('')
    const cachedDetail = queryClient.getQueryData<BudgetItemDetail>(getDetailQueryKey(item.canonicalRowId))
    if (cachedDetail) {
      setDetail(cachedDetail)
      setDetailLoading(false)
      return
    }
    setDetail(null)
    setDetailLoading(true)
    try {
      const nextDetail = await queryClient.fetchQuery({
        queryKey: getDetailQueryKey(item.canonicalRowId),
        queryFn: () => getBudgetItemDetail(companyId as number, item.canonicalRowId as string),
        staleTime: 5 * 60 * 1000
      })
      setDetail(nextDetail)
    } catch (error: any) {
      setDetailError(formatText(String(error?.message || error || 'No se pudo abrir el detalle mensual.')))
    } finally {
      setDetailLoading(false)
    }
  }

  const handleDetailKeyDown = (event: React.KeyboardEvent, item: BudgetItemInsight) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      void handleOpenDetail(item)
    }
  }

  const closeDetail = () => {
    setSelectedItem(null)
    setDetail(null)
    setDetailError('')
  }

  const handleDownloadPdf = async () => {
    if (!companyId) return
    setDownloadingPdf(true)
    try {
      const blob = await downloadBudgetReportPdf(companyId as number)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `budget-report-${companyId}.pdf`
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
      toast.push({ tone: 'success', title: 'PDF', message: 'Descarga iniciada.' })
    } catch (error: any) {
      toast.push({ tone: 'danger', title: 'Error', message: formatText(String(error?.message || error || 'No se pudo descargar el PDF.')) })
    } finally {
      setDownloadingPdf(false)
    }
  }

  const handleDownloadLongCsv = async () => {
    if (!companyId) return
    setDownloadingLongCsv(true)
    try {
      const blob = await downloadBudgetLongCsv(companyId as number)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `budget-long-${companyId}.csv`
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
      toast.push({ tone: 'success', title: 'CSV', message: 'Descarga iniciada.' })
    } catch (error: any) {
      toast.push({ tone: 'danger', title: 'Error', message: formatText(String(error?.message || error || 'No se pudo descargar el CSV largo.')) })
    } finally {
      setDownloadingLongCsv(false)
    }
  }

  const handlePreviewLong = async () => {
    if (!companyId) return
    setShowLongPreview(true)
    setLongPreviewError('')
    setLongPreviewLoading(true)
    try {
      setLongPreview(await getBudgetLongPreview(companyId as number))
    } catch (error: any) {
      setLongPreview(null)
      setLongPreviewError(formatText(String(error?.message || error || 'No se pudo generar la validacion del formato largo.')))
    } finally {
      setLongPreviewLoading(false)
    }
  }

  const renderInsightRows = (items: BudgetItemInsight[], kind: 'driver' | 'zero' | 'adjustment') =>
    items.map((item, index) => (
      <tr
        key={`${kind}-${rowKey(item, index)}`}
        className={item.canonicalRowId ? 'row-clickable' : ''}
        role={item.canonicalRowId ? 'button' : undefined}
        tabIndex={item.canonicalRowId ? 0 : -1}
        onClick={item.canonicalRowId ? () => void handleOpenDetail(item) : undefined}
        onMouseEnter={item.canonicalRowId ? () => prefetchDetail(item) : undefined}
        onFocus={item.canonicalRowId ? () => prefetchDetail(item) : undefined}
        onKeyDown={item.canonicalRowId ? (event) => handleDetailKeyDown(event, item) : undefined}
        title={item.canonicalRowId ? 'Abrir detalle mensual' : undefined}
      >
        <td>{item.code || '-'}</td>
        <td>{item.label || '-'}</td>
        <td className="ta-right">{formatMoney(item.annualTotal)}</td>
        <td className="ta-right">{Number(item.shareAbsPct || 0).toFixed(2)}%</td>
        <td className="ta-right">{item.zeroMonths ?? 0}</td>
      </tr>
    ))

  return (
    <div className="budget-tech-page">
      <PageHeader
        title="Analisis tecnico del plan anual"
        subtitle="Lectura tecnica para revisar estructura, concentracion y caja del plan anual."
        actions={
          <div className="budget-tech-top-actions">
            {plan ? <span className="badge">{plan}</span> : null}
            <span
              className={`badge annual-status-pill ${
                months.length
                  ? 'annual-status-pill-ok'
                  : needsValidation || waitingForAnalysis || sourcePresent
                    ? 'annual-status-pill-warn'
                    : ''
              }`}
            >
              {technicalStatusTitle}
            </span>
            <Button size="sm" variant="ghost" onClick={() => navigate('/budget')}>
              Volver al plan anual
            </Button>
          </div>
        }
      />

      {!hasGold ? <Alert tone="warning">Disponible desde Gold.</Alert> : null}
      {!companyId ? <Alert tone="warning">Selecciona una empresa.</Alert> : null}
      {summaryError ? <Alert tone="danger">{formatText(String((summaryError as any)?.message || summaryError))}</Alert> : null}
      {cashflowError ? <Alert tone="warning">{formatText(String((cashflowError as any)?.message || cashflowError))}</Alert> : null}
      {longInsightsError ? <Alert tone="warning">{formatText(String((longInsightsError as any)?.message || longInsightsError))}</Alert> : null}

      <section className="budget-tech-hero card">
        <div className="budget-tech-hero-copy">
          <span className="budget-tech-eyebrow">Plan anual</span>
          <h2>{months.length ? 'Arquitectura anual del ejercicio' : technicalStatusTitle}</h2>
          <p>{technicalStatusDetail}</p>
          <div className="budget-tech-hero-tags">
            {heroSummary.map((item) => (
              <span key={item} className="budget-tech-hero-tag">
                {item}
              </span>
            ))}
          </div>
          <div className="budget-tech-hero-actions">
            <Button size="sm" variant="ghost" loading={longPreviewLoading} disabled={!hasGold || !companyId} onClick={handlePreviewLong}>
              Preview long
            </Button>
            <Button size="sm" variant="ghost" loading={downloadingLongCsv} disabled={!hasGold || !companyId} onClick={handleDownloadLongCsv}>
              CSV largo
            </Button>
            <Button size="sm" variant="secondary" loading={downloadingPdf} disabled={!hasGold || !companyId || !months.length} onClick={handleDownloadPdf}>
              Descargar PDF
            </Button>
          </div>
        </div>

        <div className="budget-tech-hero-side">
          <div className="budget-tech-hero-meta budget-tech-hero-meta-compact">
            <div>
              <span>Fuente</span>
              <strong>{formatText(sourceFilename, 'Sin fichero anual')}</strong>
            </div>
            <div>
              <span>Trazabilidad</span>
              <strong>{traceLabel}</strong>
            </div>
          </div>
          <div className="budget-tech-executive-card budget-tech-executive-card-compact">
            <div className="budget-tech-block-head">
              <span>Lectura ejecutiva</span>
              <h3>Claves del modelo</h3>
            </div>
            <div className="budget-tech-note-list budget-tech-note-list-compact">
              {heroSummary.slice(0, 4).map((item) => (
                <div key={item} className="budget-tech-note-item budget-tech-note-item-compact">
                  <span />
                  <p>{item}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      {showLongPreview ? (
        <Section title="Validacion del formato largo" subtitle="Solo cuando necesites revisar hoja, cabecera y salida larga.">
          {longPreviewError ? <Alert tone="warning">{longPreviewError}</Alert> : null}
          {longPreview ? (
            <div className="budget-tech-inline-panel">
              <div className="budget-tech-inline-grid">
                <div className="budget-tech-inline-card">
                  <div className="budget-tech-card-label">Etiqueta detectada</div>
                  <div className="budget-tech-inline-value">{formatText(longPreview.labelHeader)}</div>
                </div>
                <div className="budget-tech-inline-card">
                  <div className="budget-tech-card-label">Meses</div>
                  <div className="budget-tech-inline-copy">{longPreview.monthKeys.join(', ')}</div>
                </div>
                <div className="budget-tech-inline-card">
                  <div className="budget-tech-card-label">Filas long</div>
                  <div className="budget-tech-inline-value">{longPreview.totalRowsProduced}</div>
                </div>
              </div>
            </div>
          ) : null}
        </Section>
      ) : null}

      {months.length ? (
        <>
          <Section title="1. Resumen ejecutivo" subtitle="Las cifras clave para situar el ejercicio sin ruido.">
            <div className="budget-tech-kpi-strip budget-tech-kpi-strip-primary">
              <div className="budget-tech-kpi-card budget-tech-kpi-card-accent">
                <span>EBITDA</span>
                <strong>{formatMoney(summary?.totalMargin)}</strong>
                <small>Ingresos {formatMoney(summary?.totalIncome)} | OPEX {formatMoney(summary?.totalExpense)}</small>
              </div>
              <div className="budget-tech-kpi-card">
                <span>EBIT</span>
                <strong>{formatMoney(summary?.totalEbit)}</strong>
                <small>Resultado operativo</small>
              </div>
              <div className="budget-tech-kpi-card">
                <span>Neto</span>
                <strong>{formatMoney(summary?.netResult)}</strong>
                <small>Cierre del ejercicio</small>
              </div>
              <div className="budget-tech-kpi-card budget-tech-kpi-card-teal">
                <span>Saldo final</span>
                <strong>{formatMoney(cashflow?.endingBalance)}</strong>
                <small>Caja prevista</small>
              </div>
              <div className="budget-tech-kpi-card">
                <span>Top 3 drivers</span>
                <strong>{driverConcentrationPct.toFixed(2)}%</strong>
                <small>Peso absoluto agregado</small>
              </div>
            </div>

            <div className="budget-tech-context-grid">
              <div className="budget-tech-context-card">
                <span>Mejor / peor margen</span>
                <strong>
                  {formatText(summary?.bestMonth)} / {formatText(summary?.worstMonth)}
                </strong>
                <small>Lectura mensual de margen</small>
              </div>
              <div className="budget-tech-context-card">
                <span>Pico / valle de actividad</span>
                <strong>
                  {formatText(longInsights?.bestMonth)} / {formatText(longInsights?.worstMonth)}
                </strong>
                <small>Segun partidas operativas</small>
              </div>
              <div className="budget-tech-context-card">
                <span>Cash neto ultimo mes</span>
                <strong>{fmtDelta(lastMonth ? cashByKey.get(lastMonth.monthKey)?.net : null)}</strong>
                <small>Saldo final {formatMoney(cashflow?.endingBalance)}</small>
              </div>
              <div className="budget-tech-context-card">
                <span>Delta EBITDA ultimo mes</span>
                <strong>{fmtDelta(marginDelta)}</strong>
                <small>{formatText(lastMonth?.label)}</small>
              </div>
            </div>
          </Section>

          <Section title="2. Series mensuales" subtitle="P&L y tesoreria en la misma lectura tecnica.">
            <div className="budget-tech-chart-grid">
              <div className="budget-tech-chart-card">
                <h3 className="h3-reset">Ingresos / OPEX / EBITDA</h3>
                <div className="budget-tech-card-note">La serie operativa principal del ejercicio.</div>
                <EChart module="budget" valueSuffix=" EUR" height={320} option={incomeExpenseChart as any} />
              </div>
              <div className="budget-tech-chart-card">
                <h3 className="h3-reset">Cash neto / saldo final</h3>
                <div className="budget-tech-card-note">La lectura de caja sin recalculos en el frontend.</div>
                <EChart module="budget" valueSuffix=" EUR" height={320} option={cashChart as any} />
              </div>
            </div>
          </Section>

          <Section title="3. Drivers operativos" subtitle="Concentracion y detalle de las partidas ordinarias elegibles.">
            <div className="budget-tech-kpi-strip budget-tech-kpi-strip-secondary">
              <div className="budget-tech-kpi-card">
                <span>Concentracion top 3</span>
                <strong>{driverConcentrationPct.toFixed(2)}%</strong>
                <small>Peso absoluto agregado</small>
              </div>
              <div className="budget-tech-kpi-card">
                <span>Drivers analizados</span>
                <strong>{longInsights?.itemCount || 0}</strong>
                <small>Partidas elegibles</small>
              </div>
              <div className="budget-tech-kpi-card">
                <span>Total abs. anual</span>
                <strong>{formatMoney(longInsights?.totalAbsAnnual)}</strong>
                <small>Base para concentracion</small>
              </div>
            </div>
            {driverDonutPanels.length ? (
              <div className="budget-tech-panel mt-12">
                {driverExecutiveNarrative ? (
                  <div className="budget-driver-executive-note">
                    <span className="budget-driver-executive-kicker">Lectura ejecutiva</span>
                    <strong>{driverExecutiveNarrative}</strong>
                  </div>
                ) : null}
                <div className="budget-driver-visual">
                  <div className="budget-driver-chart-stack">
                    {driverDonutPanels.map((panel) => (
                      <div key={panel.key} className={`budget-driver-chart-shell budget-driver-chart-shell-${panel.theme}`}>
                        <div className="budget-driver-chart-head">
                          <div className="budget-driver-chart-title">{panel.title}</div>
                          <div className="upload-hint">{panel.subtitle}</div>
                        </div>
                        <EChart
                          module="budget"
                          height={260}
                          option={panel.option as any}
                          activeDataIndex={hoveredDriverSlice[panel.key] ?? null}
                        />
                        <div className="budget-driver-chart-foot upload-hint">
                          {panel.concentrationReading} | Top 3 {panel.concentrationPct.toFixed(2)}%
                        </div>
                      </div>
                    ))}
                  </div>
                  <div className="budget-driver-legend">
                    {driverDonutPanels.map((panel) => (
                      <div key={panel.key} className={`budget-driver-legend-block budget-driver-legend-block-${panel.theme}`}>
                        <div>
                          <div className="budget-driver-legend-kicker">{panel.centerLabel}</div>
                          <div className="budget-driver-legend-title">Top {Math.min(DRIVER_DONUT_LIMIT, panel.slices.filter((slice) => slice.key !== 'otros').length)} + Otros</div>
                          <div className="upload-hint mt-1">Concentracion Top 3: {panel.concentrationPct.toFixed(2)}%</div>
                        </div>
                        <div className="budget-driver-legend-list">
                          {panel.slices.map((slice) => (
                            <div
                              key={slice.key}
                              className={`budget-driver-legend-item ${
                                hoveredDriverSlice[panel.key] === panel.slices.findIndex((candidate) => candidate.key === slice.key)
                                  ? 'is-active'
                                  : ''
                              }`}
                              onMouseEnter={() =>
                                setHoveredDriverSlice((current) => ({
                                  ...current,
                                  [panel.key]: panel.slices.findIndex((candidate) => candidate.key === slice.key)
                                }))
                              }
                              onMouseLeave={() =>
                                setHoveredDriverSlice((current) => ({
                                  ...current,
                                  [panel.key]: null
                                }))
                              }
                            >
                              <span
                                className="budget-driver-legend-dot"
                                style={{ background: slice.color, boxShadow: `0 0 24px ${slice.glow}` }}
                                aria-hidden="true"
                              />
                              <div className="budget-driver-legend-copy">
                                <div className="budget-driver-legend-name">{slice.name}</div>
                                <div className="upload-hint">
                                  {formatMoney(slice.rawValue)} | {slice.pct.toFixed(2)}%
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            ) : null}
            {longInsights?.topDrivers?.length ? (
              <div className="budget-tech-table-card mt-12">
                <div className="budget-tech-table-head">
                  <div>
                    <span>Detalle</span>
                    <h3>Top drivers</h3>
                  </div>
                </div>
                <div className="table-wrap budget-tech-table-wrap">
                  <table className="table budget-tech-table">
                    <thead>
                      <tr>
                        <th>Codigo</th>
                        <th>Partida</th>
                        <th className="ta-right">Total anual</th>
                        <th className="ta-right">Peso abs.</th>
                        <th className="ta-right">Meses a 0</th>
                      </tr>
                    </thead>
                    <tbody>{renderInsightRows(longInsights.topDrivers.slice(0, 10), 'driver')}</tbody>
                  </table>
                </div>
              </div>
            ) : (
              <Alert tone="info">Sin drivers operativos elegibles.</Alert>
            )}
          </Section>

          {longInsights?.accountingAdjustments?.length ? (
            <Section title="4. Ajustes contables" subtitle="Impactan en el P&L, pero quedan fuera de la concentracion operativa.">
              <div className="budget-tech-table-card">
                <div className="budget-tech-table-head">
                  <div>
                    <span>Revision</span>
                    <h3>Ajustes identificados</h3>
                  </div>
                </div>
                <div className="table-wrap budget-tech-table-wrap">
                  <table className="table budget-tech-table">
                    <thead>
                      <tr>
                        <th>Codigo</th>
                        <th>Ajuste</th>
                        <th className="ta-right">Total anual</th>
                        <th>Naturaleza</th>
                        <th className="ta-right">Meses a 0</th>
                      </tr>
                    </thead>
                    <tbody>
                      {longInsights.accountingAdjustments.map((item, index) => (
                        <tr
                          key={`adj-${rowKey(item, index)}`}
                          className={item.canonicalRowId ? 'row-clickable' : ''}
                          role={item.canonicalRowId ? 'button' : undefined}
                          tabIndex={item.canonicalRowId ? 0 : -1}
                          onClick={item.canonicalRowId ? () => void handleOpenDetail(item) : undefined}
                          onKeyDown={item.canonicalRowId ? (event) => handleDetailKeyDown(event, item) : undefined}
                        >
                          <td>{item.code || '-'}</td>
                          <td>{item.label || '-'}</td>
                          <td className="ta-right">{formatMoney(item.annualTotal)}</td>
                          <td>{item.financialNature || item.semanticKind || '-'}</td>
                          <td className="ta-right">{item.zeroMonths ?? 0}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </Section>
          ) : null}

          {longInsights?.zeroHeavyItems?.length ? (
            <Section title="5. Partidas con meses a cero" subtitle="Filas donde el cero puede ser estacionalidad o falta de dato.">
              <div className="budget-tech-table-card">
                <div className="budget-tech-table-head">
                  <div>
                    <span>Revision</span>
                    <h3>Ceros relevantes</h3>
                  </div>
                </div>
                <div className="table-wrap budget-tech-table-wrap">
                  <table className="table budget-tech-table">
                    <thead>
                      <tr>
                        <th>Codigo</th>
                        <th>Partida</th>
                        <th className="ta-right">Total anual</th>
                        <th className="ta-right">Meses a 0</th>
                        <th>Lectura</th>
                      </tr>
                    </thead>
                    <tbody>
                      {longInsights.zeroHeavyItems.slice(0, 12).map((item, index) => (
                        <tr
                          key={`zero-${rowKey(item, index)}`}
                          className={item.canonicalRowId ? 'row-clickable' : ''}
                          role={item.canonicalRowId ? 'button' : undefined}
                          tabIndex={item.canonicalRowId ? 0 : -1}
                          onClick={item.canonicalRowId ? () => void handleOpenDetail(item) : undefined}
                          onKeyDown={item.canonicalRowId ? (event) => handleDetailKeyDown(event, item) : undefined}
                        >
                          <td>{item.code || '-'}</td>
                          <td>{item.label || '-'}</td>
                          <td className="ta-right">{formatMoney(item.annualTotal)}</td>
                          <td className="ta-right">{item.zeroMonths ?? 0}</td>
                          <td>{item.zeroInterpretation || '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </Section>
          ) : null}

          {selectedItem || detailLoading || detailError ? (
            <Section title="6. Detalle mensual" subtitle="Serie y trazabilidad de la partida seleccionada.">
              <div className="budget-tech-panel">
                <div ref={detailSectionRef} className="budget-tech-detail-head">
                  <div className="budget-tech-detail-head-copy">
                    <div className="budget-tech-card-label">Partida activa</div>
                    <div className="budget-tech-detail-title">
                      {selectedItem?.code || detail?.code || '-'} {selectedItem?.label || detail?.label || ''}
                    </div>
                  </div>
                  <div className="budget-tech-detail-actions">
                    <Button size="sm" variant="ghost" onClick={closeDetail}>
                      Cerrar
                    </Button>
                  </div>
                </div>

                {detailError ? <Alert tone="warning">{detailError}</Alert> : null}

                <div className="budget-tech-detail-meta-grid mt-12">
                  <div className="budget-tech-detail-meta-card">
                    <span>Total anual</span>
                    <strong>{detailLoading && !detail ? 'Cargando...' : formatMoney(detail?.computedAnnualTotal ?? selectedItem?.annualTotal ?? null)}</strong>
                    <small>Lectura agregada</small>
                  </div>
                  <div className="budget-tech-detail-meta-card">
                    <span>Filas fuente</span>
                    <strong>{detailLoading && !detail ? '...' : detail?.sourceRowCount ?? 0}</strong>
                    <small>Trazabilidad</small>
                  </div>
                  <div className="budget-tech-detail-meta-card">
                    <span>Naturaleza</span>
                    <strong>{humanizeTechnicalValue(detail?.financialNature || selectedItem?.financialNature || '-')}</strong>
                    <small>Clasificacion financiera</small>
                  </div>
                  <div className="budget-tech-detail-meta-card">
                    <span>Metodo de lectura</span>
                    <strong>{humanizeTechnicalValue(detail?.lookupStrategy || '-')}</strong>
                    <small>Resolucion aplicada</small>
                  </div>
                </div>

                {detail?.months?.length ? (
                  <div className="budget-tech-detail-layout mt-12">
                    <div className="budget-tech-chart-card">
                      <h3 className="h3-reset">Serie mensual</h3>
                      <div className="budget-tech-card-note">Evolucion mensual de la partida seleccionada.</div>
                      <EChart module="budget" loading={detailLoading} valueSuffix=" EUR" height={320} option={detailChart as any} />
                    </div>
                    <div className="budget-tech-table-card">
                      <div className="budget-tech-table-head">
                        <div>
                          <span>Detalle</span>
                          <h3>Importes por mes</h3>
                        </div>
                      </div>
                      <div className="table-wrap budget-tech-table-wrap budget-tech-table-wrap-compact">
                        <table className="table budget-tech-table">
                          <thead>
                            <tr>
                              <th>Mes</th>
                              <th className="ta-right">Importe</th>
                            </tr>
                          </thead>
                          <tbody>
                            {detail.months.map((month) => (
                              <tr key={month.monthKey}>
                                <td>{month.monthLabel}</td>
                                <td className="ta-right">{formatMoney(month.amount)}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                    <div className="budget-tech-detail-trace">
                      Source rows: {detail.sourceRows.join(', ') || '-'}
                      {detail.aggregationPolicy ? ` | Politica ${detail.aggregationPolicy}` : ''}
                    </div>
                  </div>
                ) : detailLoading ? (
                  <div className="budget-tech-detail-loading mt-12">
                    <div className="budget-tech-detail-loading-card" />
                    <div className="budget-tech-detail-loading-card" />
                  </div>
                ) : (
                  <Alert tone="warning">No hay meses disponibles para esta partida.</Alert>
                )}
              </div>
            </Section>
          ) : null}
        </>
      ) : null}

      {!isClient && !months.length && !summaryError && !sourcePresent ? (
        <Alert tone="info">Sube un plan anual valido desde Cargar datos para activar esta pantalla.</Alert>
      ) : null}

      {!isClient && !months.length && !summaryError && needsValidation ? (
        <Section title="Validacion pendiente" subtitle="La base anual esta dentro, pero todavia falta fijar una lectura fiable.">
          <Alert tone="info">El presupuesto ya esta cargado. Revisa hoja y cabecera en Plan anual antes de abrir el analisis tecnico completo.</Alert>
          <div className="budget-tech-context-grid mt-12">
            <div className="budget-tech-context-card">
              <span>Fichero detectado</span>
              <strong>{sourceFilename || 'Sin datos'}</strong>
              <small>Ultima base anual detectada</small>
            </div>
            <div className="budget-tech-context-card">
              <span>Hoja</span>
              <strong>{sourceSheetIndex != null ? `Hoja ${Number(sourceSheetIndex) + 1}` : 'Sin datos'}</strong>
              <small>Intento actual</small>
            </div>
            <div className="budget-tech-context-card">
              <span>Cabecera</span>
              <strong>{sourceHeaderRow != null ? `Fila ${sourceHeaderRow}` : 'Sin datos'}</strong>
              <small>Punto de arranque</small>
            </div>
          </div>
          <div className="row row-wrap gap-8 mt-12">
            <Button size="sm" variant="secondary" onClick={() => navigate('/budget')}>
              Volver al plan anual
            </Button>
            <Button size="sm" variant="ghost" onClick={() => navigate('/imports?mode=universal&flow=budget&focus=sheet')}>
              Elegir otra hoja
            </Button>
            <Button size="sm" variant="ghost" onClick={() => navigate('/imports?mode=universal&flow=budget&focus=header')}>
              Cambiar cabecera
            </Button>
          </div>
        </Section>
      ) : null}

      {!isClient && !months.length && !summaryError && waitingForAnalysis ? (
        <Alert tone="info">La estructura anual ya esta detectada, pero la lectura tecnica todavia se esta completando.</Alert>
      ) : null}
    </div>
  )
}
