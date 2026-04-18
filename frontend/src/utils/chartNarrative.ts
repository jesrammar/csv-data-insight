import { formatChartValue } from './chartFormat'
import { formatMoney } from './format'

type CashKpi = {
  period: string
  inflows: number
  outflows: number
  netFlow: number
  endingBalance: number
}

function avg(nums: number[]) {
  const list = nums.filter((n) => Number.isFinite(n))
  if (!list.length) return 0
  return list.reduce((acc, v) => acc + v, 0) / list.length
}

function avgWindow(nums: number[], endIdx: number, window: number) {
  if (!nums.length) return null
  const end = Math.max(0, Math.min(nums.length - 1, endIdx))
  const start = Math.max(0, end - window + 1)
  const slice = nums.slice(start, end + 1).filter((n) => Number.isFinite(n))
  if (!slice.length) return null
  return slice.reduce((acc, v) => acc + v, 0) / slice.length
}

export function buildCashFlowNarrative(kpis: CashKpi[], focusPeriod?: string | null) {
  // Optional "drivers" can be appended by caller after computing narrative.
  const list = Array.isArray(kpis) ? kpis : []
  if (!list.length) {
    return { see: [], why: [], todo: [] }
  }

  const idx =
    focusPeriod ? Math.max(0, list.findIndex((k) => String(k.period) === String(focusPeriod))) : list.length - 1
  const safeIdx = idx >= 0 ? idx : list.length - 1

  const last = list[safeIdx]!
  const prev = safeIdx >= 1 ? list[safeIdx - 1]! : null

  const inflows = Number(last.inflows || 0)
  const outflows = Math.abs(Number(last.outflows || 0))
  const net = Number(last.netFlow || 0)
  const bal = Number(last.endingBalance || 0)

  const prevNet = prev ? Number(prev.netFlow || 0) : null
  const prevBal = prev ? Number(prev.endingBalance || 0) : null
  const dNet = prevNet == null ? null : net - prevNet
  const dBal = prevBal == null ? null : bal - prevBal

  const outflows3 = list.slice(Math.max(0, safeIdx + 1 - 3), safeIdx + 1).map((k) => Math.abs(Number(k.outflows || 0)))
  const avgOut3 = avg(outflows3)
  const runway = avgOut3 > 1e-9 ? bal / avgOut3 : null

  const nets = list.map((k) => Number(k.netFlow || 0))
  const avgNet3 = avgWindow(nets, safeIdx, 3)
  const avgNet6 = avgWindow(nets, safeIdx, 6)

  const see: string[] = []
  see.push(
    `${String(last.period)}: entradas ${formatChartValue(inflows, '€')}, salidas ${formatChartValue(-outflows, '€')} y neto ${formatChartValue(net, '€')}.`
  )
  see.push(
    `Saldo final estimado: ${formatChartValue(bal, '€')}${dBal == null ? '' : ` (${dBal >= 0 ? '+' : ''}${formatChartValue(dBal, '€')} vs mes anterior)`}.`
  )

  const why: string[] = []
  if (dNet != null) {
    const parts = [`Δ neto vs mes anterior: ${dNet >= 0 ? '+' : ''}${formatChartValue(dNet, '€')}`]
    if (avgNet3 != null || avgNet6 != null) {
      parts.push(`Media neto 3m/6m: ${avgNet3 == null ? '—' : formatChartValue(avgNet3, '€')} / ${avgNet6 == null ? '—' : formatChartValue(avgNet6, '€')}`)
    }
    why.push(parts.join(' · ') + '.')
  } else {
    why.push('Comparativa: sube al menos 2 periodos para ver cambios mes a mes.')
  }
  if (runway != null && Number.isFinite(runway)) {
    if (runway <= 1.5) why.push(`Runway aproximado: ~${runway.toFixed(1)} meses (si se mantiene el ritmo de salidas).`)
    else why.push(`Runway aproximado: ~${runway.toFixed(1)} meses (estimación simple).`)
  }

  const todo: string[] = []
  if (bal < 0) {
    todo.push('Prioriza cobros inmediatos y frena gasto no crítico hasta volver a saldo positivo.')
    todo.push('Prepara un plan de tesorería de 13 semanas (cobros comprometidos y pagos fijos).')
  } else if (net < 0) {
    todo.push('Revisa vencimientos: cobra antes y renegocia pagos para suavizar el neto.')
    todo.push('Busca “drivers”: top 10 salidas del mes y 3 categorías con mayor subida.')
  } else {
    todo.push('Reserva parte del neto para colchón y fija un mínimo de caja objetivo.')
    if (runway != null && runway < 3) todo.push('Aumenta margen de seguridad (runway bajo) antes de asumir pagos nuevos.')
  }

  return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 3) }
}

type TxAnalyticsDriver = {
  topCounterparties?: Array<{ counterparty: string; total: number; count: number }>
  categories?: Array<{ category: string; total: number; inflows: number; outflows: number; count: number }>
}

export function appendCashDrivers(narrative: { see: string[]; why: string[]; todo: string[] }, drivers?: TxAnalyticsDriver | null) {
  if (!drivers) return narrative

  const cps = Array.isArray(drivers.topCounterparties) ? drivers.topCounterparties : []
  const cats = Array.isArray(drivers.categories) ? drivers.categories : []

  const topCps = cps
    .filter((c) => c && String((c as any).counterparty || '').trim())
    .slice(0, 3)
    .map((c) => `${String(c.counterparty)} (${formatMoney(c.total)})`)

  const topCats = cats
    .filter((c) => c && String((c as any).category || '').trim())
    .slice(0, 3)
    .map((c) => `${String(c.category)} (${formatMoney(c.total)})`)

  const why = [...(narrative.why || [])]
  if (topCps.length) why.unshift(`Top contrapartes (impacto): ${topCps.join(' · ')}.`)
  if (topCats.length) why.unshift(`Top categorías (aprox.): ${topCats.join(' · ')}.`)

  return { ...narrative, why: why.slice(0, 3) }
}

export function buildSeriesNarrative(points: Array<{ label: string; value: number }>, unit: string) {
  const list = Array.isArray(points) ? points : []
  if (!list.length) return { see: [], why: [], todo: [] }

  const last = list[list.length - 1]!
  const prev = list.length >= 2 ? list[list.length - 2]! : null
  const vLast = Number(last.value || 0)
  const vPrev = prev ? Number(prev.value || 0) : null
  const d = vPrev == null ? null : vLast - vPrev
  const values = list.map((p) => Number(p.value || 0))
  const endIdx = Math.max(0, list.length - 1)
  const avg3 = avgWindow(values, endIdx, 3)
  const avg6 = avgWindow(values, endIdx, 6)

  const see: string[] = []
  see.push(`Último dato (${last.label}): ${formatChartValue(vLast, unit)}.`)
  const line2: string[] = []
  if (d != null) line2.push(`Δ vs anterior: ${d >= 0 ? '+' : ''}${formatChartValue(d, unit)}`)
  if (avg3 != null || avg6 != null) {
    line2.push(`Media 3p/6p: ${avg3 == null ? '—' : formatChartValue(avg3, unit)} / ${avg6 == null ? '—' : formatChartValue(avg6, unit)}`)
  }
  if (line2.length) see.push(line2.join(' · ') + '.')
  if (d != null) see.push(`Variación vs anterior: ${d >= 0 ? '+' : ''}${formatChartValue(d, unit)}.`)

  const why: string[] = []
  if (d == null) {
    why.push('Sube más puntos para entender tendencia y variaciones.')
  } else if (Math.abs(d) < 1e-9) {
    why.push('Sin cambio relevante respecto al punto anterior.')
  } else if (d < 0) {
    why.push('Bajada reciente: confirma si es puntual o tendencia (2–3 periodos).')
  } else {
    why.push('Subida reciente: valida que no sea efecto puntual (cobro/gasto único).')
  }

  const todo: string[] = []
  if (d != null && d < 0) todo.push('Identifica el driver del cambio: top categorías/contrapartes o líneas que más aportan.')
  todo.push('Marca eventos (import, campaña, pago grande) para explicar cambios a cliente.')

  return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 2) }
}

export function buildSeriesNarrativeForLabel(
  points: Array<{ label: string; value: number }>,
  unit: string,
  focusLabel?: string | null
) {
  const list = Array.isArray(points) ? points : []
  if (!list.length) return { see: [], why: [], todo: [] }
  if (!focusLabel) return buildSeriesNarrative(list, unit)

  const idx = list.findIndex((p) => String(p.label) === String(focusLabel))
  const safeIdx = idx >= 0 ? idx : list.length - 1

  const last = list[safeIdx]!
  const prev = safeIdx >= 1 ? list[safeIdx - 1]! : null
  const vLast = Number(last.value || 0)
  const vPrev = prev ? Number(prev.value || 0) : null
  const d = vPrev == null ? null : vLast - vPrev
  const values = list.map((p) => Number(p.value || 0))
  const avg3 = avgWindow(values, safeIdx, 3)
  const avg6 = avgWindow(values, safeIdx, 6)

  const see: string[] = []
  see.push(`Dato (${last.label}): ${formatChartValue(vLast, unit)}.`)
  const line2: string[] = []
  if (d != null) line2.push(`Δ vs anterior: ${d >= 0 ? '+' : ''}${formatChartValue(d, unit)}`)
  if (avg3 != null || avg6 != null) {
    line2.push(`Media 3p/6p: ${avg3 == null ? '—' : formatChartValue(avg3, unit)} / ${avg6 == null ? '—' : formatChartValue(avg6, unit)}`)
  }
  if (line2.length) see.push(line2.join(' · ') + '.')
  if (d != null) see.push(`Variación vs anterior: ${d >= 0 ? '+' : ''}${formatChartValue(d, unit)}.`)

  const why: string[] = []
  if (d == null) {
    why.push('Primer punto del rango seleccionado (sin comparativa previa).')
  } else if (Math.abs(d) < 1e-9) {
    why.push('Sin cambio relevante respecto al punto anterior.')
  } else if (d < 0) {
    why.push('Bajada en este punto: valida si es puntual o parte de una tendencia.')
  } else {
    why.push('Subida en este punto: comprueba si viene de un evento puntual.')
  }

  const todo: string[] = []
  if (d != null && d < 0) todo.push('Busca drivers del cambio (categorías/contrapartes) para explicarlo al cliente.')
  todo.push('Si es un pico, marca el evento (pago grande / cobro único) para que se entienda.')

  return { see: see.slice(0, 2), why: why.slice(0, 2), todo: todo.slice(0, 2) }
}
