import { useEffect, useMemo, useState } from 'react'
import EChart from './charts/EChart'
import type { ChartModule } from './charts/EChart'
import { formatChartValue } from '../utils/chartFormat'
import ChartNarrative from './charts/ChartNarrative'
import { buildSeriesNarrativeForLabel } from '../utils/chartNarrative'

type Point = { label: string; value: number }

type Marker = {
  label: string
  name: string
  text?: string
  kind?: 'alert' | 'import' | 'warning' | 'info'
}

type Props = {
  title: string
  points: Point[]
  variant?: 'line' | 'area' | 'bar'
  valueSuffix?: string
  module?: ChartModule
  markers?: Marker[]
  showTrend?: boolean
  band?: { low: number; high: number; name?: string } | null
  onPointClick?: (label: string, value: number, index: number) => void
}

function linreg(values: number[]) {
  const n = values.length
  if (n < 2) return { slope: 0, intercept: values[0] ?? 0 }
  let sumX = 0
  let sumY = 0
  let sumXY = 0
  let sumXX = 0
  for (let i = 0; i < n; i++) {
    sumX += i
    sumY += values[i] ?? 0
    sumXY += i * (values[i] ?? 0)
    sumXX += i * i
  }
  const denom = n * sumXX - sumX * sumX
  if (!denom) return { slope: 0, intercept: sumY / n }
  const slope = (n * sumXY - sumX * sumY) / denom
  const intercept = (sumY - slope * sumX) / n
  return { slope, intercept }
}

export default function KpiChart({
  title,
  points,
  variant = 'line',
  valueSuffix = '',
  module = 'default',
  markers = [],
  showTrend = false,
  band = null,
  onPointClick
}: Props) {
  if (!points.length) return <div className="empty">Sin datos para graficar.</div>

  const lastLabel = points[points.length - 1]?.label ? String(points[points.length - 1].label) : ''
  const [focusLabel, setFocusLabel] = useState<string>(lastLabel)

  useEffect(() => {
    setFocusLabel(lastLabel)
  }, [lastLabel])

  const values = points.map((p) => Number(p.value))
  const min = Math.min(...values)
  const max = Math.max(...values)
  const avg = values.reduce((acc, v) => acc + v, 0) / values.length
  const hasMany = points.length > 14
  const focusIdx = points.findIndex((p) => String(p.label) === String(focusLabel))
  const safeFocusIdx = focusIdx >= 0 ? focusIdx : Math.max(0, points.length - 1)
  const vCur = values[safeFocusIdx] ?? 0
  const vPrevFocus = safeFocusIdx >= 1 ? (values[safeFocusIdx - 1] ?? 0) : null
  const dFocus = vPrevFocus == null ? null : vCur - vPrevFocus
  const avgWindow = (window: number) => {
    const end = Math.max(0, Math.min(values.length - 1, safeFocusIdx))
    const start = Math.max(0, end - window + 1)
    const slice = values.slice(start, end + 1).filter((n) => Number.isFinite(n))
    if (!slice.length) return null
    return slice.reduce((acc, v) => acc + v, 0) / slice.length
  }
  const avg3 = avgWindow(3)
  const avg6 = avgWindow(6)
  const narrative = buildSeriesNarrativeForLabel(points, valueSuffix, focusLabel)

  const option = useMemo(() => {
    const labels = points.map((p) => p.label)
    const seriesData = points.map((p) => Number(p.value))
    const trend = showTrend ? linreg(seriesData) : null
    const trendSeries = trend ? seriesData.map((_, i) => trend.slope * i + trend.intercept) : []

    const commonLine = { smooth: true, symbol: 'circle', symbolSize: 7, lineStyle: { width: 3 } }

    const markerData = (markers || [])
      .map((m) => {
        const idx = labels.indexOf(String(m.label))
        if (idx < 0) return null
        const y = seriesData[idx] ?? 0
        const symbol = m.kind === 'alert' ? 'pin' : m.kind === 'warning' ? 'triangle' : 'circle'
        const color =
          m.kind === 'alert'
            ? 'rgba(248,113,113,0.95)'
            : m.kind === 'import'
              ? 'rgba(34,197,94,0.9)'
              : m.kind === 'warning'
                ? 'rgba(250,204,21,0.95)'
                : 'rgba(96,165,250,0.9)'
        return {
          name: m.name,
          value: m.text || m.name,
          coord: [labels[idx], y],
          symbol,
          itemStyle: { color }
        }
      })
      .filter(Boolean)

    return {
      legend: { show: false },
      grid: { left: 14, right: 14, top: 34, bottom: hasMany ? 46 : 32, containLabel: true },
      xAxis: {
        type: 'category',
        data: labels,
        axisLabel: {
          interval: 'auto',
          rotate: hasMany ? 35 : 0
        }
      },
      yAxis: {
        type: 'value',
        axisLabel: { formatter: (v: any) => formatChartValue(Number(v), valueSuffix) }
      },
      tooltip: {
        trigger: 'axis',
        formatter: (params: any) => {
          const p = Array.isArray(params) ? params[0] : params
          const label = String(p?.axisValue ?? '')
          const val = Number(p?.data)
          return `${label}\n${formatChartValue(val, valueSuffix)}`
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
              markLine: {
                symbol: 'none',
                lineStyle: { color: 'rgba(250, 204, 21, 0.5)', type: 'dashed' },
                data: [{ yAxis: avg, name: 'Media' }]
              }
              ,
              markArea: band
                ? {
                    itemStyle: { color: 'rgba(96,165,250,0.08)' },
                    data: [[{ yAxis: band.low, name: band.name || 'Banda' }, { yAxis: band.high }]]
                  }
                : undefined,
              markPoint: markerData.length
                ? { symbolSize: 44, label: { color: '#e2e8f0', fontWeight: 900 }, data: markerData as any }
                : undefined
            }
          : {
              name: title,
              type: 'line',
              data: seriesData,
              ...commonLine,
              areaStyle: variant === 'area' ? { opacity: 0.18 } : undefined,
              markLine: {
                symbol: 'none',
                lineStyle: { color: 'rgba(250, 204, 21, 0.5)', type: 'dashed' },
                data: [{ yAxis: avg, name: 'Media' }]
              }
              ,
              markArea: band
                ? {
                    itemStyle: { color: 'rgba(96,165,250,0.08)' },
                    data: [[{ yAxis: band.low, name: band.name || 'Banda' }, { yAxis: band.high }]]
                  }
                : undefined,
              markPoint: markerData.length
                ? { symbolSize: 44, label: { color: '#e2e8f0', fontWeight: 900 }, data: markerData as any }
                : undefined
            }
        ,
        ...(showTrend
          ? [
              {
                name: 'Tendencia',
                type: 'line',
                data: trendSeries,
                symbol: 'none',
                lineStyle: { type: 'dashed', width: 2, opacity: 0.65 }
              }
            ]
          : [])
      ]
    }
  }, [points, variant, valueSuffix, avg, hasMany, title, markers, showTrend, band])

  return (
    <div className="chart-wrap">
      <h4 className="m-0 mb-8">{title}</h4>
      <div className="chart-surface">
        <EChart
          module={module}
          valueSuffix={valueSuffix}
          height={240}
          option={option as any}
          onAxisHover={(label) => setFocusLabel(String(label || ''))}
          onLeave={() => setFocusLabel(lastLabel)}
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
      <div className="mini-row">
        <span>Dato: {formatChartValue(vCur, valueSuffix)}</span>
        <span>Δ vs anterior: {dFocus == null ? '—' : `${dFocus >= 0 ? '+' : ''}${formatChartValue(dFocus, valueSuffix)}`}</span>
        <span>Media 3p: {avg3 == null ? '—' : formatChartValue(avg3, valueSuffix)}</span>
        <span>Media 6p: {avg6 == null ? '—' : formatChartValue(avg6, valueSuffix)}</span>
      </div>
      <ChartNarrative title={`Lectura del gráfico · ${focusLabel || '—'}`} see={narrative.see} why={narrative.why} todo={narrative.todo} />
    </div>
  )
}
