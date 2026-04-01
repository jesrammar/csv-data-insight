import EChart from './EChart'

type Kpi = {
  period: string
  inflows: number
  outflows: number
  netFlow: number
  endingBalance: number
}

function fmt(n: number) {
  const abs = Math.abs(n)
  if (abs >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (abs >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

export default function CashFlowBiChart({ kpis }: { kpis: Kpi[] }) {
  const periods = kpis.map((k) => k.period)
  const inflows = kpis.map((k) => Number(k.inflows || 0))
  const outflows = kpis.map((k) => Number(k.outflows || 0))
  const net = kpis.map((k) => Number(k.netFlow || 0))
  const ending = kpis.map((k) => Number(k.endingBalance || 0))

  return (
    <EChart
      style={{ height: 320 }}
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

