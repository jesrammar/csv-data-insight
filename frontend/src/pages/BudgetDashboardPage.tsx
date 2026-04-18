import { useQuery } from '@tanstack/react-query'
import {
  downloadBudgetLongCsv,
  downloadBudgetReportPdf,
  getBudgetLongInsights,
  getBudgetLongPreview,
  getBudgetSummary,
  getCashflowSummary,
  type BudgetLongInsights,
  type BudgetLongPreview,
  type BudgetSummary,
  type CashflowSummary,
  getUserRole
} from '../api'
import { useState } from 'react'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import Section from '../components/ui/Section'
import EChart from '../components/charts/EChart'
import { formatMoney } from '../utils/format'
import { useToast } from '../components/ui/ToastProvider'
import Papa from 'papaparse'

function fmtDelta(v?: number | null) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  const n = Number(v)
  const sign = n > 0 ? '+' : ''
  return `${sign}${formatMoney(n)}`
}

function fmtPct(v?: number | null, digits: number = 1) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  return `${Number(v).toFixed(digits)}%`
}

function mean(nums: number[]) {
  if (!nums.length) return 0
  return nums.reduce((acc, v) => acc + v, 0) / nums.length
}

function stddev(nums: number[]) {
  if (nums.length < 2) return 0
  const m = mean(nums)
  const v = mean(nums.map((x) => (x - m) ** 2))
  return Math.sqrt(v)
}

function linreg(values: number[]) {
  const n = values.length
  if (n < 2) return { slope: 0, intercept: values[0] ?? 0 }
  let sumX = 0
  let sumY = 0
  let sumXY = 0
  let sumXX = 0
  for (let i = 0; i < n; i++) {
    const x = i
    const y = Number(values[i] ?? 0)
    sumX += x
    sumY += y
    sumXY += x * y
    sumXX += x * x
  }
  const denom = n * sumXX - sumX * sumX
  if (!denom) return { slope: 0, intercept: sumY / n }
  const slope = (n * sumXY - sumX * sumY) / denom
  const intercept = (sumY - slope * sumX) / n
  return { slope, intercept }
}

export default function BudgetDashboardPage() {
  const { id: companyId, plan } = useCompanySelection()
  const role = getUserRole()
  const isClient = role === 'CLIENTE'
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const toast = useToast()
  const [downloading, setDownloading] = useState(false)
  const [downloadingLongCsv, setDownloadingLongCsv] = useState(false)
  const [showLongPreview, setShowLongPreview] = useState(false)
  const [longPreviewLoading, setLongPreviewLoading] = useState(false)
  const [longPreviewError, setLongPreviewError] = useState('')
  const [longPreview, setLongPreview] = useState<BudgetLongPreview | null>(null)
  const [driverDetail, setDriverDetail] = useState<any | null>(null)
  const [driverDetailLoading, setDriverDetailLoading] = useState(false)
  const [driverDetailError, setDriverDetailError] = useState('')

  const { data, error } = useQuery({
    queryKey: ['budget-summary', companyId],
    queryFn: () => getBudgetSummary(companyId as number),
    enabled: !!companyId && hasGold
  })

  const { data: cashflow, error: cashError } = useQuery({
    queryKey: ['budget-cashflow', companyId],
    queryFn: () => getCashflowSummary(companyId as number),
    enabled: !!companyId && hasGold
  })

  const { data: longInsightsData, error: longInsightsError } = useQuery({
    queryKey: ['budget-long-insights', companyId],
    queryFn: () => getBudgetLongInsights(companyId as number),
    enabled: !!companyId && hasGold
  })

  const summary = data as BudgetSummary | undefined
  const months = summary?.months || []
  const cash = cashflow as CashflowSummary | undefined
  const cashMonths = cash?.months || []
  const cashByKey = new Map(cashMonths.map((m) => [m.monthKey, m]))
  const longInsights = longInsightsData as BudgetLongInsights | undefined
  const monthTotalsByLabel = new Map((longInsights?.monthTotals || []).map((m) => [m.monthLabel, Number(m.total || 0)]))

  const labels = months.map((m) => m.label)
  const income = months.map((m) => Number(m.income || 0))
  const expense = months.map((m) => Number(m.expense || 0))
  const margin = months.map((m) => Number(m.margin || 0))
  const cashNet = months.map((m) => Number(cashByKey.get(m.monthKey)?.net || 0))
  const cashBal = months.map((m) => Number(cashByKey.get(m.monthKey)?.endingBalance || 0))

  const lastIdx = Math.max(0, months.length - 1)
  const lastMonth = months[lastIdx]
  const prevMonth = months[lastIdx - 1]
  const marginLast = Number(lastMonth?.margin ?? 0)
  const marginPrev = Number(prevMonth?.margin ?? 0)
  const deltaMoM = lastIdx > 0 ? marginLast - marginPrev : 0
  const deltaMoMPct = lastIdx > 0 && Math.abs(marginPrev) > 1e-9 ? (deltaMoM / Math.abs(marginPrev)) * 100 : null

  const last6Margin = margin.slice(Math.max(0, margin.length - 6))
  const avg6Margin = mean(last6Margin)
  const std6Margin = stddev(last6Margin)
  const bandLow = avg6Margin - std6Margin
  const bandHigh = avg6Margin + std6Margin

  const trend = linreg(margin)
  const marginTrend = margin.map((_, i) => trend.slope * i + trend.intercept)

  const anomalyPoints = months
    .map((m) => ({ label: m.label, pct: Number(m.deltaMarginPct ?? 0) }))
    .filter((p) => Number.isFinite(p.pct) && Math.abs(p.pct) >= 15)
    .slice(0, 6)
    .map((p) => ({ name: 'Δ', xAxis: p.label, value: `Δ ${p.pct.toFixed(0)}%` }))

  if (summary?.bestMonth) anomalyPoints.unshift({ name: '★', xAxis: summary.bestMonth, value: 'Mejor mes' })
  if (summary?.worstMonth) anomalyPoints.unshift({ name: '!', xAxis: summary.worstMonth, value: 'Peor mes' })

  const chartIncomeExpense = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['Ingresos', 'Gastos', 'Margen', 'Tendencia margen'] },
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value', axisLabel: { formatter: (v: any) => formatMoney(v) } },
    series: [
      { name: 'Ingresos', type: 'bar', data: income },
      { name: 'Gastos', type: 'bar', data: expense },
      {
        name: 'Margen',
        type: 'line',
        data: margin,
        smooth: true,
        lineStyle: { width: 3 },
        markLine: {
          symbol: 'none',
          lineStyle: { type: 'dashed', width: 2, opacity: 0.75 },
          label: { color: 'rgba(226,232,240,0.8)', fontWeight: 800 },
          data: avg6Margin ? [{ yAxis: avg6Margin, name: 'Promedio 6m' }] : []
        },
        markArea:
          std6Margin > 0
            ? {
                itemStyle: { color: 'rgba(96,165,250,0.08)' },
                data: [[{ yAxis: bandLow, name: 'Banda objetivo' }, { yAxis: bandHigh }]]
              }
            : undefined,
        markPoint: { symbolSize: 44, label: { color: '#e2e8f0', fontWeight: 900 }, data: anomalyPoints }
      },
      {
        name: 'Tendencia margen',
        type: 'line',
        data: marginTrend,
        symbol: 'none',
        lineStyle: { type: 'dashed', width: 2, opacity: 0.65 }
      }
    ]
  }

  const chartCash = {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value', axisLabel: { formatter: (v: any) => formatMoney(v) } },
    series: [
      {
        name: 'Cash neto',
        type: 'line',
        data: cashMonths.length ? cashNet : [],
        smooth: true,
        lineStyle: { width: 3 }
      },
      {
        name: 'Saldo final',
        type: 'line',
        data: cashMonths.length ? cashBal : [],
        smooth: true,
        lineStyle: { width: 3 }
      }
    ]
  }

  const withinBand = std6Margin > 0 ? marginLast >= bandLow && marginLast <= bandHigh : null
  const deltaVsAvg6 = marginLast - avg6Margin

  const loadDriverDetail = async (driver: any) => {
    if (!companyId) return
    setDriverDetail({ driver })
    setDriverDetailError('')
    setDriverDetailLoading(true)

    try {
      const blob = await downloadBudgetLongCsv(companyId as number)
      const file = new File([blob], 'budget-long.csv', { type: 'text/csv' })

      const byMonth = new Map<string, number>()
      let matchedRows = 0

      await new Promise<void>((resolve, reject) => {
        Papa.parse(file, {
          header: true,
          skipEmptyLines: true,
          worker: true,
          step: (result: any) => {
            const r = result?.data || {}
            const rowType = String(r.row_type ?? r.rowType ?? '').toUpperCase()
            if (rowType !== 'ITEM') return

            const code = String(r.code ?? '').trim()
            const label = String(r.label ?? '').trim()
            const wantCode = String(driver?.code ?? '').trim()
            const wantLabel = String(driver?.label ?? '').trim()

            if (wantCode) {
              if (code !== wantCode) return
            } else if (wantLabel) {
              if (label !== wantLabel) return
            } else {
              return
            }

            const monthLabel = String(r.month_label ?? r.monthLabel ?? '').trim()
            const amount = Number(r.amount ?? 0)
            if (!monthLabel || !Number.isFinite(amount)) return
            matchedRows++
            byMonth.set(monthLabel, (byMonth.get(monthLabel) || 0) + amount)
          },
          complete: () => resolve(),
          error: (err: any) => reject(err)
        })
      })

      const series = labels.map((l) => Number(byMonth.get(l) || 0))
      const totals = labels.map((l) => Number(monthTotalsByLabel.get(l) || 0))
      const shares = totals.map((t, i) => (t > 0 ? (series[i] / t) * 100 : 0))
      const annual = series.reduce((acc, v) => acc + v, 0)

      const last6 = series.slice(Math.max(0, series.length - 6))
      const avg6 = mean(last6)
      const sd6 = stddev(last6)
      const t = linreg(series)
      const trendSeries = series.map((_, i) => t.slope * i + t.intercept)
      const bandA = avg6 - sd6
      const bandB = avg6 + sd6

      const zeroPoints = labels
        .map((l, i) => ({ l, v: series[i] }))
        .filter((p) => p.v === 0)
        .slice(0, 8)
        .map((p) => ({ name: '0', xAxis: p.l, value: 'Mes a 0' }))

      const driverChart = {
        tooltip: { trigger: 'axis' },
        legend: { data: ['Importe', 'Peso %', 'Tendencia'] },
        xAxis: { type: 'category', data: labels },
        yAxis: [
          { type: 'value', axisLabel: { formatter: (v: any) => formatMoney(v) } },
          { type: 'value', axisLabel: { formatter: (v: any) => `${Number(v).toFixed(0)}%` }, max: 100 }
        ],
        series: [
          {
            name: 'Importe',
            type: 'bar',
            data: series,
            barMaxWidth: 26,
            markLine: {
              symbol: 'none',
              lineStyle: { type: 'dashed', width: 2, opacity: 0.75 },
              label: { color: 'rgba(226,232,240,0.8)', fontWeight: 800 },
              data: avg6 ? [{ yAxis: avg6, name: 'Promedio 6m' }] : []
            },
            markArea:
              sd6 > 0
                ? {
                    itemStyle: { color: 'rgba(20,184,166,0.08)' },
                    data: [[{ yAxis: bandA, name: 'Banda objetivo' }, { yAxis: bandB }]]
                  }
                : undefined,
            markPoint: { symbolSize: 44, label: { color: '#e2e8f0', fontWeight: 900 }, data: zeroPoints }
          },
          {
            name: 'Peso %',
            type: 'line',
            yAxisIndex: 1,
            data: shares,
            smooth: true,
            symbolSize: 7,
            lineStyle: { width: 3, opacity: 0.85 }
          },
          { name: 'Tendencia', type: 'line', data: trendSeries, symbol: 'none', lineStyle: { type: 'dashed', width: 2, opacity: 0.6 } }
        ]
      }

      setDriverDetail({
        driver,
        matchedRows,
        annual,
        series,
        shares,
        chart: driverChart
      })
    } catch (err: any) {
      setDriverDetailError(String(err?.message || err || 'No se pudo cargar el detalle del driver.'))
    } finally {
      setDriverDetailLoading(false)
    }
  }

  return (
    <div>
      <PageHeader
        title="Dashboard Presupuesto"
        subtitle="Plantilla mensual (ENERO...DICIEMBRE). Gráficas + variaciones + drivers accionables."
        actions={
          <>
            <span className="badge">{plan}</span>
            <Button
              size="sm"
              variant="ghost"
              loading={longPreviewLoading}
              disabled={!hasGold || !companyId}
              onClick={async () => {
                if (!companyId) return
                setShowLongPreview(true)
                setLongPreviewError('')
                setLongPreviewLoading(true)
                try {
                  const prev = await getBudgetLongPreview(companyId as number)
                  setLongPreview(prev)
                  toast.push({
                    tone: 'success',
                    title: 'Preview',
                    message: `Detectado: ${prev.labelHeader} · filas long: ${prev.totalRowsProduced}`
                  })
                } catch (err: any) {
                  const msg = String(err?.message || err || 'No se pudo generar el preview.')
                  setLongPreview(null)
                  setLongPreviewError(msg)
                  toast.push({ tone: 'danger', title: 'Error', message: 'No se pudo generar el preview.' })
                } finally {
                  setLongPreviewLoading(false)
                }
              }}
            >
              Preview long
            </Button>
            <Button
              size="sm"
              variant="ghost"
              loading={downloadingLongCsv}
              disabled={!hasGold || !companyId}
              onClick={async () => {
                if (!companyId) return
                setDownloadingLongCsv(true)
                try {
                  const blob = await downloadBudgetLongCsv(companyId as number)
                  const url = URL.createObjectURL(blob)
                  const a = document.createElement('a')
                  a.href = url
                  a.download = `budget-long-${companyId}.csv`
                  document.body.appendChild(a)
                  a.click()
                  a.remove()
                  URL.revokeObjectURL(url)
                  toast.push({ tone: 'success', title: 'CSV', message: 'Descarga iniciada.' })
                } catch (err: any) {
                  toast.push({ tone: 'danger', title: 'Error', message: String(err?.message || err || 'No se pudo descargar el CSV.') })
                } finally {
                  setDownloadingLongCsv(false)
                }
              }}
            >
              CSV largo
            </Button>
            <Button
              size="sm"
              variant="secondary"
              loading={downloading}
              disabled={!hasGold || !companyId || !!error || !months.length}
              onClick={async () => {
                if (!companyId) return
                setDownloading(true)
                try {
                  const blob = await downloadBudgetReportPdf(companyId as number)
                  const url = URL.createObjectURL(blob)
                  const a = document.createElement('a')
                  a.href = url
                  a.download = `budget-report-${companyId}.pdf`
                  document.body.appendChild(a)
                  a.click()
                  a.remove()
                  URL.revokeObjectURL(url)
                  toast.push({ tone: 'success', title: 'PDF', message: 'Descarga iniciada.' })
                } catch (err: any) {
                  toast.push({ tone: 'danger', title: 'Error', message: String(err?.message || err || 'No se pudo descargar el PDF.') })
                } finally {
                  setDownloading(false)
                }
              }}
            >
              Descargar PDF
            </Button>
          </>
        }
      />

      {!hasGold ? (
        <div className="mb-3">
          <Alert tone="warning" title="Plan insuficiente">
            Requiere plan GOLD o superior.
          </Alert>
        </div>
      ) : null}

      {!companyId ? (
        <div className="mb-3">
          <Alert tone="warning">Selecciona una empresa.</Alert>
        </div>
      ) : null}

      {error ? (
        <div className="mb-3">
          <Alert tone="danger" title="No se pudo generar el presupuesto">
            {String((error as any)?.message || error)}
            <div className="upload-hint mt-8">
              Sube el XLSX a <strong>Cargar datos</strong> → <strong>Universal</strong> y usa el modo guiado (hoja + fila cabecera).
            </div>
          </Alert>
        </div>
      ) : null}

      {cashError ? (
        <div className="mb-3">
          <Alert tone="warning" title="Cashflow no disponible">
            {String((cashError as any)?.message || cashError)}
            <div className="upload-hint mt-8">
              Si tu plantilla tiene la tabla de tesorería en otra zona, asegúrate de elegir la cabecera que incluya ENERO…DICIEMBRE y la sección de Cashflow.
            </div>
          </Alert>
        </div>
      ) : null}

      {hasGold && companyId && !error && !months.length ? (
        <div className="mb-3">
          <Alert tone="info" title="Sin datos todavía">
            Sube tu plantilla de presupuesto (XLSX) en <strong>Cargar datos</strong> → <strong>Universal</strong>.
          </Alert>
        </div>
      ) : null}

      {showLongPreview ? (
        <Section title="0) Validación (formato largo)" subtitle="Comprueba cabecera/columna etiqueta antes de analizar.">
          {longPreviewError ? (
            <Alert tone="warning" title="Preview no disponible">
              {longPreviewError}
              <div className="upload-hint mt-8">
                Sube el XLSX en <strong>Cargar datos</strong> → <strong>Universal</strong> y usa el modo guiado (hoja + fila cabecera).
              </div>
            </Alert>
          ) : null}

          {longPreview ? (
            <div className="card">
              <div className="grid">
                <div className="card soft">
                  <div className="upload-hint">Columna etiqueta detectada</div>
                  <div className="fw-900 mt-1">{longPreview.labelHeader}</div>
                  <div className="upload-hint mt-1">Meses: {(longPreview.monthKeys || []).join(', ')}</div>
                </div>
                <div className="card soft">
                  <div className="upload-hint">Filas generadas (long)</div>
                  <div className="fw-900 mt-1">{Number(longPreview.totalRowsProduced || 0).toLocaleString()}</div>
                  <div className="upload-hint mt-1">
                    Muestra: {Math.min(longPreview.sampleRows?.length || 0, 20)} filas
                  </div>
                </div>
                <div className="card soft">
                  <div className="upload-hint">Fuente</div>
                  <div className="fw-900 mt-1">{longPreview.filename || summary?.sourceFilename || '—'}</div>
                  <div className="upload-hint mt-1">
                    {longPreview.createdAt ? `Subido: ${new Date(longPreview.createdAt).toLocaleString()}` : '—'}
                  </div>
                </div>
              </div>

              {longPreview.sampleRows?.length ? (
                <div className="mt-12">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>Tipo</th>
                        <th>Código</th>
                        <th>Partida</th>
                        <th>Mes</th>
                        <th>Importe</th>
                      </tr>
                    </thead>
                    <tbody>
                      {longPreview.sampleRows.slice(0, 20).map((r, idx) => (
                        <tr key={`lp-${idx}`}>
                          <td>{r.rowType}</td>
                          <td>{r.code || '—'}</td>
                          <td>{r.label || '—'}</td>
                          <td>{r.monthLabel || r.monthKey}</td>
                          <td>{formatMoney(r.amount)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <div className="upload-hint mt-2">
                    Si el tipo es mayoritariamente <strong>TEXT</strong> o no sale código, suele ser una cabecera mal detectada.
                  </div>
                </div>
              ) : null}

              <div className="row row-wrap gap-10 mt-12">
                <Button size="sm" variant="ghost" onClick={() => setShowLongPreview(false)}>
                  Ocultar preview
                </Button>
              </div>
            </div>
          ) : null}
        </Section>
      ) : null}

      {months.length ? (
        <>
          <Section title="1) Resumen" subtitle="Totales y contexto del fichero.">
            <div className="grid">
              <div className="card soft">
                <div className="upload-hint">Fuente</div>
                <div className="fw-800 mt-1">{summary?.sourceFilename || '—'}</div>
                <div className="upload-hint mt-1">
                  {summary?.sourceCreatedAt ? `Subido: ${new Date(summary.sourceCreatedAt).toLocaleString()}` : '—'}
                </div>
              </div>
              <div className="card soft">
                <div className="upload-hint">Totales</div>
                <div className="fw-800 mt-1">{formatMoney(summary?.totalMargin)}</div>
                <div className="upload-hint mt-1">
                  Ingresos: {formatMoney(summary?.totalIncome)} · Gastos: {formatMoney(summary?.totalExpense)}
                </div>
                {cash?.endingBalance != null ? (
                  <div className="upload-hint mt-1">
                    Saldo final (cash): {formatMoney(cash.endingBalance)}
                  </div>
                ) : null}
              </div>
              <div className="card soft">
                <div className="upload-hint">Mejor / peor mes</div>
                <div className="fw-800 mt-1">
                  {summary?.bestMonth || '—'} / {summary?.worstMonth || '—'}
                </div>
                <div className="upload-hint mt-1">
                  Tip: para comparar vs real, importa contabilidad/ventas (módulo Universal).
                </div>
              </div>
            </div>

            <div className="grid mt-12">
              <div className="card soft">
                <div className="upload-hint">Margen último mes</div>
                <div className="fw-900 mt-1">{formatMoney(marginLast)}</div>
                <div className="upload-hint mt-1">{lastMonth?.label ? `Mes: ${lastMonth.label}` : '—'}</div>
              </div>
              <div className="card soft">
                <div className="upload-hint">Vs mes anterior</div>
                <div className="fw-900 mt-1">{fmtDelta(deltaMoM)}</div>
                <div className="upload-hint mt-1">{deltaMoMPct == null ? '—' : `(${fmtPct(deltaMoMPct, 1)})`}</div>
              </div>
              <div className="card soft">
                <div className="upload-hint">Vs promedio 6m</div>
                <div className="fw-900 mt-1">{fmtDelta(deltaVsAvg6)}</div>
                <div className="upload-hint mt-1">{avg6Margin ? `Promedio 6m: ${formatMoney(avg6Margin)}` : '—'}</div>
              </div>
              <div className="card soft">
                <div className="upload-hint">Banda objetivo (±1σ)</div>
                <div className="fw-900 mt-1">
                  {withinBand == null ? '—' : withinBand ? 'Dentro' : 'Fuera'}
                </div>
                <div className="upload-hint mt-1">
                  {std6Margin > 0 ? `${formatMoney(bandLow)} → ${formatMoney(bandHigh)}` : 'Sin histórico suficiente'}
                </div>
              </div>
            </div>
          </Section>

          <Section title="2) Gráficos" subtitle="Ingresos vs gastos, margen y narrativa (tendencia + banda objetivo + alertas).">
            <div className="grid">
              <div className="card">
                <h3 className="h3-reset">Ingresos / Gastos / Margen</h3>
                <EChart module="budget" valueSuffix="€" height={320} option={chartIncomeExpense as any} />
                <div className="upload-hint mt-2">
                  Lectura rápida: busca meses con <strong>Δ ≥ 15%</strong>, y revisa si el margen cae fuera de la banda objetivo.
                </div>
              </div>
              <div className="card">
                <h3 className="h3-reset">Tesorería (cashflow)</h3>
                <div className="upload-hint mt-1">
                  Neto y saldo final por mes (si la tabla existe en la plantilla).
                </div>
                <EChart module="budget" valueSuffix="€" height={320} option={chartCash as any} />
              </div>
            </div>
          </Section>

          <Section title="3) Variaciones" subtitle="Mes a mes (margen + cash neto).">
            <div className="card">
              <table className="table">
                <thead>
                  <tr>
                    <th>Mes</th>
                    <th>Ingresos</th>
                    <th>Gastos</th>
                    <th>Margen</th>
                    <th>Δ margen</th>
                    <th>Δ %</th>
                    <th>Cash neto</th>
                    <th>Saldo final</th>
                  </tr>
                </thead>
                <tbody>
                  {months.map((m) => (
                    <tr key={m.monthKey}>
                      <td>{m.label}</td>
                      <td>{formatMoney(m.income)}</td>
                      <td>{formatMoney(m.expense)}</td>
                      <td>{formatMoney(m.margin)}</td>
                      <td>{fmtDelta(m.deltaMargin)}</td>
                      <td>{fmtPct(m.deltaMarginPct, 2)}</td>
                      <td>{cashByKey.get(m.monthKey) ? formatMoney(cashByKey.get(m.monthKey)!.net) : '—'}</td>
                      <td>{cashByKey.get(m.monthKey) ? formatMoney(cashByKey.get(m.monthKey)!.endingBalance) : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!isClient ? (
                <div className="upload-hint mt-12">
                  Recomendación: elige 2–3 meses “anómalos” (Δ alto) y añade causa + acción en el informe.
                </div>
              ) : null}
            </div>
          </Section>

          <Section title="4) Insights accionables" subtitle="Drivers del presupuesto (click para ver detalle mensual).">
            {longInsightsError ? (
              <Alert tone="warning" title="Insights no disponibles">
                {String((longInsightsError as any)?.message || longInsightsError)}
                <div className="upload-hint mt-8">
                  Tip: si la cabecera (ENERO...DICIEMBRE) no está en la fila correcta, sube el XLSX en Universal con modo guiado (hoja + fila cabecera).
                </div>
              </Alert>
            ) : null}

            {longInsights && longInsights.itemCount ? (
              <div className="grid">
                <div className="card soft">
                  <div className="upload-hint">Concentración (Top 3)</div>
                  <div className="fw-900 mt-1">
                    {Number(longInsights.concentrationTop3AbsPct || 0).toFixed(2)}%
                  </div>
                  <div className="upload-hint mt-1">
                    Cuanto más alto, más depende el presupuesto de pocas partidas.
                  </div>
                </div>

                <div className="card soft">
                  <div className="upload-hint">Partidas detectadas</div>
                  <div className="fw-900 mt-1">{longInsights.itemCount}</div>
                  <div className="upload-hint mt-1">
                    Total absoluto anual: {formatMoney(longInsights.totalAbsAnnual)}
                  </div>
                </div>

                <div className="card soft">
                  <div className="upload-hint">Estacionalidad (total)</div>
                  <div className="fw-900 mt-1">
                    {longInsights.bestMonth || '—'} / {longInsights.worstMonth || '—'}
                  </div>
                  <div className="upload-hint mt-1">
                    Mejor/peor mes según total agregado de partidas (no margen).
                  </div>
                </div>
              </div>
            ) : (
              <div className="upload-hint">Sin insights todavía (sube un presupuesto válido en Universal).</div>
            )}

            {longInsights?.topDrivers?.length ? (
              <div className="card mt-12">
                <h3 className="h3-reset">Top drivers (por peso absoluto)</h3>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Código</th>
                      <th>Partida</th>
                      <th>Total anual</th>
                      <th>Peso abs.</th>
                      <th>Meses a 0</th>
                    </tr>
                  </thead>
                  <tbody>
                    {longInsights.topDrivers.slice(0, 10).map((d, idx) => (
                      <tr
                        key={`${d.code || 'x'}-${idx}`}
                        className="row-clickable"
                        onClick={() => loadDriverDetail(d)}
                        title="Ver detalle mensual"
                      >
                        <td>{d.code || '—'}</td>
                        <td>{d.label || '—'}</td>
                        <td>{formatMoney(d.annualTotal)}</td>
                        <td>{Number(d.shareAbsPct || 0).toFixed(2)}%</td>
                        <td>{d.zeroMonths ?? 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="upload-hint mt-2">
                  Acciones rápidas: revisa supuestos (precio/volumen) de las 3 primeras partidas y valida su distribución mensual.
                </div>
              </div>
            ) : null}

            {driverDetail ? (
              <div className="card mt-12">
                <div className="row row-between row-baseline row-wrap gap-12">
                  <div>
                    <h3 className="h3-reset mb-1">Detalle driver</h3>
                    <div className="upload-hint">
                      {driverDetail?.driver?.code ? <span className="code-inline">{driverDetail.driver.code}</span> : null}{' '}
                      {driverDetail?.driver?.label || '—'}
                    </div>
                  </div>
                  <div className="row row-wrap gap-10">
                    <Button size="sm" variant="ghost" onClick={() => setDriverDetail(null)}>
                      Cerrar
                    </Button>
                  </div>
                </div>

                {driverDetailError ? (
                  <Alert tone="warning" title="No se pudo cargar el detalle">
                    {driverDetailError}
                  </Alert>
                ) : null}

                <div className="mt-12">
                  <EChart
                    module="budget"
                    loading={driverDetailLoading}
                    error={driverDetailError || null}
                    valueSuffix="€"
                    height={340}
                    option={(driverDetail.chart || {}) as any}
                  />
                </div>

                {!driverDetailLoading && !driverDetailError && driverDetail.matchedRows === 0 ? (
                  <Alert tone="warning" title="Sin detalle para esta partida">
                    No se encontraron filas en el formato largo para este driver. Revisa si la columna etiqueta/código se detectó bien (usa “Preview long”).
                  </Alert>
                ) : null}

                <div className="upload-hint mt-2">
                  Total anual (suma meses): <strong>{formatMoney(driverDetail.annual)}</strong> · Filas usadas: {driverDetail.matchedRows}
                </div>
              </div>
            ) : null}

            {longInsights?.zeroHeavyItems?.length ? (
              <div className="card mt-12">
                <h3 className="h3-reset">Partidas con muchos meses a 0</h3>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Código</th>
                      <th>Partida</th>
                      <th>Total anual</th>
                      <th>Meses a 0</th>
                    </tr>
                  </thead>
                  <tbody>
                    {longInsights.zeroHeavyItems.slice(0, 12).map((d, idx) => (
                      <tr
                        key={`${d.code || 'x'}-z-${idx}`}
                        className="row-clickable"
                        onClick={() => loadDriverDetail(d)}
                        title="Ver detalle mensual"
                      >
                        <td>{d.code || '—'}</td>
                        <td>{d.label || '—'}</td>
                        <td>{formatMoney(d.annualTotal)}</td>
                        <td>{d.zeroMonths ?? 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="upload-hint mt-2">
                  Esto suele ser estacionalidad fuerte o celdas sin rellenar. Confirma con el cliente si es “normal”.
                </div>
              </div>
            ) : null}
          </Section>
        </>
      ) : null}
    </div>
  )
}
