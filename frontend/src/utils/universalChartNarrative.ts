import { formatChartValue, formatCompactNumber } from './chartFormat'
import type { UniversalChartData } from '../api'

function safeNumber(x: any) {
  const n = typeof x === 'number' ? x : Number(x)
  return Number.isFinite(n) ? n : null
}

function sum(nums: Array<number | null | undefined>): number {
  return nums.reduce<number>((acc, v) => acc + (Number.isFinite(v as any) ? Number(v) : 0), 0)
}

function pct(part: number, total: number) {
  if (!Number.isFinite(part) || !Number.isFinite(total) || Math.abs(total) < 1e-9) return null
  return (part / total) * 100
}

function pearson(xs: number[], ys: number[]) {
  const n = Math.min(xs.length, ys.length)
  if (n < 3) return null
  const meanX = xs.reduce((a, v) => a + v, 0) / n
  const meanY = ys.reduce((a, v) => a + v, 0) / n
  let num = 0
  let denX = 0
  let denY = 0
  for (let i = 0; i < n; i++) {
    const dx = xs[i]! - meanX
    const dy = ys[i]! - meanY
    num += dx * dy
    denX += dx * dx
    denY += dy * dy
  }
  const den = Math.sqrt(denX * denY)
  if (!Number.isFinite(den) || den < 1e-12) return null
  const r = num / den
  if (!Number.isFinite(r)) return null
  return Math.max(-1, Math.min(1, r))
}

function buildCategoryNarrative(labels: string[], values: any[], unit: string) {
  const pairs = labels.map((l, idx) => ({ label: String(l || '—'), value: safeNumber(values?.[idx]) ?? 0 }))
  if (!pairs.length) return { see: [], why: [], todo: [] }
  const sorted = [...pairs].sort((a, b) => b.value - a.value)
  const top = sorted[0]!
  const total = sum(pairs.map((p) => p.value))
  const topShare = pct(top.value, total)

  const see: string[] = []
  see.push(`Top: ${top.label} (${formatChartValue(top.value, unit)}).`)
  if (sorted.length >= 2) see.push(`2º: ${sorted[1]!.label} (${formatChartValue(sorted[1]!.value, unit)}).`)

  const why: string[] = []
  if (topShare != null && topShare >= 50) why.push(`Alta concentración: el top explica ~${topShare.toFixed(0)}% del total.`)
  else why.push(`Ranking de ${pairs.length} categorías: útil para localizar “drivers”.`)

  const todo: string[] = []
  todo.push('Revisa el top-3 y valida si son recurrentes o puntuales.')
  todo.push('Si el top no cuadra, revisa filtros/periodo o valores nulos.')

  return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 2) }
}

function buildScatterNarrative(chart: UniversalChartData) {
  const pts = ((chart.series as any)?.[0]?.data || []) as any[]
  const xs: number[] = []
  const ys: number[] = []
  for (const p of pts.slice(0, 4000)) {
    if (!Array.isArray(p) || p.length < 2) continue
    const x = safeNumber(p[0])
    const y = safeNumber(p[1])
    if (x == null || y == null) continue
    xs.push(x)
    ys.push(y)
  }

  const r = pearson(xs, ys)
  const xName = String((chart.meta as any)?.xColumn || 'X')
  const yName = String((chart.meta as any)?.yColumn || 'Y')

  const see: string[] = []
  see.push(`Puntos: ${formatCompactNumber(xs.length)} (muestra).`)
  if (r != null) see.push(`Correlación aprox. ${xName}↔${yName}: r=${r.toFixed(2)}.`)

  const why: string[] = []
  why.push('Sirve para ver relación (tendencia) y outliers.')
  why.push('Correlación no implica causalidad.')

  const todo: string[] = []
  todo.push('Filtra por segmentos (categoría/periodo) para que se entienda.')
  todo.push('Identifica outliers y revisa filas origen.')

  return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 2) }
}

function buildHeatmapNarrative(chart: UniversalChartData) {
  const pts = ((chart.series as any)?.[0]?.data || []) as any[]
  const xLabels = chart.labels || []
  const yLabels = ((chart.meta as any)?.yLabels || []) as any[]

  let best: { x: number; y: number; v: number } | null = null
  for (const p of pts) {
    if (!Array.isArray(p) || p.length < 3) continue
    const x = safeNumber(p[0])
    const y = safeNumber(p[1])
    const v = safeNumber(p[2])
    if (x == null || y == null || v == null) continue
    if (!best || v > best.v) best = { x, y, v }
  }

  const see: string[] = []
  if (best) {
    const x = xLabels[Math.round(best.x)] ?? String(best.x)
    const y = yLabels[Math.round(best.y)] ?? String(best.y)
    see.push(`Máximo: ${String(x)} · ${String(y)} (${formatCompactNumber(best.v)}).`)
  } else {
    see.push('Sin celdas suficientes para localizar máximos.')
  }
  see.push('Más oscuro = valor más alto (según escala).')

  const why: string[] = []
  why.push('Útil para ver “zonas” de concentración (combinaciones frecuentes/altas).')
  why.push('Si no se entiende, cambia a ranking (bar) o serie temporal.')

  const todo: string[] = []
  todo.push('Empieza por el máximo y revisa filas origen (qué lo provoca).')
  todo.push('Si hay muchas categorías, reduce top-N o aplica filtros.')

  return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 2) }
}

function buildPivotNarrative(chart: UniversalChartData, focusLabel?: string | null) {
  const series = Array.isArray(chart.series) ? chart.series : []
  if (!series.length) return { see: [], why: [], todo: [] }

  const rawFocus = String(focusLabel || '')
  if (rawFocus.includes('||')) {
    const [catRaw, monthRaw] = rawFocus.split('||', 2)
    const cat = String(catRaw || '').trim()
    const month = String(monthRaw || '').trim()
    const monthIdx = (chart.labels || []).findIndex((m) => String(m) === month)
    const s = series.find((it) => String((it as any)?.name || '') === cat) as any
    const data = (s?.data || []) as any[]
    if (cat && monthIdx >= 0 && data.length) {
      const cur = safeNumber(data[monthIdx]) ?? 0
      const prev = monthIdx >= 1 ? safeNumber(data[monthIdx - 1]) : null
      const d = prev == null ? null : cur - prev
      const nums = data.map((v: any) => safeNumber(v) ?? Number.NaN)
      const avgWindow = (window: number) => {
        const end = Math.max(0, Math.min(nums.length - 1, monthIdx))
        const start = Math.max(0, end - window + 1)
        const slice = nums.slice(start, end + 1).filter((n) => Number.isFinite(n))
        if (!slice.length) return null
        return slice.reduce((acc, v) => acc + v, 0) / slice.length
      }
      const avg3 = avgWindow(3)
      const avg6 = avgWindow(6)

      const see: string[] = []
      see.push(`${cat} · ${month}: ${formatCompactNumber(cur)}.`)
      const line2: string[] = []
      if (d != null) line2.push(`Δ vs mes anterior: ${d >= 0 ? '+' : ''}${formatCompactNumber(d)}`)
      if (avg3 != null || avg6 != null) line2.push(`Media 3m/6m: ${avg3 == null ? '—' : formatCompactNumber(avg3)} / ${avg6 == null ? '—' : formatCompactNumber(avg6)}`)
      if (line2.length) see.push(line2.join(' · ') + '.')

      const why: string[] = []
      why.push('Drivers por periodo: identifica qué categoría explica el cambio del mes.')
      why.push('Si hay demasiadas series, reduce a top-N.')

      const todo: string[] = []
      todo.push('Marca meses con picos y revisa evidencia (filas) para justificarlo.')
      todo.push('Convierte a “Top categorías” si necesitas un mensaje más simple.')
      return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 2) }
    }
  }

  const totals = series.map((s) => ({
    name: String(s?.name || '—'),
    total: sum((s?.data || []).map((v) => safeNumber(v) ?? 0))
  }))
  totals.sort((a, b) => b.total - a.total)
  const top = totals[0]

  const see: string[] = []
  if (top) see.push(`Mayor contribución: ${top.name} (${formatCompactNumber(top.total)} total).`)
  see.push(`Meses: ${chart.labels?.length || 0} · Series: ${series.length}.`)

  const why: string[] = []
  why.push('Desglosa el total por categoría y mes (drivers por periodo).')
  why.push('Si hay demasiadas series, reduce a top-N.')

  const todo: string[] = []
  todo.push('Busca meses con cambios bruscos y revisa qué categoría lo explica.')
  todo.push('Convierte a “Top categorías” si quieres un mensaje más simple.')

  return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 2) }
}

function buildKpiCardsNarrative(chart: UniversalChartData, unit: string) {
  const labels = chart.labels || []
  const values = (((chart.series as any)?.[0]?.data || []) as any[]).map((v) => safeNumber(v))
  const finite = values.filter((v) => v != null) as number[]
  if (!labels.length) return { see: [], why: [], todo: [] }

  const max = finite.length ? Math.max(...finite) : null
  const min = finite.length ? Math.min(...finite) : null
  const missing = values.filter((v) => v == null).length

  const see: string[] = []
  if (max != null) see.push(`Rango: ${formatChartValue(min ?? 0, unit)} → ${formatChartValue(max, unit)}.`)
  if (missing) see.push(`Valores no numéricos: ${missing}.`)

  const why: string[] = []
  why.push('KPIs resumen: sirven para dar contexto rápido antes de drill-down.')
  if (missing) why.push('Valores inválidos suelen venir de columnas vacías o tipos mal detectados.')

  const todo: string[] = []
  todo.push('Elige 1–2 KPIs “drivers” y explica su origen (columna/filtro).')
  if (missing) todo.push('Revisa calidad de dato (nulos/formatos) y vuelve a importar.')

  return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 2) }
}

export function buildUniversalChartNarrative(chart: UniversalChartData | null | undefined, focusLabel?: string | null) {
  if (!chart) return { see: [], why: [], todo: [] }
  const t = String(chart.type || '').toUpperCase()
  const unit = String((chart.meta as any)?.unit || '').trim()

  if (t === 'KPI_CARDS') return buildKpiCardsNarrative(chart, unit)
  if (t === 'SCATTER') return buildScatterNarrative(chart)
  if (t === 'HEATMAP') return buildHeatmapNarrative(chart)
  if (t === 'PIVOT_MONTHLY') return buildPivotNarrative(chart, focusLabel)

  // Default: axis charts
  const labels = chart.labels || []
  const series0 = (chart.series as any)?.[0] || null
  const values = (series0?.data || []) as any[]

  // Time series narrative (hover aware) if labels look like dates/periods
  const looksLikePeriod = labels.some((l) => /^\d{4}-\d{2}/.test(String(l)))
  if (looksLikePeriod && labels.length && values.length) {
    // Local implementation to avoid importing React hooks. Reuse the same logic as series narrative for label.
    const idx = focusLabel ? labels.findIndex((l) => String(l) === String(focusLabel)) : labels.length - 1
    const safeIdx = idx >= 0 ? idx : labels.length - 1
    const cur = safeNumber(values[safeIdx]) ?? 0
    const prev = safeIdx >= 1 ? safeNumber(values[safeIdx - 1]) : null
    const d = prev == null ? null : cur - prev
    const nums = values.map((v) => safeNumber(v) ?? Number.NaN)
    const avgWindow = (window: number) => {
      const end = Math.max(0, Math.min(nums.length - 1, safeIdx))
      const start = Math.max(0, end - window + 1)
      const slice = nums.slice(start, end + 1).filter((n) => Number.isFinite(n))
      if (!slice.length) return null
      return slice.reduce((acc, v) => acc + v, 0) / slice.length
    }
    const avg3 = avgWindow(3)
    const avg6 = avgWindow(6)

    const see: string[] = []
    see.push(`Dato (${labels[safeIdx] ?? '—'}): ${formatChartValue(cur, unit)}.`)
    const line2: string[] = []
    if (d != null) line2.push(`Δ vs anterior: ${d >= 0 ? '+' : ''}${formatChartValue(d, unit)}`)
    if (avg3 != null || avg6 != null) {
      line2.push(`Media 3p/6p: ${avg3 == null ? '—' : formatChartValue(avg3, unit)} / ${avg6 == null ? '—' : formatChartValue(avg6, unit)}`)
    }
    if (line2.length) see.push(line2.join(' · ') + '.')
    if (d != null) see.push(`Variación vs anterior: ${d >= 0 ? '+' : ''}${formatChartValue(d, unit)}.`)

    const why: string[] = []
    if (d == null) why.push('Primer punto del rango (sin comparativa).')
    else if (Math.abs(d) < 1e-9) why.push('Sin cambio relevante respecto al punto anterior.')
    else if (d < 0) why.push('Bajada reciente: confirma si es puntual o tendencia (2–3 periodos).')
    else why.push('Subida reciente: valida que no sea efecto puntual (cobro/gasto único).')
    why.push('Tendencias ayudan a justificar acciones en el informe.')

    const todo: string[] = []
    if (d != null && d < 0) todo.push('Busca drivers del cambio (categorías/segmentos) para explicarlo.')
    todo.push('Añade un filtro o cambia a “Top categorías” si necesitas mensaje más simple.')

    return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 2) }
  }

  return buildCategoryNarrative(labels, values, unit)
}
