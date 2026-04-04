import { useMemo } from 'react'
import EChart from './charts/EChart'
import { formatChartValue } from '../utils/chartFormat'

type Point = { label: string; value: number }

type Props = {
  title: string
  points: Point[]
  variant?: 'line' | 'area' | 'bar'
  valueSuffix?: string
  onPointClick?: (label: string, value: number, index: number) => void
}

export default function KpiChart({ title, points, variant = 'line', valueSuffix = '', onPointClick }: Props) {
  if (!points.length) return <div className="empty">Sin datos para graficar.</div>

  const values = points.map((p) => Number(p.value))
  const min = Math.min(...values)
  const max = Math.max(...values)
  const avg = values.reduce((acc, v) => acc + v, 0) / values.length
  const hasMany = points.length > 14

  const option = useMemo(() => {
    const labels = points.map((p) => p.label)
    const seriesData = points.map((p) => Number(p.value))

    const commonLine = {
      smooth: true,
      symbol: 'circle',
      symbolSize: 7,
      itemStyle: { color: 'rgba(96, 165, 250, 0.95)' },
      lineStyle: { width: 3, color: 'rgba(96, 165, 250, 0.95)' }
    }

    return {
      legend: { show: false },
      grid: { left: 14, right: 14, top: 34, bottom: hasMany ? 46 : 32, containLabel: true },
      xAxis: {
        type: 'category',
        data: labels,
        axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)' } },
        axisLabel: {
          color: 'rgba(226, 232, 240, 0.75)',
          interval: 'auto',
          rotate: hasMany ? 35 : 0
        }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.12)' } },
        axisLabel: { color: 'rgba(226, 232, 240, 0.65)', formatter: (v: any) => formatChartValue(Number(v), valueSuffix) }
      },
      tooltip: {
        trigger: 'axis',
        formatter: (params: any) => {
          const p = Array.isArray(params) ? params[0] : params
          const label = String(p?.axisValue ?? '')
          const val = Number(p?.data)
          return `<div style="font-weight:700;margin-bottom:4px">${label}</div><div>${formatChartValue(val, valueSuffix)}</div>`
        }
      },
      dataZoom: hasMany
        ? [
            { type: 'inside', start: 0, end: 100 },
            {
              type: 'slider',
              height: 18,
              bottom: 6,
              borderColor: 'rgba(148, 163, 184, 0.2)',
              fillerColor: 'rgba(96, 165, 250, 0.15)',
              backgroundColor: 'rgba(148, 163, 184, 0.06)',
              handleStyle: { color: 'rgba(96, 165, 250, 0.6)' },
              textStyle: { color: 'rgba(226, 232, 240, 0.7)' }
            }
          ]
        : undefined,
      series: [
        variant === 'bar'
          ? {
              name: title,
              type: 'bar',
              data: seriesData,
              barMaxWidth: 22,
              itemStyle: { color: 'rgba(20, 184, 166, 0.85)' },
              markLine: {
                symbol: 'none',
                lineStyle: { color: 'rgba(250, 204, 21, 0.5)', type: 'dashed' },
                data: [{ yAxis: avg, name: 'Media' }]
              }
            }
          : {
              name: title,
              type: 'line',
              data: seriesData,
              ...commonLine,
              areaStyle: variant === 'area' ? { color: 'rgba(96, 165, 250, 0.15)' } : undefined,
              markLine: {
                symbol: 'none',
                lineStyle: { color: 'rgba(250, 204, 21, 0.5)', type: 'dashed' },
                data: [{ yAxis: avg, name: 'Media' }]
              }
            }
      ]
    }
  }, [points, variant, valueSuffix, avg, hasMany, title])

  return (
    <div className="chart-wrap">
      <h4 style={{ margin: '0 0 8px' }}>{title}</h4>
      <div className="chart-surface">
        <EChart
          style={{ height: 240 }}
          option={option as any}
          onClick={(params) => {
            if (!onPointClick) return
            const label = String(params?.name ?? params?.axisValue ?? '')
            const raw = params?.value
            const value = Array.isArray(raw) ? Number(raw[1]) : Number(raw)
            const idx = typeof params?.dataIndex === 'number' ? params.dataIndex : -1
            if (!label) return
            if (!Number.isFinite(value)) return
            onPointClick(label, value, idx)
          }}
        />
      </div>
      <div className="mini-row">
        <span>Min: {formatChartValue(min, valueSuffix)}</span>
        <span>Media: {formatChartValue(avg, valueSuffix)}</span>
        <span>Max: {formatChartValue(max, valueSuffix)}</span>
      </div>
    </div>
  )
}
