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

function fmtDelta(v?: number | null) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  const n = Number(v)
  const sign = n > 0 ? '+' : ''
  return `${sign}${formatMoney(n)}`
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

  const labels = months.map((m) => m.label)
  const income = months.map((m) => Number(m.income || 0))
  const expense = months.map((m) => Number(m.expense || 0))
  const margin = months.map((m) => Number(m.margin || 0))
  const cashNet = months.map((m) => Number(cashByKey.get(m.monthKey)?.net || 0))
  const cashBal = months.map((m) => Number(cashByKey.get(m.monthKey)?.endingBalance || 0))

  const chartIncomeExpense = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['Ingresos', 'Gastos', 'Margen'] },
    xAxis: { type: 'category', data: labels, axisLabel: { color: 'rgba(230,237,248,0.75)' } },
    yAxis: { type: 'value', axisLabel: { formatter: (v: any) => `${Number(v).toLocaleString()}€` } },
    series: [
      { name: 'Ingresos', type: 'bar', data: income, itemStyle: { color: '#60a5fa' } },
      { name: 'Gastos', type: 'bar', data: expense, itemStyle: { color: '#fb7185' } },
      { name: 'Margen', type: 'line', data: margin, smooth: true, lineStyle: { width: 3, color: '#14b8a6' } }
    ]
  }

  const chartCash = {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: labels, axisLabel: { color: 'rgba(230,237,248,0.75)' } },
    yAxis: { type: 'value', axisLabel: { formatter: (v: any) => `${Number(v).toLocaleString()}€` } },
    series: [
      {
        name: 'Cash neto',
        type: 'line',
        data: cashMonths.length ? cashNet : [],
        smooth: true,
        lineStyle: { width: 3, color: '#14b8a6' }
      },
      {
        name: 'Saldo final',
        type: 'line',
        data: cashMonths.length ? cashBal : [],
        smooth: true,
        lineStyle: { width: 3, color: '#60a5fa' }
      }
    ]
  }

  return (
    <div>
      <PageHeader
        title="Dashboard Presupuesto"
        subtitle="Plantilla mensual (ENERO…DICIEMBRE). 2 gráficos + tabla de variaciones."
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
        <div style={{ marginBottom: 14 }}>
          <Alert tone="warning" title="Plan insuficiente">
            Requiere plan GOLD o superior.
          </Alert>
        </div>
      ) : null}

      {!companyId ? (
        <div style={{ marginBottom: 14 }}>
          <Alert tone="warning">Selecciona una empresa.</Alert>
        </div>
      ) : null}

      {error ? (
        <div style={{ marginBottom: 14 }}>
          <Alert tone="danger" title="No se pudo generar el presupuesto">
            {String((error as any)?.message || error)}
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Sube el XLSX a <strong>Cargar datos</strong> → <strong>Universal</strong> y usa el modo guiado (hoja + fila cabecera).
            </div>
          </Alert>
        </div>
      ) : null}

      {cashError ? (
        <div style={{ marginBottom: 14 }}>
          <Alert tone="warning" title="Cashflow no disponible">
            {String((cashError as any)?.message || cashError)}
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Si tu plantilla tiene la tabla de tesorería en otra zona, asegúrate de elegir la cabecera que incluya ENERO…DICIEMBRE y la sección de Cashflow.
            </div>
          </Alert>
        </div>
      ) : null}

      {hasGold && companyId && !error && !months.length ? (
        <div style={{ marginBottom: 14 }}>
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
              <div className="upload-hint" style={{ marginTop: 8 }}>
                Sube el XLSX en <strong>Cargar datos</strong> → <strong>Universal</strong> y usa el modo guiado (hoja + fila cabecera).
              </div>
            </Alert>
          ) : null}

          {longPreview ? (
            <div className="card">
              <div className="grid">
                <div className="card soft">
                  <div className="upload-hint">Columna etiqueta detectada</div>
                  <div style={{ fontWeight: 900, marginTop: 6 }}>{longPreview.labelHeader}</div>
                  <div className="upload-hint" style={{ marginTop: 6 }}>Meses: {(longPreview.monthKeys || []).join(', ')}</div>
                </div>
                <div className="card soft">
                  <div className="upload-hint">Filas generadas (long)</div>
                  <div style={{ fontWeight: 900, marginTop: 6 }}>{Number(longPreview.totalRowsProduced || 0).toLocaleString()}</div>
                  <div className="upload-hint" style={{ marginTop: 6 }}>
                    Muestra: {Math.min(longPreview.sampleRows?.length || 0, 20)} filas
                  </div>
                </div>
                <div className="card soft">
                  <div className="upload-hint">Fuente</div>
                  <div style={{ fontWeight: 900, marginTop: 6 }}>{longPreview.filename || summary?.sourceFilename || '—'}</div>
                  <div className="upload-hint" style={{ marginTop: 6 }}>
                    {longPreview.createdAt ? `Subido: ${new Date(longPreview.createdAt).toLocaleString()}` : '—'}
                  </div>
                </div>
              </div>

              {longPreview.sampleRows?.length ? (
                <div style={{ marginTop: 12 }}>
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
                  <div className="upload-hint" style={{ marginTop: 10 }}>
                    Si el tipo es mayoritariamente <strong>TEXT</strong> o no sale código, suele ser una cabecera mal detectada.
                  </div>
                </div>
              ) : null}

              <div style={{ marginTop: 12, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
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
                <div style={{ fontWeight: 800, marginTop: 6 }}>{summary?.sourceFilename || '—'}</div>
                <div className="upload-hint" style={{ marginTop: 6 }}>
                  {summary?.sourceCreatedAt ? `Subido: ${new Date(summary.sourceCreatedAt).toLocaleString()}` : '—'}
                </div>
              </div>
              <div className="card soft">
                <div className="upload-hint">Totales</div>
                <div style={{ fontWeight: 800, marginTop: 6 }}>{formatMoney(summary?.totalMargin)}</div>
                <div className="upload-hint" style={{ marginTop: 6 }}>
                  Ingresos: {formatMoney(summary?.totalIncome)} · Gastos: {formatMoney(summary?.totalExpense)}
                </div>
                {cash?.endingBalance != null ? (
                  <div className="upload-hint" style={{ marginTop: 6 }}>
                    Saldo final (cash): {formatMoney(cash.endingBalance)}
                  </div>
                ) : null}
              </div>
              <div className="card soft">
                <div className="upload-hint">Mejor / peor mes</div>
                <div style={{ fontWeight: 800, marginTop: 6 }}>
                  {summary?.bestMonth || '—'} / {summary?.worstMonth || '—'}
                </div>
                <div className="upload-hint" style={{ marginTop: 6 }}>
                  Tip: para comparar vs real necesitarás importar contabilidad/ventas (fase 2).
                </div>
              </div>
            </div>
          </Section>

          <Section title="2) Gráficos" subtitle="Ingresos vs gastos y margen mensual.">
            <div className="grid">
              <div className="card">
                <h3 style={{ marginTop: 0 }}>Ingresos / Gastos / Margen</h3>
                <EChart style={{ height: 320 }} option={chartIncomeExpense as any} />
              </div>
              <div className="card">
                <h3 style={{ marginTop: 0 }}>Tesorería (cashflow)</h3>
                <div className="upload-hint" style={{ marginTop: 6 }}>
                  Neto y saldo final por mes (si la tabla existe en la plantilla).
                </div>
                <EChart style={{ height: 320 }} option={chartCash as any} />
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
                      <td>{m.deltaMarginPct == null ? '—' : `${Number(m.deltaMarginPct).toFixed(2)}%`}</td>
                      <td>{cashByKey.get(m.monthKey) ? formatMoney(cashByKey.get(m.monthKey)!.net) : '—'}</td>
                      <td>{cashByKey.get(m.monthKey) ? formatMoney(cashByKey.get(m.monthKey)!.endingBalance) : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!isClient ? (
                <div className="upload-hint" style={{ marginTop: 12 }}>
                  Recomendación: marca 2–3 meses “anómalos” y coméntalos en el informe al cliente.
                </div>
              ) : null}
            </div>
          </Section>

          <Section title="4) Insights accionables" subtitle="Drivers del presupuesto y líneas a revisar.">
            {longInsightsError ? (
              <Alert tone="warning" title="Insights no disponibles">
                {String((longInsightsError as any)?.message || longInsightsError)}
                <div className="upload-hint" style={{ marginTop: 8 }}>
                  Tip: si la cabecera (ENERO…DICIEMBRE) no está en la fila correcta, sube el XLSX en Universal con modo guiado (hoja + fila cabecera).
                </div>
              </Alert>
            ) : null}

            {longInsights && longInsights.itemCount ? (
              <div className="grid">
                <div className="card soft">
                  <div className="upload-hint">Concentración (Top 3)</div>
                  <div style={{ fontWeight: 900, marginTop: 6 }}>
                    {Number(longInsights.concentrationTop3AbsPct || 0).toFixed(2)}%
                  </div>
                  <div className="upload-hint" style={{ marginTop: 6 }}>
                    Cuanto más alto, más depende el presupuesto de pocas partidas.
                  </div>
                </div>

                <div className="card soft">
                  <div className="upload-hint">Partidas detectadas</div>
                  <div style={{ fontWeight: 900, marginTop: 6 }}>{longInsights.itemCount}</div>
                  <div className="upload-hint" style={{ marginTop: 6 }}>
                    Total absoluto anual: {formatMoney(longInsights.totalAbsAnnual)}
                  </div>
                </div>

                <div className="card soft">
                  <div className="upload-hint">Estacionalidad (total)</div>
                  <div style={{ fontWeight: 900, marginTop: 6 }}>
                    {longInsights.bestMonth || '—'} / {longInsights.worstMonth || '—'}
                  </div>
                  <div className="upload-hint" style={{ marginTop: 6 }}>
                    Mejor/peor mes según total agregado de partidas (no margen).
                  </div>
                </div>
              </div>
            ) : (
              <div className="upload-hint">Sin insights todavía (sube un presupuesto válido en Universal).</div>
            )}

            {longInsights?.topDrivers?.length ? (
              <div className="card" style={{ marginTop: 12 }}>
                <h3 style={{ marginTop: 0 }}>Top drivers (por peso absoluto)</h3>
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
                      <tr key={`${d.code || 'x'}-${idx}`}>
                        <td>{d.code || '—'}</td>
                        <td>{d.label || '—'}</td>
                        <td>{formatMoney(d.annualTotal)}</td>
                        <td>{Number(d.shareAbsPct || 0).toFixed(2)}%</td>
                        <td>{d.zeroMonths ?? 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="upload-hint" style={{ marginTop: 10 }}>
                  Acciones rápidas: revisa supuestos (precio/volumen) de las 3 primeras partidas y valida su distribución mensual.
                </div>
              </div>
            ) : null}

            {longInsights?.zeroHeavyItems?.length ? (
              <div className="card" style={{ marginTop: 12 }}>
                <h3 style={{ marginTop: 0 }}>Partidas con muchos meses a 0</h3>
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
                      <tr key={`${d.code || 'x'}-z-${idx}`}>
                        <td>{d.code || '—'}</td>
                        <td>{d.label || '—'}</td>
                        <td>{formatMoney(d.annualTotal)}</td>
                        <td>{d.zeroMonths ?? 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="upload-hint" style={{ marginTop: 10 }}>
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
