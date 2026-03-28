const eur = new Intl.NumberFormat('es-ES', { style: 'currency', currency: 'EUR', maximumFractionDigits: 2 })

export function formatMoney(value: unknown) {
  const n = typeof value === 'string' && value.trim() === '' ? NaN : Number(value)
  return Number.isFinite(n) ? eur.format(n) : '-'
}

export function formatIsoDateTime(value: unknown) {
  const raw = String(value || '')
  if (!raw) return '-'
  return raw.length >= 19 ? raw.slice(0, 19).replace('T', ' ') : raw.replace('T', ' ')
}

