import EChart from './EChart'
import { formatChartValue, formatCompactNumber } from '../../utils/chartFormat'

type Kpi = {
  period: string
  inflows: number
  outflows: number
  netFlow: number
  endingBalance: number
}

function fmt(n: number) {
  return formatCompactNumber(n)
}

export default function CashFlowBiChart({ kpis, onSelectPeriod }: { kpis: Kpi[]; onSelectPeriod?: (period: string) => void }) {
  const periods = kpis.map((k) => k.period)
  const inflows = kpis.map((k) => Number(k.inflows || 0))
  const outflows = kpis.map((k) => Number(k.outflows || 0))
  const net = kpis.map((k) => Number(k.netFlow || 0))
  const ending = kpis.map((k) => Number(k.endingBalance || 0))

  return (
    <EChart
      style={{ height: 320 }}
      onClick={(params) => {
        if (!onSelectPeriod) return
        const p = String(params?.name || '')
        if (p && p.length >= 7) onSelectPeriod(p.slice(0, 7))
      }}
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
                return `<div>${name}: <strong>${formatChartValue(val, '€')}</strong></div>`
              })
              .join('')
            return `<div style="font-weight:700;margin-bottom:4px">${label}</div>${lines}`
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
  )
}

