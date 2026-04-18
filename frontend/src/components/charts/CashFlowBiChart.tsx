import EChart from './EChart'
import { formatChartValue, formatCompactNumber } from '../../utils/chartFormat'
import ChartNarrative from './ChartNarrative'
import { appendCashDrivers, buildCashFlowNarrative } from '../../utils/chartNarrative'
import { useEffect, useMemo, useState } from 'react'

type Kpi = {
  period: string
  inflows: number
  outflows: number
  netFlow: number
  endingBalance: number
}

type Drivers = {
  topCounterparties?: Array<{ counterparty: string; total: number; count: number }>
  categories?: Array<{ category: string; total: number; inflows: number; outflows: number; count: number }>
}

type Props = {
  kpis: Kpi[]
  onSelectPeriod?: (period: string) => void
  drivers?: Drivers | null
  driversPeriod?: string | null
  onFocusPeriodChange?: (period: string | null) => void
}

function fmt(n: number) {
  return formatCompactNumber(n)
}

export default function CashFlowBiChart({ kpis, onSelectPeriod, drivers = null, driversPeriod = null, onFocusPeriodChange }: Props) {
  const periods = kpis.map((k) => k.period)
  const inflows = kpis.map((k) => Number(k.inflows || 0))
  const outflows = kpis.map((k) => Number(k.outflows || 0))
  const net = kpis.map((k) => Number(k.netFlow || 0))
  const ending = kpis.map((k) => Number(k.endingBalance || 0))

  const lastPeriod = periods[periods.length - 1] || null
  const [focusPeriod, setFocusPeriod] = useState<string | null>(lastPeriod)

  useEffect(() => {
    setFocusPeriod(lastPeriod)
    onFocusPeriodChange?.(lastPeriod)
  }, [lastPeriod])

  const narrative = useMemo(() => {
    const base = buildCashFlowNarrative(kpis || [], focusPeriod)
    if (!drivers || !driversPeriod || !focusPeriod) return base
    if (String(driversPeriod) !== String(focusPeriod)) return base
    return appendCashDrivers(base, drivers)
  }, [kpis, focusPeriod, drivers, driversPeriod])

  const setFocus = (p: string | null) => {
    setFocusPeriod(p)
    onFocusPeriodChange?.(p)
  }

  return (
    <div className="chart-wrap">
      <EChart
      height={320}
      onClick={(params) => {
        if (!onSelectPeriod) return
        const p = String(params?.name || '')
        if (p && p.length >= 7) onSelectPeriod(p.slice(0, 7))
      }}
      onAxisHover={(p) => {
        if (!p) return
        setFocus(p.slice(0, 7))
      }}
      onLeave={() => setFocus(lastPeriod)}
      option={{
        legend: {
          top: 0,
          textStyle: { color: '#cbd5e1' },
          selected: { 'Saldo final': false }
        },
        xAxis: {
          type: 'category',
          data: periods,
          axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)' } },
          axisLabel: { color: 'rgba(226, 232, 240, 0.75)' }
        },
        yAxis: {
          type: 'value',
          axisLine: { show: false },
          splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.12)' } },
          axisLabel: { color: 'rgba(226, 232, 240, 0.65)', formatter: (v: any) => fmt(Number(v)) }
        },
        tooltip: {
          trigger: 'axis',
          formatter: (params: any) => {
            const items = Array.isArray(params) ? params : [params]
            const label = String(items[0]?.axisValue ?? '')
            const lines = items
              .map((it: any) => {
                const name = String(it?.seriesName ?? '')
                const val = Array.isArray(it?.data) ? Number(it.data[1]) : Number(it?.data)
                return `${name}: ${formatChartValue(val, '€')}`
              })
              .join('\n')
            return `${label}\n${lines}`
          }
        },
        series: [
          {
            name: 'Entradas',
            type: 'bar',
            data: inflows,
            barMaxWidth: 22,
            itemStyle: { color: 'rgba(20, 184, 166, 0.85)' }
          },
          {
            name: 'Salidas',
            type: 'bar',
            data: outflows.map((v) => -Math.abs(v)),
            barMaxWidth: 22,
            itemStyle: { color: 'rgba(239, 68, 68, 0.8)' }
          },
          {
            name: 'Neto',
            type: 'line',
            data: net,
            smooth: true,
            symbol: 'circle',
            symbolSize: 7,
            itemStyle: { color: 'rgba(96, 165, 250, 0.95)' },
            lineStyle: { width: 3 }
          },
          {
            name: 'Saldo final',
            type: 'line',
            data: ending,
            smooth: true,
            symbol: 'none',
            itemStyle: { color: 'rgba(250, 204, 21, 0.9)' },
            lineStyle: { width: 2, type: 'dashed' }
          }
        ]
      }}
      />
      <ChartNarrative title={`Lectura rápida · ${focusPeriod || '—'}`} see={narrative.see} why={narrative.why} todo={narrative.todo} />
      {onSelectPeriod ? <div className="upload-hint mt-8">Tip: haz click en un periodo para ver el detalle de transacciones.</div> : null}
    </div>
  )
}

