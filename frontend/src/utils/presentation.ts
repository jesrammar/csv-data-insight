import type { ImportJob, PipelineFileDto, PortfolioWorkflowStepDto } from '../api'

export function workflowBadgeTone(status?: string | null) {
  const value = String(status || '').toUpperCase()
  if (value === 'CLOSED' || value === 'REPORT_READY') return 'ok'
  if (value === 'EXCEPTIONS') return 'err'
  if (value === 'INGESTING' || value === 'REPORT_GENERATING' || value === 'REVIEWED' || value === 'READY_FOR_REVIEW') return 'warn'
  return ''
}

export function importStatusBadgeTone(status?: ImportJob['status'] | string | null) {
  const value = String(status || '').toUpperCase()
  if (value === 'OK' || value === 'SUCCESS') return 'ok'
  if (value === 'WARNING' || value === 'RETRY' || value === 'SKIPPED') return 'warn'
  if (value === 'ERROR' || value === 'DEAD' || value === 'BLOCKED' || value === 'FAILED') return 'err'
  return ''
}

export function portfolioStepBadgeTone(step?: PortfolioWorkflowStepDto | null) {
  if (step?.badgeTone) return step.badgeTone
  const status = String(step?.status || '').toUpperCase()
  if (status === 'LOADED' || status === 'NOT_APPLICABLE') return 'ok'
  if (status === 'STALE') return 'warn'
  if (status === 'PENDING') return 'err'
  return ''
}

export function portfolioStepActionLabel(step?: PortfolioWorkflowStepDto | null) {
  if (step?.actionLabel) return step.actionLabel
  const status = String(step?.status || '').toUpperCase()
  if (status === 'PENDING') return 'Cargar cartera'
  if (status === 'STALE') return 'Actualizar cartera'
  if (status === 'LOADED') return 'Abrir Tribunal'
  return 'Opcional'
}

export function followUpBadgeTone(status?: string | null) {
  const normalized = String(status || '').toUpperCase()
  if (normalized === 'RESOLVED') return 'ok'
  if (normalized === 'IN_PROGRESS') return 'warn'
  return 'err'
}

export function followUpStatusLabel(status?: string | null) {
  const normalized = String(status || '').toUpperCase()
  if (normalized === 'RESOLVED') return 'Resuelta'
  if (normalized === 'IN_PROGRESS') return 'En curso'
  return 'Pendiente'
}

export function pipelineKindLabel(kind?: string | null) {
  const value = String(kind || '').toUpperCase()
  if (value === 'TRANSACTIONS') return 'Caja'
  if (value === 'TRIBUNAL') return 'Tribunal'
  if (value === 'UNIVERSAL') return 'Universal'
  return 'Sin clasificar'
}

export function pipelineModuleLabel(row: Pick<PipelineFileDto, 'detectedKind' | 'actionLabel'>) {
  if (row.actionLabel && ['Abrir Caja', 'Abrir Tribunal', 'Abrir Universal', 'Abrir modulo'].includes(String(row.actionLabel))) {
    return String(row.actionLabel)
  }
  const kind = String(row.detectedKind || '').toUpperCase()
  if (kind === 'TRIBUNAL') return 'Abrir Tribunal'
  if (kind === 'UNIVERSAL') return 'Abrir Universal'
  return 'Abrir Caja'
}
