const eur = new Intl.NumberFormat('es-ES', { style: 'currency', currency: 'EUR', maximumFractionDigits: 2 })

export function formatCompactNumber(n: number) {
  const abs = Math.abs(n)
  if (abs >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(1)}B`
  if (abs >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (abs >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

export function formatChartValue(n: number, unit?: string) {
  if (!Number.isFinite(n)) return '—'
  if (unit === '%') return `${n.toFixed(1)}%`
  if (unit === '€' || unit?.toUpperCase() === 'EUR') return eur.format(n)
  return `${formatCompactNumber(n)}${unit || ''}`
}
