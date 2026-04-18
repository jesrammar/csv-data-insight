export function nowYm() {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

export function getWorkPeriod(companyId: number | null | undefined) {
  if (!companyId) return null
  const raw = localStorage.getItem(`workPeriod:${companyId}`)
  return raw ? String(raw) : null
}

export function setWorkPeriod(companyId: number | null | undefined, period: string) {
  if (!companyId) return
  localStorage.setItem(`workPeriod:${companyId}`, period)
}

