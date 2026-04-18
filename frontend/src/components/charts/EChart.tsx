import { useEffect, useMemo, useRef, useState } from 'react'
import type { EChartsCoreOption, EChartsType } from './echarts'
import Button from '../ui/Button'
import Skeleton from '../ui/Skeleton'

export type ChartModule = 'default' | 'budget' | 'universal' | 'tribunal' | 'overview'

type Props = {
  option: EChartsCoreOption
  module?: ChartModule
  loading?: boolean
  error?: string | null
  emptyTitle?: string
  emptyHint?: string
  errorTitle?: string
  errorHint?: string
  actions?: boolean
  valueSuffix?: string
  className?: string
  height?: number
  onClick?: (params: any) => void
  onAxisHover?: (axisValue: string) => void
  onLeave?: () => void
}

function linearGradient(top: string, bottom: string) {
  return {
    type: 'linear',
    x: 0,
    y: 0,
    x2: 0,
    y2: 1,
    colorStops: [
      { offset: 0, color: top },
      { offset: 1, color: bottom }
    ]
  }
}

function mergeOptions(base: EChartsCoreOption, extra: EChartsCoreOption): EChartsCoreOption {
  const merged: any = { ...base, ...extra }

  const isPlainObject = (value: unknown): value is Record<string, any> => {
    return !!value && typeof value === 'object' && !Array.isArray(value)
  }

  for (const key of ['grid', 'tooltip', 'textStyle', 'legend', 'xAxis', 'yAxis'] as const) {
    const b = (base as any)[key]
    const e = (extra as any)[key]
    if (isPlainObject(b) && isPlainObject(e)) {
      merged[key] = { ...b, ...e }
    }
  }

  return merged as EChartsCoreOption
}

const MODULE_PALETTES: Record<ChartModule, any[]> = {
  default: [
    linearGradient('rgba(96,165,250,0.95)', 'rgba(96,165,250,0.25)'),
    linearGradient('rgba(20,184,166,0.9)', 'rgba(20,184,166,0.22)'),
    linearGradient('rgba(168,85,247,0.86)', 'rgba(168,85,247,0.22)'),
    linearGradient('rgba(236,72,153,0.82)', 'rgba(236,72,153,0.2)'),
    linearGradient('rgba(250,204,21,0.8)', 'rgba(250,204,21,0.18)')
  ],
  budget: [
    linearGradient('rgba(96,165,250,0.95)', 'rgba(96,165,250,0.22)'),
    linearGradient('rgba(20,184,166,0.9)', 'rgba(20,184,166,0.2)'),
    linearGradient('rgba(248,113,113,0.88)', 'rgba(248,113,113,0.18)'),
    linearGradient('rgba(168,85,247,0.86)', 'rgba(168,85,247,0.18)')
  ],
  universal: [
    linearGradient('rgba(20,184,166,0.92)', 'rgba(20,184,166,0.22)'),
    linearGradient('rgba(96,165,250,0.92)', 'rgba(96,165,250,0.22)'),
    linearGradient('rgba(250,204,21,0.84)', 'rgba(250,204,21,0.18)'),
    linearGradient('rgba(236,72,153,0.82)', 'rgba(236,72,153,0.18)')
  ],
  tribunal: [
    linearGradient('rgba(168,85,247,0.88)', 'rgba(168,85,247,0.2)'),
    linearGradient('rgba(236,72,153,0.82)', 'rgba(236,72,153,0.18)'),
    linearGradient('rgba(96,165,250,0.9)', 'rgba(96,165,250,0.2)'),
    linearGradient('rgba(34,197,94,0.82)', 'rgba(34,197,94,0.16)')
  ],
  overview: [
    linearGradient('rgba(96,165,250,0.95)', 'rgba(96,165,250,0.22)'),
    linearGradient('rgba(168,85,247,0.86)', 'rgba(168,85,247,0.18)'),
    linearGradient('rgba(20,184,166,0.88)', 'rgba(20,184,166,0.18)'),
    linearGradient('rgba(250,204,21,0.82)', 'rgba(250,204,21,0.18)')
  ]
}

function baseOption(module: ChartModule): EChartsCoreOption {
  return {
    backgroundColor: 'transparent',
    color: MODULE_PALETTES[module] || MODULE_PALETTES.default,
    animation: true,
    animationDuration: 650,
    animationEasing: 'cubicOut',
    animationDurationUpdate: 260,
    animationEasingUpdate: 'cubicOut',
    textStyle: { color: '#e2e8f0', fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, sans-serif', fontSize: 12 },
    grid: { left: 14, right: 14, top: 30, bottom: 26, containLabel: true },
    legend: {
      top: 0,
      left: 0,
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 12,
      icon: 'roundRect',
      textStyle: { color: 'rgba(226, 232, 240, 0.78)', fontSize: 12 }
    },
    xAxis: {
      axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', width: 1 } },
      axisTick: { show: false },
      axisLabel: { color: 'rgba(226, 232, 240, 0.72)', fontSize: 11, margin: 12 },
      splitLine: { show: false }
    },
    yAxis: {
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: 'rgba(226, 232, 240, 0.66)', fontSize: 11, margin: 12 },
      splitLine: { show: true, lineStyle: { color: 'rgba(148, 163, 184, 0.12)', width: 1 } }
    },
    tooltip: {
      trigger: 'axis',
      renderMode: 'richText',
      borderWidth: 1,
      backgroundColor: 'rgba(15, 23, 42, 0.95)',
      borderColor: 'rgba(148, 163, 184, 0.22)',
      textStyle: { color: '#e2e8f0', fontSize: 12 },
      axisPointer: {
        type: 'cross',
        crossStyle: { color: 'rgba(148, 163, 184, 0.2)' },
        lineStyle: { color: 'rgba(148, 163, 184, 0.18)' },
        label: {
          show: true,
          backgroundColor: 'rgba(15, 23, 42, 0.92)',
          borderColor: 'rgba(148, 163, 184, 0.25)',
          borderWidth: 1,
          padding: [6, 8],
          color: '#e2e8f0'
        }
      }
    },
    media: [
      {
        query: { maxWidth: 520 },
        option: {
          grid: { left: 12, right: 12, top: 28, bottom: 26, containLabel: true },
          legend: { itemGap: 8, itemWidth: 9, itemHeight: 9, textStyle: { fontSize: 11 } },
          xAxis: { axisLabel: { fontSize: 10, margin: 10 } },
          yAxis: { axisLabel: { fontSize: 10, margin: 10 } }
        }
      }
    ]
  }
}

function polishSeries(option: any): any {
  const seriesRaw = option?.series
  const series = Array.isArray(seriesRaw) ? seriesRaw : seriesRaw ? [seriesRaw] : []
  if (!series.length) return option

  const nextSeries = series.map((s: any) => {
    const type = String(s?.type || 'line')
    const base: any = { ...s }

    const emphasis = { ...(base.emphasis || {}), focus: (base.emphasis?.focus ?? 'series') as any }
    base.emphasis = emphasis

    if (type === 'line') {
      if (base.smooth == null) base.smooth = true
      if (base.showSymbol == null) base.showSymbol = false
      if (base.symbol == null) base.symbol = 'circle'
      if (base.symbolSize == null) base.symbolSize = 7

      const lineStyle = { ...(base.lineStyle || {}) }
      if (lineStyle.width == null) lineStyle.width = 3
      if (lineStyle.shadowBlur == null) lineStyle.shadowBlur = 14
      if (lineStyle.shadowOffsetY == null) lineStyle.shadowOffsetY = 8
      if (lineStyle.shadowColor == null) lineStyle.shadowColor = 'rgba(2, 8, 20, 0.55)'
      base.lineStyle = lineStyle

      base.emphasis = {
        ...base.emphasis,
        scale: base.emphasis?.scale ?? true
      }
    }

    if (type === 'bar') {
      if (base.barMaxWidth == null) base.barMaxWidth = 26
      const itemStyle = { ...(base.itemStyle || {}) }
      if (itemStyle.borderRadius == null) itemStyle.borderRadius = 8
      if (itemStyle.shadowBlur == null) itemStyle.shadowBlur = 16
      if (itemStyle.shadowOffsetY == null) itemStyle.shadowOffsetY = 10
      if (itemStyle.shadowColor == null) itemStyle.shadowColor = 'rgba(2, 8, 20, 0.5)'
      base.itemStyle = itemStyle
    }

    return base
  })

  return { ...option, series: Array.isArray(seriesRaw) ? nextSeries : nextSeries[0] }
}

function formatNumber(value: number) {
  const abs = Math.abs(value)
  const hasDecimals = Math.abs(value - Math.round(value)) > 1e-6
  const digits = abs < 1 ? 3 : abs < 100 ? 2 : hasDecimals ? 2 : 0
  return value.toLocaleString(undefined, { maximumFractionDigits: digits })
}

const eur = new Intl.NumberFormat('es-ES', { style: 'currency', currency: 'EUR', maximumFractionDigits: 2 })

function defaultFormat(value: unknown, valueSuffix: string) {
  if (value == null) return '—'
  const n = typeof value === 'number' ? value : Number((value as any)?.value ?? value)
  if (!Number.isFinite(n)) return String(value)

  const unit = (valueSuffix || '').trim()
  if (unit === '%' || unit === 'pct') return `${n.toFixed(1)}%`
  if (unit === '€' || unit.toUpperCase() === 'EUR') return eur.format(n)
  return `${formatNumber(n)}${unit || ''}`
}

function extractSeriesDataPoint(raw: any): any {
  if (raw == null) return null
  if (typeof raw === 'number') return raw
  if (Array.isArray(raw)) return raw.length ? raw[raw.length - 1] : null
  if (typeof raw === 'object' && raw && 'value' in raw) return (raw as any).value
  return raw
}

function hasAnyData(option: any): boolean {
  const series = option?.series
  const arr = Array.isArray(series) ? series : series ? [series] : []
  for (const s of arr) {
    const data = (s as any)?.data
    if (Array.isArray(data) && data.length) return true
  }
  return false
}

function withAutoZoom(option: any): any {
  try {
    if (option?.dataZoom) return option
    const xAxis = Array.isArray(option?.xAxis) ? option.xAxis[0] : option?.xAxis
    const labels = Array.isArray(xAxis?.data) ? xAxis.data : []
    if (!labels.length || labels.length <= 22) return option
    return {
      ...option,
      dataZoom: [
        { type: 'inside', start: 0, end: 100, filterMode: 'none' },
        {
          type: 'slider',
          height: 18,
          bottom: 6,
          borderColor: 'rgba(148, 163, 184, 0.18)',
          fillerColor: 'rgba(96, 165, 250, 0.12)',
          backgroundColor: 'rgba(148, 163, 184, 0.06)',
          handleStyle: { color: 'rgba(96, 165, 250, 0.55)' },
          textStyle: { color: 'rgba(226, 232, 240, 0.7)' }
        }
      ]
    }
  } catch {
    return option
  }
}

export default function EChart({
  option,
  module = 'default',
  loading,
  error,
  emptyTitle = 'Sin datos',
  emptyHint = 'Sube un CSV/XLSX o ajusta filtros/periodo para ver resultados.',
  errorTitle = 'No se pudo cargar la gráfica',
  errorHint = 'Refresca la página. Si persiste, revisa el import o el límite de filas.',
  actions = true,
  valueSuffix = '',
  className,
  height,
  onClick,
  onAxisHover,
  onLeave
}: Props) {
  const elRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<EChartsType | null>(null)
  const [ready, setReady] = useState(false)

  const mergedOption = useMemo(() => {
    const withTheme = mergeOptions(baseOption(module), option)
    const withZoom = withAutoZoom(withTheme as any)
    const withPolish = polishSeries(withZoom as any)
    const extraTooltip = {
      tooltip: {
        formatter: (params: any) => {
          const items = Array.isArray(params) ? params : [params]
          const axisLabel = String(items?.[0]?.axisValue ?? items?.[0]?.name ?? '')
          const rows = items
            .map((p: any) => {
              const name = String(p?.seriesName ?? '')
              const value = defaultFormat(extractSeriesDataPoint(p?.data), valueSuffix)
              return `${name}: ${value}`
            })
            .join('\n')
          return `${axisLabel}\n${rows}`
        }
      },
      axisPointer: { link: [{ xAxisIndex: 'all' }] }
    }
    return mergeOptions(withPolish as any, extraTooltip as any)
  }, [module, option, valueSuffix])

  const isEmpty = useMemo(() => !hasAnyData(mergedOption as any), [mergedOption])

  useEffect(() => {
    let mounted = true
    let ro: ResizeObserver | null = null

    ;(async () => {
      const el = elRef.current
      if (!el) return
      const echarts = await import('./echarts')
      if (!mounted) return
      const chart = echarts.init(el, undefined, { renderer: 'canvas' })
      chartRef.current = chart
      chart.setOption(mergedOption, { notMerge: true })

      ro = new ResizeObserver(() => chart.resize())
      ro.observe(el)
      setReady(true)
    })()

    return () => {
      mounted = false
      try {
        ro?.disconnect()
      } catch (e) {
        // ignore
      }
      try {
        chartRef.current?.dispose()
      } catch (e) {
        // ignore
      } finally {
        chartRef.current = null
      }
    }
  }, [])

  useEffect(() => {
    if (!ready) return
    const chart = chartRef.current
    if (!chart) return
    if (!onClick) return

    const handler = (params: any) => onClick(params)
    chart.on('click', handler)
    return () => {
      try {
        chart.off('click', handler)
      } catch (e) {
        // ignore
      }
    }
  }, [ready, onClick])

  useEffect(() => {
    if (!ready) return
    const chart = chartRef.current
    if (!chart) return
    if (!onAxisHover && !onLeave) return

    let lastAxisValue: string | null = null

    const handleAxisPointer = (evt: any) => {
      if (!onAxisHover) return
      const axisValue = String(evt?.axesInfo?.[0]?.value ?? '')
      if (!axisValue) return
      if (axisValue === lastAxisValue) return
      lastAxisValue = axisValue
      onAxisHover(axisValue)
    }

    const handleGlobalOut = () => {
      lastAxisValue = null
      onLeave?.()
    }

    try {
      chart.on('updateAxisPointer', handleAxisPointer)
      chart.on('globalout', handleGlobalOut)
    } catch {
      // ignore
    }

    return () => {
      try {
        chart.off('updateAxisPointer', handleAxisPointer)
        chart.off('globalout', handleGlobalOut)
      } catch {
        // ignore
      }
    }
  }, [ready, onAxisHover, onLeave])

  useEffect(() => {
    if (!ready) return
    chartRef.current?.setOption(mergedOption, { notMerge: true, lazyUpdate: true })
  }, [mergedOption, ready])

  const downloadPng = () => {
    const chart = chartRef.current
    if (!chart) return
    const url = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: 'transparent' })
    const a = document.createElement('a')
    a.href = url
    a.download = `chart-${module}.png`
    document.body.appendChild(a)
    a.click()
    a.remove()
  }

  const downloadCsv = () => {
    const opt: any = mergedOption as any
    const xAxis = Array.isArray(opt?.xAxis) ? opt.xAxis[0] : opt?.xAxis
    const labels = Array.isArray(xAxis?.data) ? xAxis.data : []
    const series = Array.isArray(opt?.series) ? opt.series : opt?.series ? [opt.series] : []
    if (!labels.length || !series.length) return

    const header = ['x', ...series.map((s: any, idx: number) => String(s?.name || `S${idx + 1}`))]
    const rows = labels.map((x: any, i: number) => {
      const cells = [String(x)]
      for (const s of series) {
        const raw = Array.isArray(s?.data) ? s.data[i] : undefined
        const v = extractSeriesDataPoint(raw)
        cells.push(v == null ? '' : String(v))
      }
      return cells
    })
    const csv = [header, ...rows]
      .map((r) =>
        r
          .map((c: any) => {
            const v = String(c ?? '')
            if (v.includes('"') || v.includes(',') || v.includes('\n')) return `"${v.replace(/\"/g, '""')}"`
            return v
          })
          .join(',')
      )
      .join('\n')

    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `chart-${module}.csv`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  const showSkeleton = loading || !ready

  const resolvedHeight = typeof height === 'number' && height > 0 ? height : 260

  const heightClass = (() => {
    switch (resolvedHeight) {
      case 240:
        return 'chart-h-240'
      case 260:
        return 'chart-h-260'
      case 320:
        return 'chart-h-320'
      case 360:
        return 'chart-h-360'
      case 420:
        return 'chart-h-420'
      default:
        return 'chart-h-260'
    }
  })()

  const canvasHidden = showSkeleton || !!error || isEmpty

  return (
    <div className={`chart-shell ${heightClass} ${className || ''}`.trim()}>
      {showSkeleton ? <Skeleton className="chart-skeleton" /> : null}
      {!showSkeleton && error ? (
        <div className="empty chart-state">
          <div className="chart-state-title">{errorTitle}</div>
          <div>{String(error)}</div>
          {errorHint ? <div className="upload-hint mt-8">{errorHint}</div> : null}
        </div>
      ) : null}
      {!showSkeleton && !error && isEmpty ? (
        <div className="empty chart-state">
          <div className="chart-state-title">{emptyTitle}</div>
          <div className="upload-hint">{emptyHint}</div>
        </div>
      ) : null}

      <div
        ref={elRef}
        className={`chart-canvas ${canvasHidden ? 'chart-canvas-hidden' : ''}`.trim()}
      />

      {!showSkeleton && !error && !isEmpty && actions ? (
        <div className="chart-actions">
          <Button variant="ghost" size="sm" onClick={downloadPng}>
            PNG
          </Button>
          <Button variant="ghost" size="sm" onClick={downloadCsv}>
            CSV
          </Button>
        </div>
      ) : null}
    </div>
  )
}
