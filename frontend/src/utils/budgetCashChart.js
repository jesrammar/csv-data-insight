export function buildBudgetCashChartSeries(months, cashMonths) {
  const cashByKey = new Map((cashMonths || []).map((month) => [month.monthKey, month]))
  const labels = (months || []).map((month) => month.label)
  const cashNet = (months || []).map((month) => {
    const cashMonth = cashByKey.get(month.monthKey)
    return Number(cashMonth?.derivedNet ?? cashMonth?.net ?? 0)
  })
  const closingBalance = (months || []).map((month) => Number(cashByKey.get(month.monthKey)?.endingBalance ?? 0))
  return { labels, cashNet, closingBalance }
}
