export type BudgetChartMonthLike = {
  monthKey: string
  label: string
}

export type BudgetCashMonthLike = {
  monthKey: string
  derivedNet?: number | null
  net?: number | null
  endingBalance?: number | null
}

export function buildBudgetCashChartSeries(
  months: BudgetChartMonthLike[],
  cashMonths: BudgetCashMonthLike[]
): {
  labels: string[]
  cashNet: number[]
  closingBalance: number[]
}
