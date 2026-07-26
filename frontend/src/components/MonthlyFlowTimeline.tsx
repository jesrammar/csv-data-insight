type FlowTone = 'idle' | 'progress' | 'success' | 'warning' | 'danger'

export type MonthlyFlowItem = {
  period: string
  importStatus?: string | null
  hasReading: boolean
  hasReport: boolean
  alertsCount?: number
  portfolioStep?: {
    applicable?: boolean
    status?: string | null
    title?: string
    detail?: string
    shortLabel?: string | null
  } | null
}

function normalizeImportTone(status?: string | null): FlowTone {
  const value = String(status || '').toUpperCase()
  if (!value) return 'idle'
  if (value === 'OK') return 'success'
  if (value === 'WARNING') return 'warning'
  if (value === 'ERROR' || value === 'DEAD' || value === 'BLOCKED') return 'danger'
  if (value === 'RUNNING' || value === 'PENDING' || value === 'RETRY') return 'progress'
  return 'idle'
}

function normalizePortfolioTone(item: MonthlyFlowItem): FlowTone {
  if (!item.portfolioStep?.applicable) return 'success'
  const status = String(item.portfolioStep.status || '').toUpperCase()
  if (status === 'PENDING' || status === 'STALE') return 'warning'
  if (status === 'LOADED') return 'success'
  return 'idle'
}

function overallTone(item: MonthlyFlowItem): FlowTone {
  const importTone = normalizeImportTone(item.importStatus)
  const portfolioTone = normalizePortfolioTone(item)
  if (importTone === 'danger') return 'danger'
  if (item.hasReport && portfolioTone === 'success') return 'success'
  if (portfolioTone === 'warning') return 'warning'
  if (importTone === 'warning' || (item.alertsCount || 0) > 0) return 'warning'
  if (item.hasReading || importTone === 'progress' || importTone === 'success') return 'progress'
  return 'idle'
}

function overallLabel(item: MonthlyFlowItem) {
  const tone = overallTone(item)
  if (tone === 'danger') return 'Atascado'
  if (tone === 'success') return 'Listo'
  if (tone === 'warning') return 'Revisar'
  if (tone === 'progress') return 'En curso'
  return 'Pendiente'
}

function stepTone(item: MonthlyFlowItem, step: 'import' | 'reading' | 'report'): FlowTone {
  if (step === 'import') return normalizeImportTone(item.importStatus)
  if (step === 'reading') {
    if (item.hasReading) return 'success'
    const importTone = normalizeImportTone(item.importStatus)
    if (importTone === 'danger') return 'danger'
    if (importTone === 'success' || importTone === 'warning' || importTone === 'progress') return 'progress'
    return 'idle'
  }
  if (item.hasReport) return 'success'
  if (item.hasReading) return 'progress'
  if (normalizeImportTone(item.importStatus) === 'danger') return 'danger'
  return 'idle'
}

export default function MonthlyFlowTimeline({ items }: { items: MonthlyFlowItem[] }) {
  if (!items.length) return null

  return (
    <div className="month-flow">
      {items.map((item) => {
        const tone = overallTone(item)
        return (
          <div key={item.period} className={`month-flow-card month-flow-card-${tone}`}>
            <div className="mini-row row-baseline">
              <div className="fw-800">{item.period}</div>
              <span className={`badge month-flow-badge month-flow-badge-${tone}`}>{overallLabel(item)}</span>
              {item.portfolioStep?.applicable ? ` · Cartera ${item.portfolioStep.shortLabel || String(item.portfolioStep.status || '').toLowerCase()}` : ''}
            </div>

            <div className="month-flow-steps">
              <div className={`month-flow-step month-flow-step-${stepTone(item, 'import')}`}>
                <span className="month-flow-dot" />
                <span>Datos</span>
              </div>
              <div className={`month-flow-step month-flow-step-${stepTone(item, 'reading')}`}>
                <span className="month-flow-dot" />
                <span>Lectura</span>
              </div>
              <div className={`month-flow-step month-flow-step-${stepTone(item, 'report')}`}>
                <span className="month-flow-dot" />
                <span>PDF</span>
              </div>
            </div>

            <div className="upload-hint mt-8">
              Import: {item.importStatus || 'Sin carga'}
              {(item.alertsCount || 0) > 0 ? ` · ${item.alertsCount} alerta${item.alertsCount === 1 ? '' : 's'}` : ''}
            </div>
          </div>
        )
      })}
    </div>
  )
}
