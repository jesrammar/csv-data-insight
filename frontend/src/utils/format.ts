const eur = new Intl.NumberFormat('es-ES', { style: 'currency', currency: 'EUR', maximumFractionDigits: 2 })
const dateTimeEs = new Intl.DateTimeFormat('es-ES', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit'
})

export const EMPTY_VALUE = '-'
export const EMPTY_DATA_TEXT = 'Sin datos'
export const EMPTY_MESSAGE_TEXT = 'Sin mensaje'
export const EMPTY_ACTIVITY_TEXT = 'Sin actividad todavia'

const TEXT_REPAIRS: Array<[RegExp, string]> = [
  [/\u00C3\u00A1/g, 'á'],
  [/\u00C3\u00A9/g, 'é'],
  [/\u00C3\u00AD/g, 'í'],
  [/\u00C3\u00B3/g, 'ó'],
  [/\u00C3\u00BA/g, 'ú'],
  [/\u00C3\u0081/g, 'Á'],
  [/\u00C3\u0089/g, 'É'],
  [/\u00C3\u008D/g, 'Í'],
  [/\u00C3\u0093/g, 'Ó'],
  [/\u00C3\u009A/g, 'Ú'],
  [/\u00C3\u00B1/g, 'ñ'],
  [/\u00C3\u0091/g, 'Ñ'],
  [/\u00C2\u00BF/g, '¿'],
  [/\u00C2\u00A1/g, '¡'],
  [/\u00C2\u00B7/g, '·'],
  [/\u00E2\u0080\u00A2/g, '•'],
  [/\u00E2\u0080\u00A6/g, '...'],
  [/\u00E2\u0080\u0094/g, '-'],
  [/\u00E2\u0080\u0093/g, '-'],
  [/\u00E2\u0080\u009C/g, '"'],
  [/\u00E2\u0080\u009D/g, '"'],
  [/\u00E2\u0080\u0098/g, "'"],
  [/\u00E2\u0080\u0099/g, "'"],
  [/\uFFFD/g, ''],
  [/\u00C2/g, '']
]

export function normalizeText(value: unknown, fallback = EMPTY_VALUE) {
  const raw = String(value ?? '').trim()
  if (!raw) return fallback

  let candidate = raw
  try {
    const parsed = JSON.parse(raw)
    if (typeof parsed === 'string' && parsed.trim()) candidate = parsed.trim()
  } catch {
    // preserve raw text
  }

  let repaired = candidate.replace(/\\u([0-9a-fA-F]{4})/g, (_, hex) => String.fromCharCode(Number.parseInt(hex, 16)))
  for (const [pattern, replacement] of TEXT_REPAIRS) {
    repaired = repaired.replace(pattern, replacement)
  }

  return repaired.trim() || fallback
}

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
  const text = normalizeText(value, '').trim()
  return text || fallback
}
