import type { UniversalIntakeDiagnosis } from '../api'

export function intakeKind(diagnosis?: UniversalIntakeDiagnosis | null) {
  return String(diagnosis?.kind || '').trim().toUpperCase()
}

export function isAnnualBudgetDiagnosis(diagnosis?: UniversalIntakeDiagnosis | null) {
  return intakeKind(diagnosis) === 'ANNUAL_BUDGET'
}

export function intakeDisplayLabel(diagnosis?: UniversalIntakeDiagnosis | null, fallback = 'base analitica') {
  return (
    String(diagnosis?.label || diagnosis?.headline || diagnosis?.canonicalStatus || '').trim() ||
    fallback
  )
}

export function intakeDetail(diagnosis?: UniversalIntakeDiagnosis | null, fallback = '') {
  return String(diagnosis?.detail || diagnosis?.canonicalDetail || '').trim() || fallback
}

export function intakePrimaryActionLabel(diagnosis?: UniversalIntakeDiagnosis | null, fallback = 'Seguir') {
  const explicit = String(diagnosis?.primaryActionLabel || '').trim()
  if (explicit) return explicit

  switch (intakeKind(diagnosis)) {
    case 'ANNUAL_BUDGET':
      return 'Abrir plan anual'
    case 'CASH_TRANSACTIONS':
      return 'Usar modulo Caja'
    case 'TRIBUNAL_PORTFOLIO':
      return 'Abrir Tribunal'
    default:
      return fallback
  }
}

export function intakeRecommendedRoute(diagnosis?: UniversalIntakeDiagnosis | null, fallback = '/universal') {
  const explicit = String(diagnosis?.recommendedRoute || '').trim()
  if (explicit) return explicit

  switch (intakeKind(diagnosis)) {
    case 'ANNUAL_BUDGET':
      return '/budget'
    case 'CASH_TRANSACTIONS':
      return '/imports?mode=transactions'
    case 'TRIBUNAL_PORTFOLIO':
      return '/tribunal'
    default:
      return fallback
  }
}
