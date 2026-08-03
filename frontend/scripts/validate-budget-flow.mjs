import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

function read(relPath) {
  return readFileSync(resolve(process.cwd(), relPath), 'utf8')
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}

function countMatches(text, pattern) {
  const matches = text.match(pattern)
  return matches ? matches.length : 0
}

const annual = read('src/pages/AnnualPlanningPage.tsx')
const dashboard = read('src/pages/BudgetDashboardPage.tsx')
const api = read('src/api.ts')

assert(api.includes("export async function getBudgetAnalysis(companyId: number)"), 'Falta el cliente API de /budget/analysis.')
assert(api.includes("request<BudgetAnalysisBundle>(`/api/companies/${companyId}/budget/analysis`)"), 'La API annual no apunta a /budget/analysis.')

assert(annual.includes('getBudgetAnalysis'), 'AnnualPlanningPage no importa getBudgetAnalysis.')
assert(countMatches(annual, /queryFn:\s*\(\)\s*=>\s*getBudgetAnalysis/g) === 1, 'AnnualPlanningPage debe usar una sola query principal a /budget/analysis.')
assert(!annual.includes('getBudgetWorkflow'), 'AnnualPlanningPage mantiene query legacy getBudgetWorkflow.')
assert(!annual.includes('getBudgetSummary'), 'AnnualPlanningPage mantiene query legacy getBudgetSummary.')
assert(!annual.includes('getBudgetLongInsights'), 'AnnualPlanningPage mantiene query legacy getBudgetLongInsights.')
assert(!annual.includes('listUniversalImports'), 'AnnualPlanningPage mantiene fallback legacy de imports.')
assert(!annual.includes('getUniversalLineage'), 'AnnualPlanningPage mantiene fallback legacy de lineage.')
assert(!annual.includes('location.state'), 'AnnualPlanningPage depende de location.state.')
assert(annual.includes("const sourceSheetIndex = analysis?.sourceSheetIndex"), 'AnnualPlanningPage no rehidrata hoja desde el bundle.')
assert(annual.includes("const sourceHeaderRow = analysis?.sourceHeaderRow"), 'AnnualPlanningPage no rehidrata cabecera desde el bundle.')
assert(annual.includes('EMPTY_DATA_TEXT'), 'AnnualPlanningPage debe mostrar vacíos como Sin datos.')

assert(dashboard.includes('getBudgetAnalysis'), 'BudgetDashboardPage no importa getBudgetAnalysis.')
assert(countMatches(dashboard, /queryFn:\s*\(\)\s*=>\s*getBudgetAnalysis/g) === 1, 'BudgetDashboardPage debe usar una sola query principal a /budget/analysis.')
assert(!dashboard.includes('getBudgetSummary'), 'BudgetDashboardPage mantiene query legacy getBudgetSummary.')
assert(!dashboard.includes('getCashflowSummary'), 'BudgetDashboardPage mantiene query legacy getCashflowSummary.')
assert(!dashboard.includes('getBudgetLongInsights'), 'BudgetDashboardPage mantiene query legacy getBudgetLongInsights.')
assert(dashboard.includes("const summary = (analysis?.summary || undefined)"), 'BudgetDashboardPage no usa summary desde el bundle.')
assert(dashboard.includes("const cashflow = (analysis?.cashflow || undefined)"), 'BudgetDashboardPage no usa cashflow desde el bundle.')
assert(dashboard.includes("const longInsights = (analysis?.insights || undefined)"), 'BudgetDashboardPage no usa insights desde el bundle.')
assert(!dashboard.includes('Neto real 0,00 €'), 'BudgetDashboardPage mantiene copy falso de neto real a cero.')
assert(!dashboard.includes('saldo real 0,00 €'), 'BudgetDashboardPage mantiene copy falso de saldo real a cero.')

console.log('budget-flow frontend checks passed')
