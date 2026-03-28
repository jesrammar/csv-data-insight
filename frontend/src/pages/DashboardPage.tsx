import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { downloadTransactionsCsv, getDashboard, getTransactionAnalytics, getTransactions } from '../api'
import KpiChart from '../components/KpiChart'
import PageHeader from '../components/ui/PageHeader'
import Button from '../components/ui/Button'
import { useCompanySelection } from '../hooks/useCompany'

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

export default function DashboardPage() {
  const { id: companyId, plan: localPlan } = useCompanySelection()
  const monthsCount = localPlan === 'PLATINUM' ? 24 : localPlan === 'GOLD' ? 12 : 6
  const months = lastMonths(monthsCount)
  const from = months[0]
  const to = months[months.length - 1]

  const { data, error, isLoading } = useQuery({
    queryKey: ['dashboard', companyId, from, to],
    queryFn: () => getDashboard(companyId as number, from, to),
    enabled: !!companyId
  })

  const latest = data?.kpis?.[data?.kpis.length - 1]
  const chartPoints = (data?.kpis || []).map((k: any) => ({ label: k.period, value: Number(k.netFlow) }))
  const plan = (data?.plan || localPlan || 'BRONZE').toUpperCase()
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const hasPlatinum = plan === 'PLATINUM'
  const metrics = data?.metrics || []
  const insights = data?.insights || []

  function monthRange(period: string) {
    const [yy, mm] = period.split('-').map((n) => Number(n))
    const fromDate = `${period}-01`
    const lastDay = new Date(yy, mm, 0).getDate()
    const toDate = `${period}-${String(lastDay).padStart(2, '0')}`
    return { fromDate, toDate }
  }

  const [txMode, setTxMode] = useState<'period' | 'dates'>('period')
  const [txPeriod, setTxPeriod] = useState<string>(to)
  const [txFromDate, setTxFromDate] = useState<string>(() => monthRange(to).fromDate)
  const [txToDate, setTxToDate] = useState<string>(() => monthRange(to).toDate)
  const [txQ, setTxQ] = useState<string>('')
  const [txDirection, setTxDirection] = useState<'in' | 'out' | ''>('')
  const [txMin, setTxMin] = useState<string>('')
  const [txMax, setTxMax] = useState<string>('')
  const [txPage, setTxPage] = useState<number>(0)
  const [txSize, setTxSize] = useState<number>(50)
  const [txExporting, setTxExporting] = useState(false)
  const [txExportError, setTxExportError] = useState<string | null>(null)

  const dashboardPeriods = useMemo<string[]>(() => {
    const periods = (data?.kpis || []).map((k: any) => String(k.period)).filter(Boolean) as string[]
    return Array.from(new Set<string>(periods))
  }, [data?.kpis])

  useEffect(() => {
    setTxPeriod(to)
    const r = monthRange(to)
    setTxFromDate(r.fromDate)
    setTxToDate(r.toDate)
  }, [to])

  useEffect(() => {
    setTxPage(0)
  }, [txMode, txPeriod, txFromDate, txToDate, txQ, txDirection, txMin, txMax, txSize])

  const txParams = useMemo(() => {
    const base: any = {
      q: txQ.trim() || undefined,
      direction: (txDirection || undefined) as any,
      minAmount: txMin ? Number(txMin) : undefined,
      maxAmount: txMax ? Number(txMax) : undefined,
      page: txPage,
      size: txSize
    }
    if (txMode === 'period') {
      base.period = txPeriod || undefined
    } else {
      base.fromDate = txFromDate || undefined
      base.toDate = txToDate || undefined
    }
    return base
  }, [txMode, txPeriod, txFromDate, txToDate, txQ, txDirection, txMin, txMax, txPage, txSize])

  const { data: txData, error: txError, isFetching: txLoading } = useQuery({
    queryKey: ['transactions', companyId, plan, txParams],
    queryFn: () => getTransactions(companyId as number, txParams),
    enabled: !!companyId && hasGold
  })

  const analyticsParams = useMemo(() => {
    const { page: _p, size: _s, ...rest } = txParams as any
    return { ...rest, topN: 10 }
  }, [txParams])

  const { data: txAnalytics, error: txAnalyticsError, isFetching: txAnalyticsLoading } = useQuery({
    queryKey: ['transactions-analytics', companyId, plan, analyticsParams],
    queryFn: () => getTransactionAnalytics(companyId as number, analyticsParams),
    enabled: !!companyId && hasGold
  })

  async function handleExportTransactions() {
    if (!companyId) return
    setTxExporting(true)
    setTxExportError(null)
    try {
      const { page: _p, size: _s, ...exportParams } = txParams as any
      const csv = await downloadTransactionsCsv(companyId, exportParams)
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      const suffix = txMode === 'period' && txPeriod ? txPeriod : `${txFromDate || 'from'}_${txToDate || 'to'}`
      a.download = `transactions-${suffix}.csv`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (e: any) {
      setTxExportError(e?.message || 'No se pudo exportar el CSV.')
    } finally {
      setTxExporting(false)
    }
  }

  return (
    <div>
      <PageHeader
        title="Dashboard financiero"
        subtitle={`KPIs del periodo actual y últimos ${monthsCount} meses.`}
        actions={
          <div className="card soft" style={{ padding: 14, minWidth: 220 }}>
            <div className="upload-hint">Periodo actual</div>
            <div style={{ fontWeight: 800, marginTop: 6 }}>{to}</div>
            <div className="upload-hint" style={{ marginTop: 6 }}>
              {latest ? `Net Flow: ${latest.netFlow}` : 'Sin datos'}
            </div>
          </div>
        }
      />

      {isLoading && <div className="empty">Cargando dashboard…</div>}
      {error && <p className="error">{String((error as any).message)}</p>}

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>KPIs clave</h3>
          <div className="grid">
            <div className="kpi">
              <h4>Inflows</h4>
              <strong>{latest?.inflows ?? '-'}</strong>
            </div>
            <div className="kpi">
              <h4>Outflows</h4>
              <strong>{latest?.outflows ?? '-'}</strong>
            </div>
            <div className="kpi">
              <h4>Net Flow</h4>
              <strong>{latest?.netFlow ?? '-'}</strong>
            </div>
            <div className="kpi">
              <h4>Ending Balance</h4>
              <strong>{latest?.endingBalance ?? '-'}</strong>
            </div>
          </div>
        </div>
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Evolución mensual</h3>
          {!data?.kpis?.length ? (
            <div className="empty">Sin datos todavía. Importa un CSV.</div>
          ) : (
            <div style={{ marginBottom: 16 }}>
              <KpiChart title={`Net Flow (últimos ${monthsCount} meses)`} points={chartPoints} />
            </div>
          )}
          {!data?.kpis?.length ? null : (
            <table className="table">
              <thead>
                <tr>
                  <th>Periodo</th>
                  <th>Inflows</th>
                  <th>Outflows</th>
                  <th>Net Flow</th>
                  <th>Ending Balance</th>
                </tr>
              </thead>
              <tbody>
                {(data?.kpis || []).map((k: any) => (
                  <tr key={k.period}>
                    <td>{k.period}</td>
                    <td>{k.inflows}</td>
                    <td>{k.outflows}</td>
                    <td>{k.netFlow}</td>
                    <td>{k.endingBalance}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Seguimiento y asesoramiento</h3>
          <p className="hero-sub">Plan actual: {plan}</p>
          {!metrics.length ? (
            <div className="empty">Sin métricas avanzadas para este plan.</div>
          ) : (
            <div className="grid">
              {metrics.map((m: any) => (
                <div className="kpi" key={m.key}>
                  <h4>{m.label}</h4>
                  <strong>{m.value}</strong>
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Insights</h3>
          {!insights.length ? (
            <div className="empty">No hay insights disponibles para este plan.</div>
          ) : (
            <div className="grid">
              {insights.map((i: any, idx: number) => (
                <div className="kpi" key={`${i.title}-${idx}`}>
                  <h4>{i.title}</h4>
                  <strong>{i.detail}</strong>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Detalle de transacciones</h3>
          {!hasGold ? (
            <div className="empty">Disponible en GOLD/PLATINUM (drill-down y export).</div>
          ) : (
            <div>
              <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end' }}>
                <label style={{ display: 'grid', gap: 6 }}>
                  <span className="upload-hint">Modo</span>
                  <select value={txMode} onChange={(e) => setTxMode(e.target.value as any)}>
                    <option value="period">Por periodo (YYYY-MM)</option>
                    <option value="dates">Por fechas</option>
                  </select>
                </label>

                {txMode === 'period' ? (
                  <label style={{ display: 'grid', gap: 6 }}>
                    <span className="upload-hint">Periodo</span>
                    <select value={txPeriod} onChange={(e) => setTxPeriod(e.target.value)}>
                      {(dashboardPeriods.length ? dashboardPeriods : [to]).map((p) => (
                        <option key={p} value={p}>
                          {p}
                        </option>
                      ))}
                    </select>
                  </label>
                ) : (
                  <>
                    <label style={{ display: 'grid', gap: 6 }}>
                      <span className="upload-hint">Desde</span>
                      <input type="date" value={txFromDate} onChange={(e) => setTxFromDate(e.target.value)} />
                    </label>
                    <label style={{ display: 'grid', gap: 6 }}>
                      <span className="upload-hint">Hasta</span>
                      <input type="date" value={txToDate} onChange={(e) => setTxToDate(e.target.value)} />
                    </label>
                  </>
                )}

                <label style={{ display: 'grid', gap: 6, minWidth: 180 }}>
                  <span className="upload-hint">Buscar</span>
                  <input value={txQ} onChange={(e) => setTxQ(e.target.value)} placeholder="Descripción / contrapartida" />
                </label>

                <label style={{ display: 'grid', gap: 6 }}>
                  <span className="upload-hint">Dirección</span>
                  <select value={txDirection} onChange={(e) => setTxDirection(e.target.value as any)}>
                    <option value="">Todas</option>
                    <option value="in">Entradas</option>
                    <option value="out">Salidas</option>
                  </select>
                </label>

                <label style={{ display: 'grid', gap: 6, width: 120 }}>
                  <span className="upload-hint">Min €</span>
                  <input value={txMin} onChange={(e) => setTxMin(e.target.value)} placeholder="0" />
                </label>
                <label style={{ display: 'grid', gap: 6, width: 120 }}>
                  <span className="upload-hint">Max €</span>
                  <input value={txMax} onChange={(e) => setTxMax(e.target.value)} placeholder="1000" />
                </label>

                <label style={{ display: 'grid', gap: 6, width: 110 }}>
                  <span className="upload-hint">Tamaño</span>
                  <select value={txSize} onChange={(e) => setTxSize(Number(e.target.value))}>
                    {[25, 50, 100, 200].map((n) => (
                      <option key={n} value={n}>
                        {n}
                      </option>
                    ))}
                  </select>
                </label>

                {hasPlatinum && (
                  <Button onClick={handleExportTransactions} loading={txExporting}>
                    Exportar CSV
                  </Button>
                )}
              </div>

              {txExportError && <div className="error" style={{ marginTop: 10 }}>{txExportError}</div>}
              {txError && <div className="error" style={{ marginTop: 10 }}>{String((txError as any).message)}</div>}
              {txAnalyticsError && <div className="error" style={{ marginTop: 10 }}>{String((txAnalyticsError as any).message)}</div>}

              <div className="upload-hint" style={{ marginTop: 10 }}>
                {txLoading ? 'Cargando…' : null}
                {!!txData && `Resultados: ${txData.totalElements} · Página ${txData.page + 1}/${txData.totalPages || 1}`}
              </div>

              {!!txAnalytics && (
                <div style={{ marginTop: 12 }}>
                  <div className="grid">
                    <div className="kpi">
                      <h4>Total entradas</h4>
                      <strong>{Number(txAnalytics.totals.inflows).toFixed(2)}</strong>
                    </div>
                    <div className="kpi">
                      <h4>Total salidas</h4>
                      <strong>{Number(txAnalytics.totals.outflows).toFixed(2)}</strong>
                    </div>
                    <div className="kpi">
                      <h4>Neto</h4>
                      <strong>{Number(txAnalytics.totals.net).toFixed(2)}</strong>
                    </div>
                    <div className="kpi">
                      <h4># transacciones</h4>
                      <strong>{txAnalytics.totals.count}</strong>
                    </div>
                  </div>

                  <div style={{ marginTop: 14 }}>
                    <KpiChart
                      title="Serie diaria (neto)"
                      points={(txAnalytics.daily || []).map((d) => ({ label: d.date.slice(5), value: Number(d.net) }))}
                      variant="area"
                    />
                    <div className="upload-hint" style={{ marginTop: 6 }}>
                      Nota: salidas se almacenan como importes negativos.
                    </div>
                  </div>

                  <div style={{ marginTop: 14 }}>
                    <h4 style={{ margin: '0 0 8px' }}>Top contrapartes (impacto)</h4>
                    {txAnalyticsLoading ? (
                      <div className="upload-hint">Calculando agregados…</div>
                    ) : !txAnalytics.topCounterparties?.length ? (
                      <div className="empty">Sin contrapartes para estos filtros.</div>
                    ) : (
                      <div style={{ overflow: 'auto' }}>
                        <table className="table">
                          <thead>
                            <tr>
                              <th>Contrapartida</th>
                              <th>Total</th>
                              <th>#</th>
                            </tr>
                          </thead>
                          <tbody>
                            {txAnalytics.topCounterparties.slice(0, 10).map((cp) => (
                              <tr key={cp.counterparty}>
                                <td>{cp.counterparty}</td>
                                <td style={{ fontWeight: 700 }}>{Number(cp.total).toFixed(2)}</td>
                                <td>{cp.count}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>

                  <div style={{ marginTop: 14 }}>
                    <h4 style={{ margin: '0 0 8px' }}>Desglose por categoría (reglas)</h4>
                    {!txAnalytics.categories?.length ? (
                      <div className="empty">Sin categorías para estos filtros.</div>
                    ) : (
                      <div style={{ overflow: 'auto' }}>
                        <table className="table">
                          <thead>
                            <tr>
                              <th>Categoría</th>
                              <th>Total</th>
                              <th>Entradas</th>
                              <th>Salidas</th>
                              <th>#</th>
                            </tr>
                          </thead>
                          <tbody>
                            {txAnalytics.categories.slice(0, 12).map((c) => (
                              <tr key={c.category}>
                                <td>{c.category}</td>
                                <td style={{ fontWeight: 700 }}>{Number(c.total).toFixed(2)}</td>
                                <td>{Number(c.inflows).toFixed(2)}</td>
                                <td>{Number(c.outflows).toFixed(2)}</td>
                                <td>{c.count}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                        <div className="upload-hint" style={{ marginTop: 8 }}>
                          Clasificación aproximada por texto (description + counterparty). En PLATINUM se puede refinar con reglas a medida.
                        </div>
                      </div>
                    )}
                  </div>

                  <div style={{ marginTop: 14 }}>
                    <h4 style={{ margin: '0 0 8px' }}>Anomalías detectadas</h4>
                    {!txAnalytics.anomalies?.length ? (
                      <div className="empty">No se detectan anomalías relevantes en este rango.</div>
                    ) : (
                      <div style={{ overflow: 'auto' }}>
                        <table className="table">
                          <thead>
                            <tr>
                              <th>Fecha</th>
                              <th>Neto</th>
                              <th>Score</th>
                              <th>Motivo</th>
                            </tr>
                          </thead>
                          <tbody>
                            {txAnalytics.anomalies.slice(0, 10).map((a) => (
                              <tr key={a.date}>
                                <td>{a.date}</td>
                                <td style={{ fontWeight: 700 }}>{Number(a.net).toFixed(2)}</td>
                                <td>{Number(a.score).toFixed(2)}</td>
                                <td className="upload-hint">{a.reason}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>
                </div>
              )}

              {!txData?.items?.length ? (
                <div className="empty" style={{ marginTop: 10 }}>No hay transacciones para estos filtros.</div>
              ) : (
                <div style={{ marginTop: 10, overflow: 'auto' }}>
                  <table className="table">
                    <thead>
                      <tr>
                        <th>Fecha</th>
                        <th>Importe</th>
                        <th>Descripción</th>
                        <th>Contrapartida</th>
                        <th>Periodo</th>
                      </tr>
                    </thead>
                    <tbody>
                      {txData.items.map((t: any) => {
                        const amt = Number(t.amount)
                        const color = amt < 0 ? '#ef4444' : '#22c55e'
                        return (
                          <tr key={t.id}>
                            <td>{t.txnDate}</td>
                            <td style={{ color, fontWeight: 700 }}>{amt.toFixed(2)}</td>
                            <td>{t.description}</td>
                            <td>{t.counterparty || '-'}</td>
                            <td>{t.period}</td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              )}

              {!!txData && txData.totalPages > 1 && (
                <div className="mini-row" style={{ marginTop: 10 }}>
                  <Button variant="ghost" size="sm" onClick={() => setTxPage((p) => Math.max(0, p - 1))} disabled={txPage <= 0}>
                    Anterior
                  </Button>
                  <span className="upload-hint">Página {txPage + 1} de {txData.totalPages}</span>
                  <Button variant="ghost" size="sm" onClick={() => setTxPage((p) => p + 1)} disabled={!txData.hasNext}>
                    Siguiente
                  </Button>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}



