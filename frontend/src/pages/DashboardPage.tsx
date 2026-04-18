import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { downloadPowerBiExportZip, downloadTransactionsCsv, getDashboard, getTransactionAnalytics, getTransactions, getUserRole } from '../api'
import KpiChart from '../components/KpiChart'
import CashFlowBiChart from '../components/charts/CashFlowBiChart'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import Reveal from '../components/ui/Reveal'
import { useCompanySelection } from '../hooks/useCompany'
import { formatMoney } from '../utils/format'

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
  const location = useLocation()
  const navigate = useNavigate()
  const isClient = getUserRole() === 'CLIENTE'
  const monthsCount = isClient ? 6 : localPlan === 'PLATINUM' ? 24 : localPlan === 'GOLD' ? 12 : 6
  const months = lastMonths(monthsCount)
  const from = months[0]
  const to = months[months.length - 1]

  const { data, error, isLoading } = useQuery({
    queryKey: ['dashboard', companyId, from, to],
    queryFn: () => getDashboard(companyId as number, from, to),
    enabled: !!companyId
  })

  const latest = data?.kpis?.[data?.kpis.length - 1]
  const plan = (data?.plan || localPlan || 'BRONZE').toUpperCase()
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const hasPlatinum = plan === 'PLATINUM'
  const metrics = data?.metrics || []
  const insights = data?.insights || []

  const explain = useMemo(() => {
    const kpis = (data?.kpis || []) as any[]
    if (!kpis.length) return null
    const last = kpis[kpis.length - 1]
    const prev = kpis.length >= 2 ? kpis[kpis.length - 2] : null

    const net = Number(last?.netFlow || 0)
    const bal = Number(last?.endingBalance || 0)
    const inflows = Number(last?.inflows || 0)
    const outflows = Number(last?.outflows || 0)

    const prevNet = prev ? Number(prev?.netFlow || 0) : null
    const prevBal = prev ? Number(prev?.endingBalance || 0) : null

    const dNet = prevNet == null ? null : net - prevNet
    const dBal = prevBal == null ? null : bal - prevBal

    const severityRank = (s: string) => {
      const v = String(s || '').toLowerCase()
      if (v === 'critical') return 3
      if (v === 'warning') return 2
      if (v === 'info') return 1
      return 0
    }

    const topInsights = [...(insights as any[])]
      .sort((a, b) => severityRank(String(b?.severity)) - severityRank(String(a?.severity)))
      .slice(0, 2)
    const hasCritical = topInsights.some((i) => String(i?.severity || '').toLowerCase() === 'critical')
    const hasWarning = topInsights.some((i) => String(i?.severity || '').toLowerCase() === 'warning')

    const tone = hasCritical || bal < 0 ? ('danger' as const) : hasWarning || net < 0 ? ('warning' as const) : ('info' as const)

    const what: string[] = []
    what.push(
      `Este mes: entradas ${formatMoney(inflows)}, salidas ${formatMoney(outflows)} y neto ${formatMoney(net)}${
        dNet == null ? '' : ` (${dNet >= 0 ? '+' : ''}${formatMoney(dNet)} vs mes anterior)`
      }.`
    )
    what.push(`Saldo final estimado: ${formatMoney(bal)}${dBal == null ? '' : ` (${dBal >= 0 ? '+' : ''}${formatMoney(dBal)} vs mes anterior)`}.`)

    const why: string[] = []
    for (const i of topInsights) {
      const title = String(i?.title || '').trim()
      const detail = String(i?.detail || '').trim()
      if (title || detail) why.push([title, detail].filter(Boolean).join(': '))
    }
    if (!why.length) {
      why.push('Lectura rápida: si el neto baja 2-3 meses seguidos o el saldo se acerca a 0, prioriza plan de cobros/pagos.')
    }

    const todo: string[] = []
    const runwayMention = topInsights.some((i) => String(i?.title || '').toLowerCase().includes('runway'))
    const trendDown = topInsights.some((i) => String(i?.title || '').toLowerCase().includes('tendencia') && String(i?.detail || '').toLowerCase().includes('baja'))
    if (hasCritical || runwayMention || bal < 0) {
      todo.push('Haz un plan de tesorería (13 semanas): cobros comprometidos, pagos fijos y margen de seguridad.')
      todo.push('Acelera cobros (recordatorios, pronto pago) y renegocia vencimientos de pagos.')
    } else if (net < 0 || (dBal != null && dBal < 0)) {
      todo.push('Revisa cobros pendientes y gasto no crítico antes de ajustar precios/ventas.')
      todo.push('Separa gastos recurrentes vs puntuales para evitar “falsos picos”.')
    } else {
      todo.push('Reserva parte del neto como colchón y fija un mínimo de caja objetivo.')
      todo.push('Busca palancas: subir cobros recurrentes o reducir 1-2 partidas de gasto fijo.')
    }
    if (trendDown && todo.length < 3) todo.push('Investiga qué cambia: caen cobros, suben gastos o cambia estacionalidad.')

    return { tone, what: what.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 3) }
  }, [data?.kpis, insights])

  const cashCoach = useMemo(() => {
    if (!isClient || !data?.kpis?.length) return null
    const kpis = data.kpis as any[]
    const last = kpis[kpis.length - 1]
    const prev = kpis.length >= 2 ? kpis[kpis.length - 2] : null
    const net = Number(last?.netFlow || 0)
    const bal = Number(last?.endingBalance || 0)
    const prevBal = prev ? Number(prev?.endingBalance || 0) : null
    const balDown = prevBal !== null && bal < prevBal

    if (bal < 0) {
      return {
        tone: 'danger' as const,
        title: 'Semáforo de caja: Rojo',
        message: 'Prioriza cobros inmediatos y frena gastos no críticos hasta recuperar saldo positivo.'
      }
    }
    if (net < 0 && balDown) {
      return {
        tone: 'warning' as const,
        title: 'Semáforo de caja: Amarillo',
        message: 'El saldo baja. Revisa cobros pendientes, renegocia pagos y recorta gastos fijos si es posible.'
      }
    }
    if (net < 0) {
      return {
        tone: 'warning' as const,
        title: 'Semáforo de caja: Amarillo',
        message: 'El mes va en negativo. Prioriza cobros y revisa gastos fijos antes de tocar ventas.'
      }
    }
    return {
      tone: 'success' as const,
      title: 'Semáforo de caja: Verde',
      message: 'Vas bien. Reserva parte del neto como colchón y vigila que no suban los gastos fijos.'
    }
  }, [data?.kpis, isClient])

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
  const [pbiExporting, setPbiExporting] = useState(false)
  const [pbiExportError, setPbiExportError] = useState<string | null>(null)
  const txSectionRef = useRef<HTMLDivElement | null>(null)
  const [cashFocusPeriod, setCashFocusPeriod] = useState<string | null>(to)

  const dashboardPeriods = useMemo<string[]>(() => {
    const periods = (data?.kpis || []).map((k: any) => String(k.period)).filter(Boolean) as string[]
    return Array.from(new Set<string>(periods))
  }, [data?.kpis])

  function drillToPeriod(period: string) {
    if (!hasPlatinum || isClient) return
    const p = String(period || '').trim()
    if (p.length < 7) return
    setTxMode('period')
    setTxPeriod(p.slice(0, 7))
    const r = monthRange(p.slice(0, 7))
    setTxFromDate(r.fromDate)
    setTxToDate(r.toDate)
    setTxPage(0)
    setTimeout(() => txSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 0)
  }

  useEffect(() => {
    const p = (location.state as any)?.drillPeriod
    if (!p) return
    drillToPeriod(String(p))
    navigate('.', { replace: true, state: {} })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.key])

  useEffect(() => {
    setTxPeriod(to)
    const r = monthRange(to)
    setTxFromDate(r.fromDate)
    setTxToDate(r.toDate)
    setCashFocusPeriod(to)
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
    enabled: !!companyId && hasPlatinum && !isClient
  })

  const analyticsParams = useMemo(() => {
    const { page: _p, size: _s, ...rest } = txParams as any
    return { ...rest, topN: 10 }
  }, [txParams])

  const { data: txAnalytics, error: txAnalyticsError, isFetching: txAnalyticsLoading } = useQuery({
    queryKey: ['transactions-analytics', companyId, plan, analyticsParams],
    queryFn: () => getTransactionAnalytics(companyId as number, analyticsParams),
    enabled: !!companyId && hasPlatinum && !isClient
  })

  const { data: cashDrivers } = useQuery({
    queryKey: ['cash-drivers', companyId, plan, cashFocusPeriod],
    queryFn: () => getTransactionAnalytics(companyId as number, { period: cashFocusPeriod || undefined, topN: 5 } as any),
    enabled: !!companyId && hasPlatinum && !isClient && !!cashFocusPeriod,
    staleTime: 10 * 60 * 1000,
    retry: 0
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

  async function handleExportPowerBi() {
    if (!companyId) return
    setPbiExporting(true)
    setPbiExportError(null)
    try {
      const blob = await downloadPowerBiExportZip(companyId as number, from, to)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `enterpriseiq-powerbi-${companyId}-${from}-${to}.zip`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (e: any) {
      const msg = String(e?.message || '')
      if (msg.includes('403') || msg.toLowerCase().includes('forbidden') || msg.toLowerCase().includes('plan')) {
        setPbiExportError('Función disponible en PLATINUM.')
      } else {
        setPbiExportError(e?.message || 'No se pudo exportar el ZIP para Power BI.')
      }
    } finally {
      setPbiExporting(false)
    }
  }

  return (
    <div>
      <Reveal>
        <PageHeader
        title={isClient ? 'Caja' : 'Dashboard financiero'}
        subtitle={
          isClient
            ? `Entradas, salidas y saldo de los últimos ${monthsCount} meses (sin tecnicismos).`
            : `KPIs del periodo actual y últimos ${monthsCount} meses.`
        }
        actions={
          <div className="stack gap-10 justify-items-end">
            <div className="card soft card-pad-14 minw-220">
              <div className="upload-hint">Periodo actual</div>
              <div className="fw-800 mt-1">{to}</div>
              <div className="upload-hint mt-1">
                {latest ? `Neto del mes: ${formatMoney(latest.netFlow)}` : 'Sin datos'}
              </div>
            </div>
            <div className="row row-wrap gap-10 row-end">
              {!isClient ? (
                <Link className="badge" to="/imports">
                  Subir datos
                </Link>
              ) : null}
              <Link className="badge" to="/reports">
                Generar informe (PDF)
              </Link>
            </div>
            {!isClient && hasPlatinum ? (
              <div className="stack w-full maxw-260">
                <Button onClick={handleExportPowerBi} disabled={pbiExporting || !companyId}>
                  {pbiExporting ? 'Exportando…' : 'Exportar Power BI (ZIP)'}
                </Button>
                {pbiExportError ? <div className="alert danger">{pbiExportError}</div> : null}
              </div>
            ) : !isClient ? (
              <div className="upload-hint maxw-260 text-right">
                Exportación Power BI disponible en{' '}
                <Link to="/pricing" className="badge">
                  PLATINUM
                </Link>
                .
              </div>
            ) : null}
          </div>
        }
        />
      </Reveal>

      {!companyId ? (
        <div className="empty mb-3">
          Selecciona una empresa arriba para ver caja y KPIs.
        </div>
      ) : (
        <>
          {isLoading && <div className="empty">Cargando dashboard…</div>}
          {error && <p className="error">{String((error as any).message)}</p>}

          <div className="grid section">
            <Reveal delay={1}>
              <div className="card">
              <h3 className="h3-reset">KPIs clave</h3>
              <div className="grid">
                <div className="kpi">
                  <h4>Entradas</h4>
                  <strong>{formatMoney(latest?.inflows)}</strong>
                </div>
                <div className="kpi">
                  <h4>Salidas</h4>
                  <strong>{formatMoney(latest?.outflows)}</strong>
                </div>
                <div className="kpi">
                  <h4>Neto</h4>
                  <strong>{formatMoney(latest?.netFlow)}</strong>
                </div>
                <div className="kpi">
                  <h4>Saldo fin</h4>
                  <strong>{formatMoney(latest?.endingBalance)}</strong>
                </div>
              </div>
              {cashCoach ? (
                <div className="mt-12">
                  <Alert tone={cashCoach.tone} title={cashCoach.title}>
                    {cashCoach.message}
                  </Alert>
                </div>
              ) : null}
              </div>
            </Reveal>
            <Reveal delay={2}>
              <div className="card">
              <h3 className="h3-reset">Evolución mensual</h3>
              {!data?.kpis?.length ? (
                <div>
                  <div className="empty">
                    {isClient ? 'Sin datos todavía. Tu consultora debe importar el CSV/XLSX.' : 'Sin datos todavía. Importa un CSV/XLSX para calcular caja.'}
                  </div>
                  {isClient ? null : (
                    <div className="row row-wrap gap-10 mt-12">
                      <Link className="badge" to="/imports">
                        Cargar datos
                      </Link>
                      <Link className="badge" to="/overview">
                        Ver guía
                      </Link>
                    </div>
                  )}
                </div>
              ) : (
                <>
                  <div className="mb-3">
                    <CashFlowBiChart
                      kpis={(data?.kpis || []).map((k: any) => ({
                        period: String(k.period),
                        inflows: Number(k.inflows || 0),
                        outflows: Number(k.outflows || 0),
                        netFlow: Number(k.netFlow || 0),
                        endingBalance: Number(k.endingBalance || 0)
                      }))}
                      drivers={cashDrivers as any}
                      driversPeriod={cashFocusPeriod}
                      onFocusPeriodChange={setCashFocusPeriod}
                      onSelectPeriod={hasPlatinum && !isClient ? drillToPeriod : undefined}
                    />
                  </div>
                  {explain ? (
                    <div className="mt-12">
                      <Alert tone={explain.tone} title="Resumen automático">
                        <div className="hero-sub">
                          <ul className="list-steps gap-8">
                            {explain.what.map((t) => (
                              <li key={`w-${t}`}>{t}</li>
                            ))}
                            {explain.why.map((t) => (
                              <li key={`y-${t}`}>
                                <strong>Por qué importa:</strong> {t}
                              </li>
                            ))}
                            {explain.todo.map((t) => (
                              <li key={`t-${t}`}>
                                <strong>Qué haría:</strong> {t}
                              </li>
                            ))}
                          </ul>
                        </div>
                      </Alert>
                    </div>
                  ) : null}
                </>
              )}
              {!data?.kpis?.length || isClient ? null : (
                <table className="table">
                  <thead>
                    <tr>
                      <th>Periodo</th>
                      <th>Entradas</th>
                      <th>Salidas</th>
                      <th>Neto</th>
                      <th>Saldo fin</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(data?.kpis || []).map((k: any) => (
                      <tr key={k.period}>
                        <td>{k.period}</td>
                        <td>{formatMoney(k.inflows)}</td>
                        <td>{formatMoney(k.outflows)}</td>
                        <td>{formatMoney(k.netFlow)}</td>
                        <td>{formatMoney(k.endingBalance)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              </div>
            </Reveal>
          </div>
        </>
      )}

      {!companyId ? null : isClient ? (
        <Reveal delay={1}>
          <div className="card section">
          <h3 className="h3-reset">Qué significa esto</h3>
          <div className="hero-sub">
            <ul className="list-steps gap-8">
              <li>
                <strong>Entradas</strong>: cobros (ventas, ingresos).
              </li>
              <li>
                <strong>Salidas</strong>: pagos (gastos, proveedores, nóminas).
              </li>
              <li>
                <strong>Neto</strong>: entradas - salidas. Si baja 2-3 meses seguidos, pide revisión.
              </li>
              <li>
                <strong>Saldo fin</strong>: estimación de caja al cierre del mes.
              </li>
            </ul>
          </div>
          </div>
        </Reveal>
      ) : (
        <div className="grid section">
          <Reveal delay={1}>
            <div className="card">
            <h3 className="h3-reset">Seguimiento y asesoramiento</h3>
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
          </Reveal>
          <Reveal delay={2}>
            <div className="card">
            <h3 className="h3-reset">Insights</h3>
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
          </Reveal>
        </div>
      )}

      {!companyId || isClient ? null : (
        <Reveal delay={3}>
        <div className="grid section">
          <div className="card">
            <h3 className="h3-reset">Detalle de transacciones</h3>
            {!hasPlatinum ? (
              <div className="empty">Disponible en PLATINUM (drill-down, analítica y export).</div>
            ) : (
              <div ref={txSectionRef}>
              <div className="upload-row flush align-end">
                <label className="stack">
                  <span className="upload-hint">Modo</span>
                  <select value={txMode} onChange={(e) => setTxMode(e.target.value as any)}>
                    <option value="period">Por periodo (YYYY-MM)</option>
                    <option value="dates">Por fechas</option>
                  </select>
                </label>

                {txMode === 'period' ? (
                  <label className="stack">
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
                    <label className="stack">
                      <span className="upload-hint">Desde</span>
                      <input type="date" value={txFromDate} onChange={(e) => setTxFromDate(e.target.value)} />
                    </label>
                    <label className="stack">
                      <span className="upload-hint">Hasta</span>
                      <input type="date" value={txToDate} onChange={(e) => setTxToDate(e.target.value)} />
                    </label>
                  </>
                )}

                <label className="stack minw-180">
                  <span className="upload-hint">Buscar</span>
                  <input value={txQ} onChange={(e) => setTxQ(e.target.value)} placeholder="Descripción / contrapartida" />
                </label>

                <label className="stack">
                  <span className="upload-hint">Dirección</span>
                  <select value={txDirection} onChange={(e) => setTxDirection(e.target.value as any)}>
                    <option value="">Todas</option>
                    <option value="in">Entradas</option>
                    <option value="out">Salidas</option>
                  </select>
                </label>

                <label className="stack w-120">
                  <span className="upload-hint">Min €</span>
                  <input value={txMin} onChange={(e) => setTxMin(e.target.value)} placeholder="0" />
                </label>
                <label className="stack w-120">
                  <span className="upload-hint">Max €</span>
                  <input value={txMax} onChange={(e) => setTxMax(e.target.value)} placeholder="1000" />
                </label>

                <label className="stack w-110">
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

              {txExportError && <div className="error mt-2">{txExportError}</div>}
              {txError && <div className="error mt-2">{String((txError as any).message)}</div>}
              {txAnalyticsError && <div className="error mt-2">{String((txAnalyticsError as any).message)}</div>}

              <div className="upload-hint mt-2">
                {txLoading ? 'Cargando…' : null}
                {!!txData && `Resultados: ${txData.totalElements} · Página ${txData.page + 1}/${txData.totalPages || 1}`}
              </div>

              {!!txAnalytics && (
                <Reveal className="mt-12" delay={1}>
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

                  <div className="mt-3">
                    <KpiChart
                      title="Serie diaria (neto)"
                      points={(txAnalytics.daily || []).map((d) => ({ label: d.date, value: Number(d.net) }))}
                      variant="area"
                      module="overview"
                      valueSuffix="€"
                      onPointClick={(label) => {
                        if (!hasPlatinum || isClient) return
                        if (!label || label.length < 10) return
                        setTxMode('dates')
                        setTxFromDate(label)
                        setTxToDate(label)
                        setTxPage(0)
                      }}
                    />
                    <div className="upload-hint mt-1">
                      Nota: salidas se almacenan como importes negativos.
                    </div>
                  </div>

                  <div className="mt-3">
                    <h4 className="m-0 mb-8">Top contrapartes (impacto)</h4>
                    {txAnalyticsLoading ? (
                      <div className="upload-hint">Calculando agregados…</div>
                    ) : !txAnalytics.topCounterparties?.length ? (
                      <div className="empty">Sin contrapartes para estos filtros.</div>
                    ) : (
                      <div className="overflow-auto">
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
                                <td className="fw-700">{Number(cp.total).toFixed(2)}</td>
                                <td>{cp.count}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>

                  <div className="mt-3">
                    <h4 className="m-0 mb-8">Desglose por categoría (reglas)</h4>
                    {!txAnalytics.categories?.length ? (
                      <div className="empty">Sin categorías para estos filtros.</div>
                    ) : (
                      <div className="overflow-auto">
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
                                <td className="fw-700">{Number(c.total).toFixed(2)}</td>
                                <td>{Number(c.inflows).toFixed(2)}</td>
                                <td>{Number(c.outflows).toFixed(2)}</td>
                                <td>{c.count}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                        <div className="upload-hint mt-8">
                          Clasificación aproximada por texto (description + counterparty). En PLATINUM se puede refinar con reglas a medida.
                        </div>
                      </div>
                    )}
                  </div>

                  <div className="mt-3">
                    <h4 className="m-0 mb-8">Anomalías detectadas</h4>
                    {!txAnalytics.anomalies?.length ? (
                      <div className="empty">No se detectan anomalías relevantes en este rango.</div>
                    ) : (
                      <div className="overflow-auto">
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
                                <td className="fw-700">{Number(a.net).toFixed(2)}</td>
                                <td>{Number(a.score).toFixed(2)}</td>
                                <td className="upload-hint">{a.reason}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>
                </Reveal>
              )}

              {!txData?.items?.length ? (
                <div className="empty mt-2">No hay transacciones para estos filtros.</div>
              ) : (
                <Reveal className="mt-2 overflow-auto" delay={2}>
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
                        const amtClass = amt < 0 ? 'text-danger' : 'text-success'
                        return (
                          <tr key={t.id}>
                            <td>{t.txnDate}</td>
                            <td className={`fw-700 ${amtClass}`}>{formatMoney(amt)}</td>
                            <td>{t.description}</td>
                            <td>{t.counterparty || '-'}</td>
                            <td>{t.period}</td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </Reveal>
              )}

              {!!txData && txData.totalPages > 1 && (
                <Reveal className="mini-row mt-2" delay={3}>
                  <Button variant="ghost" size="sm" onClick={() => setTxPage((p) => Math.max(0, p - 1))} disabled={txPage <= 0}>
                    Anterior
                  </Button>
                  <span className="upload-hint">Página {txPage + 1} de {txData.totalPages}</span>
                  <Button variant="ghost" size="sm" onClick={() => setTxPage((p) => p + 1)} disabled={!txData.hasNext}>
                    Siguiente
                  </Button>
                </Reveal>
              )}
              </div>
            )}
          </div>
        </div>
        </Reveal>
      )}
    </div>
  )
}



