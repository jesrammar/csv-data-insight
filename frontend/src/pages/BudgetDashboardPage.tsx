import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  downloadBudgetLongCsv,
  downloadBudgetReportPdf,
  getBudgetItemDetail,
  getBudgetLongInsights,
  getBudgetLongPreview,
  getBudgetSummary,
  getCashflowSummary,
  getUserRole,
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
import { formatMoney } from '../utils/format'
import { useToast } from '../components/ui/ToastProvider'

function fmtDelta(value?: number | null) {
  if (value == null || Number.isNaN(Number(value))) return '-'
  const num = Number(value)
  return `${num > 0 ? '+' : ''}${formatMoney(num)}`
}

function rowKey(item: BudgetItemInsight, index: number) {
  return item.canonicalRowId || item.canonicalIdentity || `${item.code || 'row'}-${index}`
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

export default function BudgetDashboardPage() {
  const { id: companyId, plan } = useCompanySelection()
  const navigate = useNavigate()
  const role = getUserRole()
  const isClient = role === 'CLIENTE'
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const toast = useToast()

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

  const { data: summaryData, error: summaryError } = useQuery({
    queryKey: ['budget-summary', companyId],
    queryFn: () => getBudgetSummary(companyId as number),
    enabled: !!companyId && hasGold
  })

  const { data: cashflowData, error: cashflowError } = useQuery({
    queryKey: ['budget-cashflow', companyId],
    queryFn: () => getCashflowSummary(companyId as number),
    enabled: !!companyId && hasGold
  })

  const { data: longInsightsData, error: longInsightsError } = useQuery({
    queryKey: ['budget-long-insights', companyId],
    queryFn: () => getBudgetLongInsights(companyId as number),
    enabled: !!companyId && hasGold
  })

  const summary = summaryData as BudgetSummary | undefined
  const cashflow = cashflowData as CashflowSummary | undefined
  const longInsights = longInsightsData as BudgetLongInsights | undefined

  const months = summary?.months || []
  const cashMonths = cashflow?.months || []
  const cashByKey = new Map(cashMonths.map((month) => [month.monthKey, month]))
  const labels = months.map((month) => month.label)
  const income = months.map((month) => Number(month.income || 0))
  const expense = months.map((month) => Number(month.expense || 0))
  const margin = months.map((month) => Number(month.margin || 0))
  const cashNet = months.map((month) => Number(cashByKey.get(month.monthKey)?.net || 0))
  const cashBalance = months.map((month) => Number(cashByKey.get(month.monthKey)?.endingBalance || 0))

  const lastMonth = months.length ? months[months.length - 1] : null
  const previousMonth = months.length > 1 ? months[months.length - 2] : null
  const marginDelta = lastMonth && previousMonth ? Number(lastMonth.margin || 0) - Number(previousMonth.margin || 0) : null

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

  const handleOpenDetail = async (item: BudgetItemInsight) => {
    setSelectedItem(item)
    setDetail(null)
    if (!companyId || !item.canonicalRowId) {
      setDetailError('Esta fila no trae identidad canonica suficiente para abrir el detalle.')
      return
    }
    setDetailError('')
    setDetailLoading(true)
    try {
      const nextDetail = await getBudgetItemDetail(companyId as number, item.canonicalRowId)
      setDetail(nextDetail)
    } catch (error: any) {
      setDetailError(String(error?.message || error || 'No se pudo abrir el detalle mensual.'))
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
      toast.push({ tone: 'danger', title: 'Error', message: String(error?.message || error || 'No se pudo descargar el PDF.') })
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
      toast.push({ tone: 'danger', title: 'Error', message: String(error?.message || error || 'No se pudo descargar el CSV largo.') })
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
      setLongPreviewError(String(error?.message || error || 'No se pudo generar la validacion del formato largo.'))
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
        onKeyDown={item.canonicalRowId ? (event) => handleDetailKeyDown(event, item) : undefined}
        title={item.canonicalRowId ? 'Abrir detalle mensual' : undefined}
      >
        <td>{item.code || '-'}</td>
        <td>{item.label || '-'}</td>
        <td>{formatMoney(item.annualTotal)}</td>
        <td>{Number(item.shareAbsPct || 0).toFixed(2)}%</td>
        <td>{item.zeroMonths ?? 0}</td>
      </tr>
    ))

  return (
    <div>
      <PageHeader
        title="Analisis tecnico del plan anual"
        subtitle="Una sola lectura oficial para revisar estructura, drivers, ajustes y tesoreria."
        actions={
          <>
            <span className="badge">{plan}</span>
            <Button size="sm" variant="ghost" onClick={() => navigate('/budget')}>
              Volver al plan anual
            </Button>
            <Button size="sm" variant="ghost" loading={longPreviewLoading} disabled={!hasGold || !companyId} onClick={handlePreviewLong}>
              Preview long
            </Button>
            <Button size="sm" variant="ghost" loading={downloadingLongCsv} disabled={!hasGold || !companyId} onClick={handleDownloadLongCsv}>
              CSV largo
            </Button>
            <Button size="sm" variant="secondary" loading={downloadingPdf} disabled={!hasGold || !companyId || !months.length} onClick={handleDownloadPdf}>
              Descargar PDF
            </Button>
          </>
        }
      />

      {!hasGold ? <Alert tone="warning">Disponible desde Gold.</Alert> : null}
      {!companyId ? <Alert tone="warning">Selecciona una empresa.</Alert> : null}
      {summaryError ? <Alert tone="danger">{String((summaryError as any)?.message || summaryError)}</Alert> : null}
      {cashflowError ? <Alert tone="warning">{String((cashflowError as any)?.message || cashflowError)}</Alert> : null}
      {longInsightsError ? <Alert tone="warning">{String((longInsightsError as any)?.message || longInsightsError)}</Alert> : null}

      {showLongPreview ? (
        <Section title="Validacion del formato largo" subtitle="Solo usalo cuando necesites revisar hoja y cabecera.">
          {longPreviewError ? <Alert tone="warning">{longPreviewError}</Alert> : null}
          {longPreview ? (
            <div className="card">
              <div className="grid">
                <div className="card soft">
                  <div className="upload-hint">Etiqueta detectada</div>
                  <div className="fw-800 mt-1">{longPreview.labelHeader}</div>
                </div>
                <div className="card soft">
                  <div className="upload-hint">Meses</div>
                  <div className="fw-800 mt-1">{longPreview.monthKeys.join(', ')}</div>
                </div>
                <div className="card soft">
                  <div className="upload-hint">Filas long</div>
                  <div className="fw-800 mt-1">{longPreview.totalRowsProduced}</div>
                </div>
              </div>
            </div>
          ) : null}
        </Section>
      ) : null}

      {months.length ? (
        <>
          <Section title="1. Resumen" subtitle="Totales canonicos y lectura rapida del ejercicio.">
            <div className="grid">
              <div className="card soft">
                <div className="upload-hint">Fuente</div>
                <div className="fw-800 mt-1">{summary?.sourceFilename || '-'}</div>
              </div>
              <div className="card soft">
                <div className="upload-hint">EBITDA</div>
                <div className="fw-900 mt-1">{formatMoney(summary?.totalMargin)}</div>
                <div className="upload-hint mt-1">Ingresos {formatMoney(summary?.totalIncome)} · OPEX {formatMoney(summary?.totalExpense)}</div>
              </div>
              <div className="card soft">
                <div className="upload-hint">EBIT / Neto</div>
                <div className="fw-900 mt-1">{formatMoney(summary?.totalEbit)}</div>
                <div className="upload-hint mt-1">Neto {formatMoney(summary?.netResult)}</div>
              </div>
            </div>
            <div className="grid mt-12">
              <div className="card soft">
                <div className="upload-hint">Mejor / peor margen</div>
                <div className="fw-800 mt-1">
                  {summary?.bestMonth || '-'} / {summary?.worstMonth || '-'}
                </div>
              </div>
              <div className="card soft">
                <div className="upload-hint">Pico / valle de actividad</div>
                <div className="fw-800 mt-1">
                  {longInsights?.bestMonth || '-'} / {longInsights?.worstMonth || '-'}
                </div>
              </div>
              <div className="card soft">
                <div className="upload-hint">Cash neto ultimo mes</div>
                <div className="fw-800 mt-1">{fmtDelta(lastMonth ? cashByKey.get(lastMonth.monthKey)?.net : null)}</div>
                <div className="upload-hint mt-1">Saldo final {formatMoney(cashflow?.endingBalance)}</div>
              </div>
              <div className="card soft">
                <div className="upload-hint">Delta EBITDA ultimo mes</div>
                <div className="fw-800 mt-1">{fmtDelta(marginDelta)}</div>
                <div className="upload-hint mt-1">{lastMonth?.label || '-'}</div>
              </div>
            </div>
          </Section>

          <Section title="2. Graficos" subtitle="Cuenta de explotacion y tesoreria sin recalculos en pantalla.">
            <div className="grid">
              <div className="card">
                <h3 className="h3-reset">Ingresos / OPEX / EBITDA</h3>
                <EChart module="budget" valueSuffix="€" height={320} option={incomeExpenseChart as any} />
              </div>
              <div className="card">
                <h3 className="h3-reset">Cash neto / saldo final</h3>
                <EChart module="budget" valueSuffix="€" height={320} option={cashChart as any} />
              </div>
            </div>
          </Section>

          <Section title="3. Drivers operativos" subtitle="Solo partidas ordinarias elegibles para concentracion y ranking.">
            <div className="grid">
              <div className="card soft">
                <div className="upload-hint">Concentracion Top 3</div>
                <div className="fw-900 mt-1">{Number(longInsights?.concentrationTop3AbsPct || 0).toFixed(2)}%</div>
              </div>
              <div className="card soft">
                <div className="upload-hint">Drivers analizados</div>
                <div className="fw-900 mt-1">{longInsights?.itemCount || 0}</div>
              </div>
              <div className="card soft">
                <div className="upload-hint">Total abs. anual</div>
                <div className="fw-900 mt-1">{formatMoney(longInsights?.totalAbsAnnual)}</div>
              </div>
            </div>
            {longInsights?.topDrivers?.length ? (
              <div className="card mt-12">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Codigo</th>
                      <th>Partida</th>
                      <th>Total anual</th>
                      <th>Peso abs.</th>
                      <th>Meses a 0</th>
                    </tr>
                  </thead>
                  <tbody>{renderInsightRows(longInsights.topDrivers.slice(0, 10), 'driver')}</tbody>
                </table>
              </div>
            ) : (
              <Alert tone="info">Sin drivers operativos elegibles.</Alert>
            )}
          </Section>

          {longInsights?.accountingAdjustments?.length ? (
            <Section title="4. Ajustes contables" subtitle="Afectan al P&L, pero no entran en drivers ordinarios ni en concentracion.">
              <div className="card">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Codigo</th>
                      <th>Ajuste</th>
                      <th>Total anual</th>
                      <th>Naturaleza</th>
                      <th>Meses a 0</th>
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
                        <td>{formatMoney(item.annualTotal)}</td>
                        <td>{item.financialNature || item.semanticKind || '-'}</td>
                        <td>{item.zeroMonths ?? 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Section>
          ) : null}

          {longInsights?.zeroHeavyItems?.length ? (
            <Section title="5. Partidas con meses a cero" subtitle="Solo filas donde los ceros pueden significar estacionalidad o falta de dato.">
              <div className="card">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Codigo</th>
                      <th>Partida</th>
                      <th>Total anual</th>
                      <th>Meses a 0</th>
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
                        <td>{formatMoney(item.annualTotal)}</td>
                        <td>{item.zeroMonths ?? 0}</td>
                        <td>{item.zeroInterpretation || '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Section>
          ) : null}

          {selectedItem || detailLoading || detailError ? (
            <Section title="6. Detalle mensual" subtitle="La identidad canonica del backend gobierna tambien este panel.">
              <div className="card">
                <div className="row row-between row-wrap gap-10">
                  <div>
                    <div className="upload-hint">Partida activa</div>
                    <div className="fw-800 mt-1">
                      {selectedItem?.code || detail?.code || '-'} {selectedItem?.label || detail?.label || ''}
                    </div>
                  </div>
                  <Button size="sm" variant="ghost" onClick={closeDetail}>
                    Cerrar
                  </Button>
                </div>

                {detailError ? <Alert tone="warning">{detailError}</Alert> : null}

                <div className="grid mt-12">
                  <div className="card soft">
                    <div className="upload-hint">Total anual</div>
                    <div className="fw-900 mt-1">{formatMoney(detail?.computedAnnualTotal ?? selectedItem?.annualTotal ?? null)}</div>
                  </div>
                  <div className="card soft">
                    <div className="upload-hint">Filas fuente</div>
                    <div className="fw-900 mt-1">{detail?.sourceRowCount ?? 0}</div>
                  </div>
                  <div className="card soft">
                    <div className="upload-hint">Naturaleza</div>
                    <div className="fw-900 mt-1">{detail?.financialNature || selectedItem?.financialNature || '-'}</div>
                  </div>
                  <div className="card soft">
                    <div className="upload-hint">Lookup</div>
                    <div className="fw-900 mt-1">{detail?.lookupStrategy || '-'}</div>
                  </div>
                </div>

                {detail?.months?.length ? (
                  <div className="mt-12">
                    <EChart module="budget" loading={detailLoading} valueSuffix="€" height={320} option={detailChart as any} />
                    <table className="table mt-12">
                      <thead>
                        <tr>
                          <th>Mes</th>
                          <th>Importe</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.months.map((month) => (
                          <tr key={month.monthKey}>
                            <td>{month.monthLabel}</td>
                            <td>{formatMoney(month.amount)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <div className="upload-hint mt-8">
                      Source rows: {detail.sourceRows.join(', ') || '-'}
                      {detail.aggregationPolicy ? ` · Politica ${detail.aggregationPolicy}` : ''}
                    </div>
                  </div>
                ) : detailLoading ? (
                  <div className="upload-hint mt-12">Cargando detalle mensual...</div>
                ) : (
                  <Alert tone="warning">No hay meses disponibles para esta partida.</Alert>
                )}
              </div>
            </Section>
          ) : null}
        </>
      ) : null}

      {!isClient && !months.length && !summaryError ? (
        <Alert tone="info">Sube un plan anual valido desde Cargar datos {'->'} Universal para activar esta pantalla.</Alert>
      ) : null}
    </div>
  )
}
