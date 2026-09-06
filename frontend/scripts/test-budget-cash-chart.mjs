import assert from 'node:assert/strict'
import { buildBudgetCashChartSeries } from '../src/utils/budgetCashChart.js'

const months = [
  ['ENERO', 'Enero'],
  ['FEBRERO', 'Febrero'],
  ['MARZO', 'Marzo'],
  ['ABRIL', 'Abril'],
  ['MAYO', 'Mayo'],
  ['JUNIO', 'Junio'],
  ['JULIO', 'Julio'],
  ['AGOSTO', 'Agosto'],
  ['SEPTIEMBRE', 'Septiembre'],
  ['OCTUBRE', 'Octubre'],
  ['NOVIEMBRE', 'Noviembre'],
  ['DICIEMBRE', 'Diciembre']
].map(([monthKey, label]) => ({ monthKey, label }))

const expectedCashNet = [
  13000, 13000, 13000, 13000, 13000, 13000,
  13000, 13000, 13000, 13000, 13000, 13000
]

const expectedClosingBalance = [
  113000, 126000, 139000, 152000, 165000, 178000,
  191000, 204000, 217000, 230000, 243000, 256000
]

const cashMonths = months.map((month, index) => ({
  monthKey: month.monthKey,
  label: month.label,
  net: index === 0 ? 100000 : expectedCashNet[index] + 1,
  derivedNet: expectedCashNet[index],
  endingBalance: expectedClosingBalance[index]
}))

const { labels, cashNet, closingBalance } = buildBudgetCashChartSeries(months, cashMonths)

assert.equal(labels.length, 12, 'Debe devolver 12 labels.')
assert.equal(cashNet.length, 12, 'La serie cashNet debe tener 12 puntos.')
assert.equal(closingBalance.length, 12, 'La serie closingBalance debe tener 12 puntos.')
assert.deepEqual(labels, months.map((month) => month.label), 'Ambas series deben seguir enero-diciembre.')
assert.equal(cashNet[0], 13000, 'Enero cashNet debe ser 13000.')
assert.equal(cashNet[11], 13000, 'Diciembre cashNet debe ser 13000.')
assert.equal(closingBalance[0], 113000, 'Enero closingBalance debe ser 113000.')
assert(!cashNet.includes(100000), 'openingBalance no puede aparecer en la serie cashNet.')
assert.deepEqual(cashNet, expectedCashNet, 'cashNet debe salir del valor canónico por mes.')
assert.deepEqual(closingBalance, expectedClosingBalance, 'closingBalance debe mantener el orden canonico por mes.')

console.log('budget cash chart mapper checks passed')
