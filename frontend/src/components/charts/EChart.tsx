import { useEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import type { EChartsCoreOption, EChartsType } from './echarts'

type Props = {
  option: EChartsCoreOption
  className?: string
  style?: CSSProperties
  onClick?: (params: any) => void
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

const BASE_OPTION: EChartsCoreOption = {
  backgroundColor: 'transparent',
  textStyle: { color: '#e2e8f0', fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, sans-serif' },
  grid: { left: 14, right: 14, top: 28, bottom: 24, containLabel: true },
  tooltip: {
    trigger: 'axis',
    borderWidth: 1,
    backgroundColor: 'rgba(15, 23, 42, 0.95)',
    borderColor: 'rgba(148, 163, 184, 0.25)',
    textStyle: { color: '#e2e8f0' },
    axisPointer: { type: 'line', lineStyle: { color: 'rgba(148, 163, 184, 0.25)' } }
  }
}

export default function EChart({ option, className, style, onClick }: Props) {
  const elRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<EChartsType | null>(null)
  const [ready, setReady] = useState(false)

  const mergedOption = useMemo(() => mergeOptions(BASE_OPTION, option), [option])

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
    chartRef.current?.setOption(mergedOption, { notMerge: true, lazyUpdate: true })
  }, [mergedOption, ready])

  return <div ref={elRef} className={className} style={{ width: '100%', height: 260, ...style }} />
}
