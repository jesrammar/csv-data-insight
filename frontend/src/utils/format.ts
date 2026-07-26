const eur = new Intl.NumberFormat('es-ES', { style: 'currency', currency: 'EUR', maximumFractionDigits: 2 })
const dateTimeEs = new Intl.DateTimeFormat('es-ES', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit'
})

export const EMPTY_VALUE = '—'
export const EMPTY_DATA_TEXT = 'Sin datos'
export const EMPTY_MESSAGE_TEXT = 'Sin mensaje'
export const EMPTY_ACTIVITY_TEXT = 'Sin actividad todavía'

export function formatMoney(value: unknown) {
  const n = typeof value === 'string' && value.trim() === '' ? NaN : Number(value)
  return Number.isFinite(n) ? eur.format(n) : '-'
}

export function formatIsoDateTime(value: unknown) {
  const raw = String(value || '')
  if (!raw) return '-'
  return raw.length >= 19 ? raw.slice(0, 19).replace('T', ' ') : raw.replace('T', ' ')
}

export function formatDateTime(value: unknown, fallback = EMPTY_VALUE) {
  const raw = String(value || '')
  if (!raw) return fallback
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return raw.length >= 19 ? raw.slice(0, 19).replace('T', ' ') : raw.replace('T', ' ')
  return dateTimeEs.format(date)
}

export function formatText(value: unknown, fallback = EMPTY_VALUE) {
  const text = String(value ?? '').trim()
  return text || fallback
}

