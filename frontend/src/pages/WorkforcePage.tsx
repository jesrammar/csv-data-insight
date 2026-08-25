// @ts-nocheck
import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  downloadWorkforceReportPdf,
  getWorkforceLaborCostComparison,
  getWorkforceLaborCostsStatus,
  getWorkforceStatus,
  getWorkforceSummary,
  uploadWorkforceImport,
  uploadWorkforceLaborCostsImport,
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import EChart from '../components/charts/EChart'
import { EMPTY_DATA_TEXT, EMPTY_VALUE, formatDateTime, formatText } from '../utils/format'
import { useToast } from '../components/ui/ToastProvider'

const NUMBER_FORMAT = new Intl.NumberFormat('es-ES', { maximumFractionDigits: 2 })
const INTEGER_FORMAT = new Intl.NumberFormat('es-ES', { maximumFractionDigits: 0 })
const CURRENCY_FORMAT = new Intl.NumberFormat('es-ES', {
  style: 'currency',
  currency: 'EUR',
  maximumFractionDigits: 2,
})
const COMPACT_CURRENCY_FORMAT = new Intl.NumberFormat('es-ES', {
  style: 'currency',
  currency: 'EUR',
  maximumFractionDigits: 0,
})

const MONTH_KEYS = ['ENERO', 'FEBRERO', 'MARZO', 'ABRIL', 'MAYO', 'JUNIO', 'JULIO', 'AGOSTO', 'SEPTIEMBRE', 'OCTUBRE', 'NOVIEMBRE', 'DICIEMBRE']
const MONTH_OPTIONS = MONTH_KEYS.map((month, index) => ({
  value: String(index + 1).padStart(2, '0'),
  label: month.charAt(0) + month.slice(1).toLowerCase(),
}))
const CHART_TOOLTIP_THEME = {
  backgroundColor: 'rgba(6, 12, 24, 0.96)',
  borderColor: 'rgba(96, 165, 250, 0.2)',
  borderWidth: 1,
  padding: [12, 14],
  textStyle: { color: 'rgba(241, 245, 249, 0.94)', fontSize: 12, lineHeight: 18 },
  extraCssText: 'box-shadow: 0 18px 40px rgba(2, 8, 20, 0.46); border-radius: 14px;',
}
const CHART_SPLIT_LINE = {
  show: true,
  lineStyle: {
    color: 'rgba(148, 163, 184, 0.12)',
    type: 'dashed',
  },
}

function formatNumber(value: unknown, fallback = EMPTY_VALUE) {
  if (value == null || value === '') return fallback
  const numeric = Number(value)
  return Number.isFinite(numeric) ? NUMBER_FORMAT.format(numeric) : fallback
}

function formatInteger(value: unknown, fallback = EMPTY_VALUE) {
  if (value == null || value === '') return fallback
  const numeric = Number(value)
  return Number.isFinite(numeric) ? INTEGER_FORMAT.format(numeric) : fallback
}

function formatCurrency(value: unknown, fallback = EMPTY_VALUE) {
  if (value == null || value === '') return fallback
  const numeric = Number(value)
  return Number.isFinite(numeric) ? CURRENCY_FORMAT.format(numeric) : fallback
}

function formatCurrencyByCode(value: unknown, currencyCode?: string | null, fallback = EMPTY_VALUE) {
  if (value == null || value === '') return fallback
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return fallback
  if (!currencyCode) return NUMBER_FORMAT.format(numeric)
  try {
    return new Intl.NumberFormat('es-ES', {
      style: 'currency',
      currency: currencyCode,
      maximumFractionDigits: 2,
    }).format(numeric)
  } catch {
    return `${NUMBER_FORMAT.format(numeric)} ${currencyCode}`
  }
}

function formatPercent(value: unknown, fallback = EMPTY_VALUE) {
  if (value == null || value === '') return fallback
  const numeric = Number(value)
  return Number.isFinite(numeric) ? `${NUMBER_FORMAT.format(numeric)} %` : fallback
}

function toNumeric(value: unknown) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : null
}

function buildReferencePeriod(month?: string | null, year?: string | null) {
  if (month && year) return `${year}-${month}`
  if (year) return year
  return null
}

function parseReferencePeriod(referencePeriod?: string | null) {
  if (!referencePeriod || referencePeriod === 'UNKNOWN') return { year: '', month: '' }
  const match = String(referencePeriod).match(/^(\d{4})-(\d{2})$/)
  if (match) return { year: match[1], month: match[2] }
  const yearOnlyMatch = String(referencePeriod).match(/^(\d{4})$/)
  return yearOnlyMatch ? { year: yearOnlyMatch[1], month: '' } : { year: '', month: '' }
}

function capitalizeLabel(value?: string | null, fallback = EMPTY_VALUE) {
  const text = String(value || '').trim()
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : fallback
}

function formatPeriodLabel(month?: string | null, year?: string | null, fallback = EMPTY_VALUE) {
  if (month && year) {
    const monthOption = MONTH_OPTIONS.find((option) => option.value === month)
    return monthOption ? `${monthOption.label} ${year}` : `${month}/${year}`
  }
  return year || fallback
}

function formatComparisonDeltaPercent(value: unknown, state?: string | null) {
  if (state === 'FROM_ZERO') return 'Desde 0'
  if (state === 'ZERO_TO_ZERO') return '0 %'
  return formatPercent(value)
}

function formatImportStatusLabel(status?: string | null) {
  const normalized = String(status || '').trim().toUpperCase()
  if (!normalized) return 'Sin estado'
  if (normalized === 'ACTIVE') return 'Activo'
  if (normalized === 'SUPERSEDED') return 'Histórico'
  if (normalized === 'FAILED') return 'Error'
  if (normalized === 'REVIEW') return 'Revisión'
  if (normalized === 'PENDING') return 'Pendiente'
  return normalized.replace(/_/g, ' ')
}

function importStatusTone(status?: string | null) {
  const normalized = String(status || '').trim().toUpperCase()
  if (normalized === 'ACTIVE') return 'is-success'
  if (normalized === 'FAILED') return 'is-danger'
  if (normalized === 'REVIEW' || normalized === 'PENDING') return 'is-warning'
  return 'is-muted'
}

function buildYearOptions(candidates: unknown[]) {
  const currentYear = new Date().getFullYear()
  const years = new Set<number>()
  for (let year = currentYear - 3; year <= currentYear + 3; year += 1) years.add(year)

  for (const candidate of candidates) {
    if (candidate == null || candidate === '') continue
    const numeric = Number(candidate)
    if (!Number.isFinite(numeric)) continue
    for (let year = numeric - 1; year <= numeric + 1; year += 1) years.add(year)
  }

  return Array.from(years)
    .sort((a, b) => a - b)
    .map(String)
}

function pluckNumeric(rows: any[], key: string) {
  return rows.map((row) => toNumeric(row?.[key])).filter((value) => value != null)
}

function summarizeNumeric(values: number[]) {
  if (!values.length) return null
  return {
    min: Math.min(...values),
    avg: values.reduce((sum, value) => sum + value, 0) / values.length,
    max: Math.max(...values),
  }
}

function formatMetricValue(value: number | null | undefined, kind: 'number' | 'currency' | 'integer' = 'number') {
  if (kind === 'currency') return formatCurrency(value)
  if (kind === 'integer') return formatInteger(value)
  return formatNumber(value)
}

function formatAxisValue(value: number, kind: 'number' | 'currency' | 'integer' = 'number') {
  if (kind === 'currency') return COMPACT_CURRENCY_FORMAT.format(value)
  if (kind === 'integer') return INTEGER_FORMAT.format(value)
  return NUMBER_FORMAT.format(value)
}

function normalizeGestorName(value: unknown) {
  const text = String(value ?? '').trim()
  if (!text) return ''
  return text
    .normalize('NFD')
    .replace(/[\u0300-\u036f]+/g, '')
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '')
    .trim()
}

function firstNonEmptyText(...values: unknown[]) {
  for (const value of values) {
    const text = String(value ?? '').trim()
    if (text) return text
  }
  return ''
}

function sumNullableValues(values: unknown[]) {
  const numericValues = values.map(toNumeric).filter((value) => value != null)
  if (!numericValues.length) return null
  return numericValues.reduce((sum, value) => sum + value, 0)
}

function mergeMonthlyCostMap(base: Record<string, number> | null | undefined, extra: Record<string, number> | null | undefined) {
  const merged = { ...(base || {}) }
  for (const [month, rawValue] of Object.entries(extra || {})) {
    const numeric = toNumeric(rawValue)
    if (numeric == null) continue
    const current = toNumeric(merged[month])
    merged[month] = current == null ? numeric : current + numeric
  }
  return merged
}

function findGestorBySelection(rows: any[], selectedGestor?: string | null) {
  if (!rows.length) return null
  if (!selectedGestor) return rows[0] || null
  const selectedKey = normalizeGestorName(selectedGestor)
  return rows.find((row) => row?.gestor === selectedGestor) || rows.find((row) => normalizeGestorName(row?.gestor) === selectedKey) || null
}

function aggregateEconomicGestores(costRows: any[], operationalRows: any[]) {
  const operationalByGestor = new Map<string, any>()
  ;(operationalRows || []).forEach((row) => {
    const key = normalizeGestorName(row?.gestor)
    if (!key || operationalByGestor.has(key)) return
    operationalByGestor.set(key, row)
  })

  const grouped = new Map<string, any>()
  ;(costRows || []).forEach((row) => {
    const key = normalizeGestorName(row?.gestor)
    if (!key) return
    const operational = operationalByGestor.get(key) || null
    const current = grouped.get(key) || {
      gestor: firstNonEmptyText(operational?.gestor, row?.gestor, key),
      normalizedGestor: key,
      costePersonalMensual: {},
      ssEmpresaMensual: {},
      costeTotalMensual: {},
      costePersonalAnual: null,
      ssEmpresaAnual: null,
      costeLaboralAnual: null,
    }

    current.gestor = firstNonEmptyText(operational?.gestor, current.gestor, row?.gestor, key)
    current.costePersonalMensual = mergeMonthlyCostMap(current.costePersonalMensual, row?.costePersonalMensual)
    current.ssEmpresaMensual = mergeMonthlyCostMap(current.ssEmpresaMensual, row?.ssEmpresaMensual)
    current.costeTotalMensual = mergeMonthlyCostMap(current.costeTotalMensual, row?.costeTotalMensual)
    current.costePersonalAnual = sumNullableValues([current.costePersonalAnual, row?.costePersonalAnual])
    current.ssEmpresaAnual = sumNullableValues([current.ssEmpresaAnual, row?.ssEmpresaAnual])
    current.costeLaboralAnual = sumNullableValues([current.costeLaboralAnual, row?.costeLaboralAnual])
    grouped.set(key, current)
  })

  return Array.from(grouped.values())
    .map((row) => {
      const operational = operationalByGestor.get(row.normalizedGestor) || {}
      return {
        ...operational,
        ...row,
        gestor: firstNonEmptyText(operational?.gestor, row.gestor, row.normalizedGestor),
      }
    })
    .sort((left, right) => {
      const costDelta = Number(right?.costeLaboralAnual || 0) - Number(left?.costeLaboralAnual || 0)
      if (costDelta !== 0) return costDelta
      return String(left?.gestor || '').localeCompare(String(right?.gestor || ''), 'es')
    })
}

function aggregateEconomicHistoryGestores(historyRows: any[], operationalRows: any[]) {
  const operationalByGestor = new Map<string, any>()
  ;(operationalRows || []).forEach((row) => {
    const key = normalizeGestorName(row?.gestor)
    if (!key || operationalByGestor.has(key)) return
    operationalByGestor.set(key, row)
  })

  const grouped = new Map<string, any>()
  ;(historyRows || []).forEach((row) => {
    const key = normalizeGestorName(row?.gestor)
    if (!key) return
    const operational = operationalByGestor.get(key) || null
    const current = grouped.get(key) || {
      gestor: firstNonEmptyText(operational?.gestor, row?.gestor, key),
      normalizedGestor: key,
      pointsByKey: new Map<string, any>(),
    }

    current.gestor = firstNonEmptyText(operational?.gestor, current.gestor, row?.gestor, key)
    ;(row?.points || []).forEach((point: any) => {
      const pointKey = historyPointKey(point)
      const previous = current.pointsByKey.get(pointKey)
      current.pointsByKey.set(pointKey, {
        referencePeriod: point?.referencePeriod ?? previous?.referencePeriod ?? null,
        referenceLabel: point?.referenceLabel ?? previous?.referenceLabel ?? null,
        createdAt:
          String(previous?.createdAt || '') > String(point?.createdAt || '')
            ? previous?.createdAt
            : point?.createdAt ?? previous?.createdAt ?? '',
        costePersonalAnual: sumNullableValues([previous?.costePersonalAnual, point?.costePersonalAnual]),
        ssEmpresaAnual: sumNullableValues([previous?.ssEmpresaAnual, point?.ssEmpresaAnual]),
        costeLaboralAnual: sumNullableValues([previous?.costeLaboralAnual, point?.costeLaboralAnual]),
      })
    })

    grouped.set(key, current)
  })

  return Array.from(grouped.values())
    .map((row) => ({
      gestor: row.gestor,
      normalizedGestor: row.normalizedGestor,
      points: Array.from(row.pointsByKey.values()).sort((left: any, right: any) => {
        const referenceOrder = compareReferencePeriods(left?.referencePeriod || '', right?.referencePeriod || '')
        if (referenceOrder !== 0) return referenceOrder
        return String(left?.createdAt || '').localeCompare(String(right?.createdAt || ''))
      }),
    }))
    .sort((left, right) => {
      const leftLatest = left?.points?.[left.points.length - 1]?.costeLaboralAnual
      const rightLatest = right?.points?.[right.points.length - 1]?.costeLaboralAnual
      const costDelta = Number(rightLatest || 0) - Number(leftLatest || 0)
      if (costDelta !== 0) return costDelta
      return String(left?.gestor || '').localeCompare(String(right?.gestor || ''), 'es')
    })
}

function serviceEntries(row: any) {
  return [
    { label: 'C/M', value: formatInteger(row?.contModelosOk, '0') },
    { label: 'IS', value: formatInteger(row?.isIrpfOk, '0') },
    { label: 'DDCC', value: formatInteger(row?.ddccOk, '0') },
    { label: 'Lib', value: formatInteger(row?.librosOk, '0') },
  ]
}

function gestorLabels(rows: any[]) {
  return rows.map((row) => formatText(row?.gestor))
}

function buildBarOption(rows: any[], key: string, kind: 'number' | 'currency' | 'integer' = 'number') {
  const labels = gestorLabels(rows)
  const data = rows.map((row) => toNumeric(row?.[key]))
  return {
    grid: { left: 22, right: 18, top: 24, bottom: 62, containLabel: true },
    tooltip: {
      ...CHART_TOOLTIP_THEME,
      trigger: 'axis',
      axisPointer: {
        type: 'shadow',
        shadowStyle: { color: 'rgba(148, 163, 184, 0.12)' },
      },
      formatter: (params: any) => {
        const point = Array.isArray(params) ? params[0] : params
        const label = String(point?.axisValue ?? '')
        const rawValue = Array.isArray(point?.data) ? point?.data?.[1] : point?.data
        return `${label}\n${formatMetricValue(rawValue, kind)}`
      },
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: { interval: 0, rotate: labels.length > 4 ? 24 : 0, fontSize: 12, margin: 14, color: 'rgba(203, 213, 225, 0.82)' },
      axisTick: { show: false },
      axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.16)' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 12, color: 'rgba(203, 213, 225, 0.74)', formatter: (value: number) => formatAxisValue(value, kind) },
      splitLine: CHART_SPLIT_LINE,
    },
    series: [
      {
        type: 'bar',
        data,
        barMaxWidth: 34,
        itemStyle: {
          borderRadius: [12, 12, 10, 10],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(96, 165, 250, 0.96)' },
              { offset: 1, color: 'rgba(34, 211, 238, 0.54)' },
            ],
          },
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 24,
            shadowColor: 'rgba(56, 189, 248, 0.28)',
          },
        },
      },
    ],
  }
}

function buildMonthlyCostOption(points: any[]) {
  return {
    grid: { left: 26, right: 20, top: 56, bottom: 44, containLabel: true },
    legend: {
      show: true,
      top: 6,
      left: 'center',
      itemGap: 18,
      textStyle: { color: 'rgba(226, 232, 240, 0.82)', fontSize: 12 },
    },
    tooltip: {
      ...CHART_TOOLTIP_THEME,
      trigger: 'axis',
      formatter: (params: any) => {
        const series = Array.isArray(params) ? params : [params]
        const axisLabel = String(series[0]?.axisValue ?? '')
        const rows = series.map((point: any) => `${point.seriesName}: ${formatCurrency(point.data)}`).join('\n')
        return `${axisLabel}\n${rows}`
      },
    },
    xAxis: {
      type: 'category',
      data: points.map((point) => point.month),
      axisLabel: { fontSize: 12, margin: 12, color: 'rgba(203, 213, 225, 0.82)' },
      axisTick: { show: false },
      axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.16)' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 11, margin: 14, color: 'rgba(203, 213, 225, 0.74)', formatter: (value: number) => COMPACT_CURRENCY_FORMAT.format(value) },
      splitLine: CHART_SPLIT_LINE,
    },
    series: [
      {
        name: 'Coste personal',
        type: 'bar',
        data: points.map((point) => point.costePersonal),
        barMaxWidth: 30,
        itemStyle: {
          borderRadius: [12, 12, 8, 8],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(56, 189, 248, 0.94)' },
              { offset: 1, color: 'rgba(99, 102, 241, 0.48)' },
            ],
          },
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 24,
            shadowColor: 'rgba(96, 165, 250, 0.24)',
          },
        },
      },
      {
        name: 'SS empresa',
        type: 'line',
        data: points.map((point) => point.ssEmpresa),
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 3, color: 'rgba(45, 212, 191, 0.96)' },
        itemStyle: { color: 'rgba(45, 212, 191, 0.96)' },
        areaStyle: { opacity: 0.08, color: 'rgba(45, 212, 191, 0.32)' },
      },
    ],
  }
}

function buildExecutiveReadings(gestores: any[], hasCosts: boolean) {
  if (!gestores.length) return []
  const topClients = [...gestores].sort((a, b) => (b.totalClients || 0) - (a.totalClients || 0))[0]
  const topMinutes = [...gestores].sort((a, b) => Number(b.totalMinutas || 0) - Number(a.totalMinutas || 0))[0]
  const topCarga = gestores
    .filter((row) => row.cargaMedia != null)
    .sort((a, b) => Number(b.cargaMedia || 0) - Number(a.cargaMedia || 0))[0]
  const topSeats = gestores
    .filter((row) => row.totalVolumenAsientos != null)
    .sort((a, b) => Number(b.totalVolumenAsientos || 0) - Number(a.totalVolumenAsientos || 0))[0]
  const topCost = hasCosts
    ? gestores
        .filter((row) => row.costeLaboralAnual != null)
        .sort((a, b) => Number(b.costeLaboralAnual || 0) - Number(a.costeLaboralAnual || 0))[0]
    : null

  return [
    topClients ? `${formatText(topClients.gestor)} concentra ${formatInteger(topClients.totalClients)} clientes en cartera.` : null,
    topMinutes ? `${formatText(topMinutes.gestor)} lidera el bloque de minutos con ${formatNumber(topMinutes.totalMinutas)}.` : null,
    topCarga ? `${formatText(topCarga.gestor)} presenta la carga media más alta con ${formatNumber(topCarga.cargaMedia)}.` : null,
    topSeats ? `${formatText(topSeats.gestor)} mueve ${formatNumber(topSeats.totalVolumenAsientos)} asientos de media acumulada.` : null,
    topCost ? `${formatText(topCost.gestor)} agrupa el mayor coste laboral del periodo con ${formatCurrency(topCost.costeLaboralAnual)}.` : null,
  ].filter(Boolean)
}

function buildCostVsActivityReadings(gestores: any[], hasCosts: boolean) {
  if (!hasCosts) return []
  const topCost = [...gestores]
    .filter((row) => row.costeLaboralAnual != null)
    .sort((a, b) => Number(b.costeLaboralAnual || 0) - Number(a.costeLaboralAnual || 0))[0]
  const topCostPerClient = [...gestores]
    .filter((row) => row.costePorCliente != null)
    .sort((a, b) => Number(b.costePorCliente || 0) - Number(a.costePorCliente || 0))[0]
  const lowestCostPerSeats = [...gestores]
    .filter((row) => row.costePor1000Asientos != null)
    .sort((a, b) => Number(a.costePor1000Asientos || 0) - Number(b.costePor1000Asientos || 0))[0]
  const topMinutesPerCost = [...gestores]
    .filter((row) => row.minutasPor1000Coste != null)
    .sort((a, b) => Number(b.minutasPor1000Coste || 0) - Number(a.minutasPor1000Coste || 0))[0]

  return [
    topCost ? `${formatText(topCost.gestor)} concentra el mayor coste total: ${formatCurrency(topCost.costeLaboralAnual)}.` : null,
    topCostPerClient ? `${formatText(topCostPerClient.gestor)} presenta el mayor coste por cliente: ${formatCurrency(topCostPerClient.costePorCliente)}.` : null,
    lowestCostPerSeats
      ? `${formatText(lowestCostPerSeats.gestor)} muestra el menor coste por 1.000 asientos: ${formatCurrency(lowestCostPerSeats.costePor1000Asientos)}.`
      : null,
    topMinutesPerCost
      ? `${formatText(topMinutesPerCost.gestor)} concentra más minutos por 1.000 EUR: ${formatNumber(topMinutesPerCost.minutasPor1000Coste)}.`
      : null,
  ].filter(Boolean)
}

function historyPointKey(point: any) {
  const referencePeriod = String(point?.referencePeriod || '').trim()
  if (referencePeriod && referencePeriod !== 'UNKNOWN') {
    return referencePeriod
  }
  return `${point?.createdAt || 'NO_DATE'}|${point?.importId || 'NO_IMPORT'}`
}

function hasKnownReferencePeriod(point: any) {
  const referencePeriod = String(point?.referencePeriod || '').trim()
  return !!referencePeriod && referencePeriod !== 'UNKNOWN'
}

function compareReferencePeriods(left: string, right: string) {
  return String(left).localeCompare(String(right))
}

function buildComparablePeriodHistory(points: any[]) {
  const knownPoints = (points || []).filter(hasKnownReferencePeriod)
  if (!knownPoints.length) return []

  const latestByPeriod = new Map<string, any>()
  knownPoints.forEach((point) => {
    latestByPeriod.set(String(point.referencePeriod).trim(), point)
  })

  return Array.from(latestByPeriod.values()).sort((left, right) => {
    const referenceOrder = compareReferencePeriods(left?.referencePeriod || '', right?.referencePeriod || '')
    if (referenceOrder !== 0) return referenceOrder
    return String(left?.createdAt || '').localeCompare(String(right?.createdAt || ''))
  })
}

function buildComparisonWindowLabel(points: any[]) {
  if (!points || points.length < 2) return null
  const previous = capitalizeLabel(points[points.length - 2]?.referenceLabel, points[points.length - 2]?.referencePeriod || 'Periodo anterior')
  const current = capitalizeLabel(points[points.length - 1]?.referenceLabel, points[points.length - 1]?.referencePeriod || 'Periodo actual')
  return `${previous} → ${current}`
}

function buildSelectedComparisonPoints(points: any[], basePeriod?: string | null, comparisonPeriod?: string | null) {
  if (!basePeriod || !comparisonPeriod || basePeriod === comparisonPeriod) return []
  const byPeriod = new Map((points || []).map((point: any) => [String(point?.referencePeriod || '').trim(), point]))
  const orderedPeriods = [basePeriod, comparisonPeriod].sort(compareReferencePeriods)
  return orderedPeriods.map((period) => byPeriod.get(period)).filter(Boolean)
}

function buildTimelineLabels(points: any[]) {
  const baseLabels = points.map((point) => capitalizeLabel(point?.referenceLabel, formatDateTime(point?.createdAt)))
  const counts = new Map<string, number>()
  baseLabels.forEach((label) => counts.set(label, (counts.get(label) || 0) + 1))
  return points.map((point, index) => {
    const baseLabel = baseLabels[index]
    return (counts.get(baseLabel) || 0) > 1 && point?.createdAt ? `${baseLabel} · ${formatDateTime(point.createdAt)}` : baseLabel
  })
}

function buildHistoryOption(points: any[], series: Array<{ key: string; name: string; kind?: 'line' | 'bar'; color?: string }>) {
  const labels = buildTimelineLabels(points)
  return {
    grid: { left: 22, right: 18, top: 30, bottom: 54, containLabel: true },
    legend: { top: 0, textStyle: { color: 'rgba(226, 232, 240, 0.82)', fontSize: 12 } },
    tooltip: {
      ...CHART_TOOLTIP_THEME,
      trigger: 'axis',
      formatter: (params: any) => {
        const rows = Array.isArray(params) ? params : [params]
        const axisLabel = String(rows[0]?.axisValue ?? '')
        const metrics = rows.map((point: any) => `${point.seriesName}: ${formatNumber(point.data)}`).join('\n')
        return `${axisLabel}\n${metrics}`
      },
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: { fontSize: 11, rotate: labels.length > 4 ? 20 : 0, color: 'rgba(203, 213, 225, 0.78)' },
      axisTick: { show: false },
      axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.16)' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 12, color: 'rgba(203, 213, 225, 0.74)', formatter: (value: number) => NUMBER_FORMAT.format(value) },
      splitLine: CHART_SPLIT_LINE,
    },
    series: series.map((serie, index) => ({
      name: serie.name,
      type: serie.kind || 'line',
      smooth: serie.kind !== 'bar',
      showSymbol: false,
      data: points.map((point) => toNumeric(point?.[serie.key])),
      barMaxWidth: 26,
      itemStyle: serie.kind === 'bar' ? { borderRadius: [10, 10, 8, 8], color: serie.color } : serie.color ? { color: serie.color } : undefined,
      lineStyle: serie.color ? { color: serie.color, width: 3 } : undefined,
      areaStyle: serie.kind === 'line' ? { opacity: 0.06 } : undefined,
      z: series.length - index,
    })),
  }
}

function buildGestorHistoryOption(points: any[]) {
  const labels = buildTimelineLabels(points)
  return {
    grid: { left: 22, right: 18, top: 30, bottom: 54, containLabel: true },
    legend: { top: 0, textStyle: { color: 'rgba(226, 232, 240, 0.82)', fontSize: 12 } },
    tooltip: {
      ...CHART_TOOLTIP_THEME,
      trigger: 'axis',
      formatter: (params: any) => {
        const rows = Array.isArray(params) ? params : [params]
        const axisLabel = String(rows[0]?.axisValue ?? '')
        const metrics = rows.map((point: any) => `${point.seriesName}: ${formatNumber(point.data)}`).join('\n')
        return `${axisLabel}\n${metrics}`
      },
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: { fontSize: 11, rotate: labels.length > 4 ? 20 : 0, color: 'rgba(203, 213, 225, 0.78)' },
      axisTick: { show: false },
      axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.16)' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 12, color: 'rgba(203, 213, 225, 0.74)', formatter: (value: number) => NUMBER_FORMAT.format(value) },
      splitLine: CHART_SPLIT_LINE,
    },
    series: [
      {
        name: 'Minutos',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: points.map((point) => point.totalMinutas),
        lineStyle: { width: 3, color: 'rgba(96, 165, 250, 0.94)' },
        itemStyle: { color: 'rgba(96, 165, 250, 0.94)' },
        areaStyle: { opacity: 0.07 },
      },
      {
        name: 'Carga media',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: points.map((point) => point.cargaMedia),
        lineStyle: { width: 3, color: 'rgba(45, 212, 191, 0.94)' },
        itemStyle: { color: 'rgba(45, 212, 191, 0.94)' },
        areaStyle: { opacity: 0.04 },
      },
      {
        name: 'Asientos',
        type: 'bar',
        data: points.map((point) => point.totalVolumenAsientos),
        barMaxWidth: 22,
        itemStyle: {
          borderRadius: [10, 10, 8, 8],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(168, 85, 247, 0.88)' },
              { offset: 1, color: 'rgba(168, 85, 247, 0.22)' },
            ],
          },
        },
      },
    ],
  }
}

function buildLaborCostHistoryOption(points: any[]) {
  const labels = buildTimelineLabels(points)
  return {
    grid: { left: 26, right: 20, top: 34, bottom: 52, containLabel: true },
    legend: { top: 0, textStyle: { color: 'rgba(226, 232, 240, 0.82)', fontSize: 12 } },
    tooltip: {
      ...CHART_TOOLTIP_THEME,
      trigger: 'axis',
      formatter: (params: any) => {
        const rows = Array.isArray(params) ? params : [params]
        const axisLabel = String(rows[0]?.axisValue ?? '')
        const metrics = rows.map((point: any) => `${point.seriesName}: ${formatCurrency(point.data)}`).join('\n')
        return `${axisLabel}\n${metrics}`
      },
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: { fontSize: 11, rotate: labels.length > 4 ? 20 : 0, color: 'rgba(203, 213, 225, 0.78)' },
      axisTick: { show: false },
      axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.16)' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 12, color: 'rgba(203, 213, 225, 0.74)', formatter: (value: number) => COMPACT_CURRENCY_FORMAT.format(value) },
      splitLine: CHART_SPLIT_LINE,
    },
    series: [
      {
        name: 'Coste personal',
        type: 'bar',
        data: points.map((point) => toNumeric(point?.totalCostePersonalAnual)),
        barMaxWidth: 26,
        itemStyle: {
          borderRadius: [10, 10, 8, 8],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(56, 189, 248, 0.94)' },
              { offset: 1, color: 'rgba(99, 102, 241, 0.46)' },
            ],
          },
        },
      },
      {
        name: 'SS empresa',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: points.map((point) => toNumeric(point?.totalSsEmpresaAnual)),
        lineStyle: { width: 3, color: 'rgba(45, 212, 191, 0.96)' },
        itemStyle: { color: 'rgba(45, 212, 191, 0.96)' },
        areaStyle: { opacity: 0.06, color: 'rgba(45, 212, 191, 0.24)' },
      },
      {
        name: 'Coste total',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: points.map((point) => toNumeric(point?.totalCosteLaboralAnual)),
        lineStyle: { width: 3, color: 'rgba(165, 180, 252, 0.92)' },
        itemStyle: { color: 'rgba(165, 180, 252, 0.92)' },
      },
    ],
  }
}

function buildLaborCostGestorHistoryOption(imports: any[], gestores: any[]) {
  const labels = buildTimelineLabels(imports)
  const importKeys = imports.map((point) => historyPointKey(point))
  const visibleGestores = [...(gestores || [])]
    .map((gestor) => {
      const values = importKeys
        .map((key) => {
          const point = (gestor?.points || []).find((item: any) => historyPointKey(item) === key)
          return toNumeric(point?.costeLaboralAnual)
        })
        .filter((value) => value != null)
      return {
        ...gestor,
        visibleCost: values.length ? values[values.length - 1] : null,
      }
    })
    .filter((gestor) => gestor.visibleCost != null)
    .sort((left, right) => {
      const leftValue = Number(left?.visibleCost || 0)
      const rightValue = Number(right?.visibleCost || 0)
      return rightValue - leftValue
    })
    .slice(0, 5)

  const palette = [
    'rgba(56, 189, 248, 0.96)',
    'rgba(45, 212, 191, 0.96)',
    'rgba(129, 140, 248, 0.94)',
    'rgba(244, 114, 182, 0.92)',
    'rgba(251, 191, 36, 0.92)',
  ]

  return {
    grid: { left: 24, right: 18, top: 34, bottom: 52, containLabel: true },
    legend: { top: 0, textStyle: { color: 'rgba(226, 232, 240, 0.82)', fontSize: 12 } },
    tooltip: {
      ...CHART_TOOLTIP_THEME,
      trigger: 'axis',
      formatter: (params: any) => {
        const rows = Array.isArray(params) ? params : [params]
        const axisLabel = String(rows[0]?.axisValue ?? '')
        const metrics = rows.map((point: any) => `${point.seriesName}: ${formatCurrency(point.data)}`).join('\n')
        return `${axisLabel}\n${metrics}`
      },
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: { fontSize: 11, rotate: labels.length > 4 ? 20 : 0, color: 'rgba(203, 213, 225, 0.78)' },
      axisTick: { show: false },
      axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.16)' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 12, color: 'rgba(203, 213, 225, 0.74)', formatter: (value: number) => COMPACT_CURRENCY_FORMAT.format(value) },
      splitLine: CHART_SPLIT_LINE,
    },
    series: visibleGestores.map((gestor, index) => {
      const values = new Map((gestor?.points || []).map((point: any) => [historyPointKey(point), toNumeric(point?.costeLaboralAnual)]))
      return {
        name: formatText(gestor?.gestor),
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: importKeys.map((key) => values.get(key) ?? null),
        lineStyle: { width: 3, color: palette[index % palette.length] },
        itemStyle: { color: palette[index % palette.length] },
      }
    }),
  }
}

function buildAnnualSeatsOption(annualSeatTotals: Record<string, number>) {
  const entries = Object.entries(annualSeatTotals || {}).sort(([yearA], [yearB]) => Number(yearA) - Number(yearB))
  return {
    grid: { left: 22, right: 18, top: 16, bottom: 42, containLabel: true },
    tooltip: {
      ...CHART_TOOLTIP_THEME,
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: any) => {
        const point = Array.isArray(params) ? params[0] : params
        return `${point?.axisValue ?? ''}\n${formatInteger(point?.data)} asientos`
      },
    },
    xAxis: {
      type: 'category',
      data: entries.map(([year]) => year),
      axisTick: { show: false },
      axisLabel: { fontSize: 12, color: 'rgba(203, 213, 225, 0.82)' },
      axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.16)' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 12, color: 'rgba(203, 213, 225, 0.74)', formatter: (value: number) => INTEGER_FORMAT.format(value) },
      splitLine: CHART_SPLIT_LINE,
    },
    series: [
      {
        type: 'bar',
        data: entries.map(([, value]) => value),
        barMaxWidth: 28,
        itemStyle: {
          borderRadius: [10, 10, 8, 8],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(129, 140, 248, 0.94)' },
              { offset: 1, color: 'rgba(56, 189, 248, 0.34)' },
            ],
          },
        },
      },
    ],
  }
}

function extractMonthlyGestorCosts(gestorCosts: any) {
  if (!gestorCosts) return []
  const months = new Set([
    ...Object.keys(gestorCosts.costePersonalMensual || {}),
    ...Object.keys(gestorCosts.ssEmpresaMensual || {}),
    ...Object.keys(gestorCosts.costeTotalMensual || {}),
  ])
  return MONTH_KEYS.filter((month) => months.has(month)).map((month) => ({
    month,
    costePersonal: gestorCosts.costePersonalMensual?.[month] ?? null,
    ssEmpresa: gestorCosts.ssEmpresaMensual?.[month] ?? null,
    costeTotal: gestorCosts.costeTotalMensual?.[month] ?? null,
  }))
}

function buildScatterOption(
  rows: any[],
  config: {
    xLabel: string
    yLabel: string
    xKind?: 'number' | 'currency' | 'integer'
    yKind?: 'number' | 'currency' | 'integer'
    getX: (row: any) => number | null
    getY: (row: any) => number | null
  }
) {
  const points = rows
    .map((row) => {
      const x = config.getX(row)
      const y = config.getY(row)
      if (x == null || y == null) return null
      return {
        gestor: row.gestor,
        value: [x, y, Math.max(12, Math.min(40, row.totalClients * 6))],
      }
    })
    .filter(Boolean)

  return {
    grid: { left: 24, right: 18, top: 22, bottom: 46, containLabel: true },
    tooltip: {
      ...CHART_TOOLTIP_THEME,
      trigger: 'item',
      formatter: (params: any) => {
        const point = params?.data
        if (!point) return ''
        return [
          formatText(point.gestor),
          `${config.xLabel}: ${formatMetricValue(point.value?.[0], config.xKind || 'number')}`,
          `${config.yLabel}: ${formatMetricValue(point.value?.[1], config.yKind || 'number')}`,
        ].join('\n')
      },
    },
    xAxis: {
      type: 'value',
      name: config.xLabel,
      nameGap: 18,
      nameTextStyle: { color: 'rgba(191, 219, 254, 0.74)', fontSize: 12, fontWeight: 700 },
      axisLabel: { fontSize: 12, color: 'rgba(203, 213, 225, 0.74)', formatter: (value: number) => formatAxisValue(value, config.xKind || 'number') },
      splitLine: CHART_SPLIT_LINE,
    },
    yAxis: {
      type: 'value',
      name: config.yLabel,
      nameGap: 24,
      nameTextStyle: { color: 'rgba(191, 219, 254, 0.74)', fontSize: 12, fontWeight: 700 },
      axisLabel: { fontSize: 12, color: 'rgba(203, 213, 225, 0.74)', formatter: (value: number) => formatAxisValue(value, config.yKind || 'number') },
      splitLine: CHART_SPLIT_LINE,
    },
    series: [
      {
        type: 'scatter',
        data: points,
        symbolSize: (value: any) => value?.[2] ?? 18,
        itemStyle: { color: 'rgba(96, 165, 250, 0.86)' },
        emphasis: {
          scale: 1.08,
          itemStyle: {
            shadowBlur: 28,
            shadowColor: 'rgba(56, 189, 248, 0.3)',
          },
        },
      },
    ],
  }
}

function buildConcentration(rows: any[], key: string) {
  const sorted = rows
    .map((row) => ({
      gestor: row.gestor,
      value: key === 'totalClients' ? row.totalClients : toNumeric(row?.[key]),
    }))
    .filter((row) => row.value != null)
    .sort((a, b) => b.value - a.value)

  if (!sorted.length) return null
  const total = sorted.reduce((sum, row) => sum + row.value, 0)
  if (!total) return null
  return {
    share: (sorted.slice(0, 3).reduce((sum, row) => sum + row.value, 0) / total) * 100,
    leaders: sorted.slice(0, 3).map((row) => row.gestor),
  }
}

function buildDelta(points: any[], key: string) {
  if (points.length < 2) return null
  const current = toNumeric(points[points.length - 1]?.[key])
  const previous = toNumeric(points[points.length - 2]?.[key])
  if (current == null || previous == null) return null
  return {
    current,
    previous,
    delta: current - previous,
    deltaPct: previous === 0 ? null : ((current - previous) / previous) * 100,
  }
}

function getDeltaTone(value: number | null | undefined) {
  if (value == null || Math.abs(value) < 1e-6) return 'neutral'
  return value > 0 ? 'up' : 'down'
}

function ChartMeta({ rows, metricKey, kind = 'number' }: { rows: any[]; metricKey: string; kind?: 'number' | 'currency' | 'integer' }) {
  const summary = summarizeNumeric(pluckNumeric(rows, metricKey))
  if (!summary) return null

  return (
    <div className="workforce-chart-meta">
      <span>Min {formatMetricValue(summary.min, kind)}</span>
      <span>Media {formatMetricValue(summary.avg, kind)}</span>
      <span>Max {formatMetricValue(summary.max, kind)}</span>
    </div>
  )
}

function GestorBarCard({
  title,
  eyebrow,
  note,
  rows,
  metricKey,
  kind = 'number',
  height = 280,
  activeIndex,
  onSelect,
}: {
  title: string
  eyebrow: string
  note?: string
  rows: any[]
  metricKey: string
  kind?: 'number' | 'currency' | 'integer'
  height?: number
  activeIndex?: number | null
  onSelect?: (gestor: string) => void
}) {
  return (
    <div className="workforce-chart-card">
      <div className="workforce-block-head">
        <span>{eyebrow}</span>
        <h3>{title}</h3>
        {note ? <p className="workforce-card-note">{note}</p> : null}
      </div>
      <EChart
        module="tribunal"
        actions={false}
        valueSuffix={kind === 'currency' ? 'EUR' : ''}
        className="workforce-chart-shell"
        height={height}
        activeDataIndex={activeIndex ?? null}
        onClick={
          onSelect
            ? (params) => {
                const index = Number(params?.dataIndex)
                if (!Number.isFinite(index) || index < 0 || index >= rows.length) return
                onSelect(rows[index].gestor)
              }
            : undefined
        }
        option={buildBarOption(rows, metricKey, kind)}
      />
      <ChartMeta rows={rows} metricKey={metricKey} kind={kind} />
    </div>
  )
}

function WorkforceKpiCard({
  label,
  value,
  detail,
  tone = 'default',
}: {
  label: string
  value: string
  detail?: string | null
  tone?: 'default' | 'accent' | 'teal'
}) {
  return (
    <div className={`workforce-kpi-card workforce-kpi-card-${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      {detail ? <small>{detail}</small> : null}
    </div>
  )
}

function SectionChips({ items }: { items: Array<string | null | undefined> }) {
  const visible = items.filter((item): item is string => !!item)
  if (!visible.length) return null

  return (
    <div className="workforce-section-chips">
      {visible.map((item, index) => (
        <span key={`${item}-${index}`} className="workforce-section-chip">
          {item}
        </span>
      ))}
    </div>
  )
}

export default function WorkforcePage() {
  const { id: companyId } = useCompanySelection()
  const toast = useToast()

  const [activeImportTab, setActiveImportTab] = useState<'workforce' | 'labor-costs'>('workforce')
  const [workforceFile, setWorkforceFile] = useState<File | null>(null)
  const [laborFile, setLaborFile] = useState<File | null>(null)
  const [workforceMonth, setWorkforceMonth] = useState('')
  const [workforceYear, setWorkforceYear] = useState('')
  const [showWorkforcePeriodFields, setShowWorkforcePeriodFields] = useState(false)
  const [useSnapshotPeriod, setUseSnapshotPeriod] = useState(true)
  const [laborMonth, setLaborMonth] = useState('')
  const [laborYear, setLaborYear] = useState('')
  const [workforceUploading, setWorkforceUploading] = useState(false)
  const [laborUploading, setLaborUploading] = useState(false)
  const [workforceError, setWorkforceError] = useState<string | null>(null)
  const [workforceSuccess, setWorkforceSuccess] = useState<string | null>(null)
  const [laborError, setLaborError] = useState<string | null>(null)
  const [laborSuccess, setLaborSuccess] = useState<string | null>(null)
  const [pdfLoading, setPdfLoading] = useState(false)
  const [selectedWorkforceImportId, setSelectedWorkforceImportId] = useState<number | null>(null)
  const [selectedGestor, setSelectedGestor] = useState<string | null>(null)
  const [comparisonBaseFile, setComparisonBaseFile] = useState<File | null>(null)
  const [comparisonFile, setComparisonFile] = useState<File | null>(null)
  const [comparisonBaseMonth, setComparisonBaseMonth] = useState('')
  const [comparisonBaseYear, setComparisonBaseYear] = useState('')
  const [comparisonMonth, setComparisonMonth] = useState('')
  const [comparisonYear, setComparisonYear] = useState('')
  const [comparisonLoading, setComparisonLoading] = useState(false)
  const [comparisonError, setComparisonError] = useState<string | null>(null)
  const [comparisonSuccess, setComparisonSuccess] = useState<string | null>(null)
  const [showComparisonPanel, setShowComparisonPanel] = useState(false)
  const [replaceComparisonBaseImport, setReplaceComparisonBaseImport] = useState(false)
  const [replaceComparisonImport, setReplaceComparisonImport] = useState(false)

  const workforceSummaryQuery = useQuery({
    queryKey: ['workforce-summary', companyId, selectedWorkforceImportId ?? 'latest'],
    queryFn: () => getWorkforceSummary(companyId as number, selectedWorkforceImportId),
    enabled: !!companyId,
  })

  const workforceStatusQuery = useQuery({
    queryKey: ['workforce-status', companyId],
    queryFn: () => getWorkforceStatus(companyId as number),
    enabled: !!companyId,
  })

  const laborStatusQuery = useQuery({
    queryKey: ['workforce-labor-costs-status', companyId],
    queryFn: () => getWorkforceLaborCostsStatus(companyId as number),
    enabled: !!companyId,
  })

  const summaryPreview = workforceSummaryQuery.data
  const comparisonBasePeriod = buildReferencePeriod(comparisonBaseMonth, comparisonBaseYear)
  const comparisonPeriod = buildReferencePeriod(comparisonMonth, comparisonYear)
  const previewLaborCostImports = summaryPreview?.laborCostImports || []
  const previewHasStoredBaseComparisonPeriod = !!comparisonBasePeriod && previewLaborCostImports.some((item: any) => item?.status === 'ACTIVE' && item.referencePeriod === comparisonBasePeriod)
  const previewHasStoredComparisonPeriod = !!comparisonPeriod && previewLaborCostImports.some((item: any) => item?.status === 'ACTIVE' && item.referencePeriod === comparisonPeriod)

  const workforceComparisonQuery = useQuery({
    queryKey: ['workforce-cost-comparison', companyId, comparisonBasePeriod || 'none', comparisonPeriod || 'none'],
    queryFn: () => getWorkforceLaborCostComparison(companyId as number, comparisonBasePeriod as string, comparisonPeriod as string),
    enabled:
      !!companyId &&
      !!comparisonBasePeriod &&
      !!comparisonPeriod &&
      comparisonBasePeriod !== comparisonPeriod &&
      previewHasStoredBaseComparisonPeriod &&
      previewHasStoredComparisonPeriod,
  })

  const summary = summaryPreview
  const workforceStatus = workforceStatusQuery.data
  const laborStatus = laborStatusQuery.data
  const comparisonData = workforceComparisonQuery.data || null
  const gestores = summary?.gestores || []
  const hasGestores = gestores.length > 0
  const kpis = summary?.kpis
  const laborCosts = summary?.laborCosts || null
  const pairedLaborCosts = summary?.pairedLaborCosts || null
  const history = summary?.history || null
  const laborCostsHistory = summary?.laborCostsHistory || null
  const historyImports = history?.imports || []
  const laborHistoryImports = laborCostsHistory?.imports || []
  const comparableHistoryImports = useMemo(() => buildComparablePeriodHistory(historyImports), [historyImports])
  const comparableLaborHistoryImports = useMemo(() => buildComparablePeriodHistory(laborHistoryImports), [laborHistoryImports])
  const workforceImports = summary?.workforceImports || []
  const laborCostImports = summary?.laborCostImports || []
  const workforceImport = summary?.workforceImport || null
  const laborCostsImport = summary?.laborCostsImport || null
  const pairedLaborCostsImport = summary?.pairedLaborCostsImport || null
  const activeWorkforceImport = workforceImport || workforceStatus || null
  const activeLaborStatus = laborStatus || null
  const economicGestores = laborCosts?.gestores || []
  const hasEconomicData = !!laborCosts
  const hasPairedCostActivity = !!pairedLaborCosts
  const economicHistoryGestores = useMemo(
    () => aggregateEconomicHistoryGestores(laborCostsHistory?.gestores || [], gestores),
    [laborCostsHistory?.gestores, gestores]
  )
  const economicDisplayRows = useMemo(() => {
    return aggregateEconomicGestores(economicGestores, gestores)
  }, [economicGestores, gestores])

  const parsedWorkforceReference = parseReferencePeriod(workforceImport?.referencePeriod)
  const hasSnapshotReference = !!(workforceImport?.referenceYear && workforceImport?.referenceMonth)
  const snapshotLabel = capitalizeLabel(workforceImport?.referenceLabel, workforceImport?.filename ? 'Base operativa' : 'Periodo desconocido')
  const economicLaborLabel = capitalizeLabel(laborCostsImport?.referenceLabel, laborCostsImport?.filename ? 'Periodo activo' : 'Sin costes')
  const pairedLaborLabel = capitalizeLabel(pairedLaborCostsImport?.referenceLabel, 'No disponible para este snapshot')
  const laborStatusLabel = capitalizeLabel(activeLaborStatus?.referenceLabel, 'No disponible')

  const yearOptions = useMemo(
    () =>
      buildYearOptions([
        ...(summary?.activityYears || []),
        ...(activeWorkforceImport?.activityYears || []),
        workforceImport?.referenceYear,
        activeLaborStatus?.referenceYear,
        workforceYear,
        laborYear,
        comparisonBaseYear,
        comparisonYear,
      ]),
    [summary?.activityYears, activeWorkforceImport?.activityYears, workforceImport?.referenceYear, activeLaborStatus?.referenceYear, workforceYear, laborYear, comparisonBaseYear, comparisonYear]
  )

  const laborPeriodOptions = useMemo(() => {
    const seen = new Set<string>()
    return laborCostImports
      .filter((item: any) => item?.referencePeriod && item.referencePeriod !== 'UNKNOWN')
      .filter((item: any) => item?.status === 'ACTIVE')
      .filter((item: any) => {
        if (seen.has(item.referencePeriod)) return false
        seen.add(item.referencePeriod)
        return true
      })
      .map((item: any) => ({
        period: item.referencePeriod,
        label: capitalizeLabel(item.referenceLabel, item.referencePeriod),
      }))
  }, [laborCostImports])

  const workforcePeriodOptions = useMemo(() => {
    const seen = new Set<string>()
    return comparableHistoryImports
      .filter((item: any) => item?.referencePeriod && item.referencePeriod !== 'UNKNOWN')
      .filter((item: any) => {
        if (seen.has(item.referencePeriod)) return false
        seen.add(item.referencePeriod)
        return true
      })
      .map((item: any) => ({
        period: item.referencePeriod,
        label: capitalizeLabel(item.referenceLabel, item.referencePeriod),
      }))
  }, [comparableHistoryImports])

  const analysisPeriodOptions = useMemo(() => {
    const byPeriod = new Map<string, any>()
    workforcePeriodOptions.forEach((option: any) => {
      byPeriod.set(option.period, {
        period: option.period,
        label: option.label,
        hasWorkforce: true,
        hasCosts: false,
      })
    })
    laborPeriodOptions.forEach((option: any) => {
      const current = byPeriod.get(option.period)
      byPeriod.set(option.period, {
        period: option.period,
        label: option.label || current?.label || option.period,
        hasWorkforce: current?.hasWorkforce || false,
        hasCosts: true,
      })
    })
    return Array.from(byPeriod.values()).sort((left: any, right: any) => compareReferencePeriods(left.period, right.period))
  }, [workforcePeriodOptions, laborPeriodOptions])

  const gestorSelectionPool = useMemo(() => {
    const labelsByNormalized = new Map<string, string>()
    ;[...(gestores || []).map((gestor) => gestor.gestor), ...economicDisplayRows.map((gestor) => gestor.gestor)].forEach((label) => {
      const normalized = normalizeGestorName(label)
      const text = String(label || '').trim()
      if (!normalized || !text || labelsByNormalized.has(normalized)) return
      labelsByNormalized.set(normalized, text)
    })
    return Array.from(labelsByNormalized.values())
  }, [gestores, economicDisplayRows])

  useEffect(() => {
    if (!gestorSelectionPool.length) {
      setSelectedGestor(null)
      return
    }
    if (!selectedGestor || !gestorSelectionPool.includes(selectedGestor)) {
      setSelectedGestor(gestorSelectionPool[0])
    }
  }, [gestorSelectionPool, selectedGestor])

  useEffect(() => {
    if (!selectedWorkforceImportId) return
    if (!workforceImports.some((item) => item.id === selectedWorkforceImportId)) {
      setSelectedWorkforceImportId(null)
    }
  }, [selectedWorkforceImportId, workforceImports])

  useEffect(() => {
    if (hasSnapshotReference && useSnapshotPeriod) {
      setLaborMonth(parsedWorkforceReference.month)
      setLaborYear(parsedWorkforceReference.year)
    }
  }, [hasSnapshotReference, parsedWorkforceReference.month, parsedWorkforceReference.year, useSnapshotPeriod])

  useEffect(() => {
    if (workforceMonth || workforceYear) {
      setShowWorkforcePeriodFields(true)
    }
  }, [workforceMonth, workforceYear])

  useEffect(() => {
    if (!analysisPeriodOptions.length) return
    const availablePeriods = new Set(analysisPeriodOptions.map((option: any) => option.period))
    const latest = analysisPeriodOptions[analysisPeriodOptions.length - 1]
    const previous = analysisPeriodOptions[analysisPeriodOptions.length - 2] || latest

    if (!comparisonBasePeriod || !availablePeriods.has(comparisonBasePeriod)) {
      const parsedBase = parseReferencePeriod(previous.period)
      setComparisonBaseYear(parsedBase.year)
      setComparisonBaseMonth(parsedBase.month)
    }

    if (!comparisonPeriod || !availablePeriods.has(comparisonPeriod) || comparisonPeriod === comparisonBasePeriod) {
      const parsedCompare = parseReferencePeriod(latest.period)
      setComparisonYear(parsedCompare.year)
      setComparisonMonth(parsedCompare.month)
    }
  }, [analysisPeriodOptions, comparisonBasePeriod, comparisonPeriod])

  useEffect(() => {
    if (comparisonError || comparisonSuccess || comparisonLoading || comparisonData?.status === 'READY') {
      setShowComparisonPanel(true)
    }
  }, [comparisonError, comparisonSuccess, comparisonLoading, comparisonData?.status])

  useEffect(() => {
    setReplaceComparisonBaseImport(false)
    setComparisonBaseFile(null)
  }, [comparisonBasePeriod])

  useEffect(() => {
    setReplaceComparisonImport(false)
    setComparisonFile(null)
  }, [comparisonPeriod])

  async function handleWorkforceUpload() {
    if (!companyId || !workforceFile) return

    setWorkforceUploading(true)
    setWorkforceError(null)
    setWorkforceSuccess(null)

    try {
      const result = await uploadWorkforceImport(companyId, workforceFile, buildReferencePeriod(workforceMonth, workforceYear))
      await workforceStatusQuery.refetch()
      setSelectedWorkforceImportId(result.id)
      setWorkforceSuccess(`Fichero procesado: ${result.rowCount} filas de trabajadores.`)
      setWorkforceFile(null)
      setWorkforceMonth('')
      setWorkforceYear('')
      setShowWorkforcePeriodFields(false)
      toast.push({
        tone: 'success',
        title: 'Trabajadores',
        message: `Importación completada con ${result.warningCount || 0} avisos.`,
      })
    } catch (error: any) {
      const message = error?.message || 'No se pudo importar el fichero de trabajadores.'
      setWorkforceError(message)
      toast.push({ tone: 'danger', title: 'Trabajadores', message })
    } finally {
      setWorkforceUploading(false)
    }
  }

  async function handleLaborUpload() {
    if (!companyId || !laborFile) return

    setLaborUploading(true)
    setLaborError(null)
    setLaborSuccess(null)

    try {
      const referencePeriod = useSnapshotPeriod && hasSnapshotReference
        ? buildReferencePeriod(parsedWorkforceReference.month, parsedWorkforceReference.year)
        : buildReferencePeriod(laborMonth, laborYear)

      const result = await uploadWorkforceLaborCostsImport(companyId, laborFile, referencePeriod)
      await workforceSummaryQuery.refetch()
      await laborStatusQuery.refetch()
      setLaborSuccess(`Fichero procesado: ${result.rowCount} filas de costes laborales.`)
      setLaborFile(null)
      if (!useSnapshotPeriod) {
        setLaborMonth('')
        setLaborYear('')
      }
      toast.push({
        tone: 'success',
        title: 'Costes laborales',
        message: `Importación completada con ${result.warningCount || 0} avisos.`,
      })
    } catch (error: any) {
      const message = error?.message || 'No se pudo importar el fichero de costes laborales.'
      setLaborError(message)
      toast.push({ tone: 'danger', title: 'Costes laborales', message })
    } finally {
      setLaborUploading(false)
    }
  }

  async function handleImportAndCompare() {
    if (!companyId || !comparisonBasePeriod || !comparisonPeriod || comparisonBasePeriod === comparisonPeriod) {
      return
    }

    const hasStoredBasePeriod = laborPeriodOptions.some((option: any) => option.period === comparisonBasePeriod)
    const hasStoredComparisonPeriod = laborPeriodOptions.some((option: any) => option.period === comparisonPeriod)
    if (!comparisonBaseFile && !hasStoredBasePeriod) {
      setComparisonError('Selecciona el fichero del periodo base o reutiliza una importación activa de ese periodo.')
      return
    }
    if (!comparisonFile && !hasStoredComparisonPeriod) {
      setComparisonError('Selecciona el fichero del periodo comparado o reutiliza una importación activa de ese periodo.')
      return
    }

    setComparisonLoading(true)
    setComparisonError(null)
    setComparisonSuccess(null)
    setShowComparisonPanel(true)

    try {
      if (comparisonBaseFile) {
        await uploadWorkforceLaborCostsImport(companyId, comparisonBaseFile, comparisonBasePeriod)
      }
      if (comparisonFile) {
        await uploadWorkforceLaborCostsImport(companyId, comparisonFile, comparisonPeriod)
      }

      await laborStatusQuery.refetch()
      await workforceSummaryQuery.refetch()
      await workforceComparisonQuery.refetch()
      setComparisonBaseFile(null)
      setComparisonFile(null)
      setComparisonSuccess('Comparación de costes preparada con los periodos seleccionados.')
      toast.push({
        tone: 'success',
        title: 'Comparación Workforce',
        message: 'Los costes base y comparados ya están listos para revisión.',
      })
    } catch (error: any) {
      const message = error?.message || 'No se pudo completar la comparación de costes laborales.'
      setComparisonError(message)
      toast.push({ tone: 'danger', title: 'Comparación Workforce', message })
    } finally {
      setComparisonLoading(false)
    }
  }

  async function handleDownloadPdf() {
    if (!companyId || !hasGestores) return

    setPdfLoading(true)
    try {
      const blob = await downloadWorkforceReportPdf(companyId, selectedWorkforceImportId)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `workforce-report-${companyId}.pdf`
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
      toast.push({ tone: 'success', title: 'Workforce', message: 'Informe PDF generado.' })
    } catch (error: any) {
      const message = error?.message || 'No se pudo generar el informe Workforce.'
      toast.push({ tone: 'danger', title: 'Workforce', message })
    } finally {
      setPdfLoading(false)
    }
  }

  const activityYearsRange = activeWorkforceImport?.activityYears?.length
    ? `${activeWorkforceImport.activityYears[0]}-${activeWorkforceImport.activityYears[activeWorkforceImport.activityYears.length - 1]}`
    : EMPTY_VALUE

  const pendingWorkforceReference = buildReferencePeriod(workforceMonth, workforceYear)
  const workforcePeriodPreview = formatPeriodLabel(workforceMonth, workforceYear, 'Periodo opcional')
  const laborTargetPeriodLabel =
    useSnapshotPeriod && hasSnapshotReference ? snapshotLabel : formatPeriodLabel(laborMonth, laborYear, 'Periodo definido por el fichero')
  const displayedWorkforceImport = activeWorkforceImport
  const displayedWorkforceFilename = displayedWorkforceImport?.filename || 'Fichero principal'
  const displayedWorkforceHasReference = !!displayedWorkforceImport?.referencePeriod && displayedWorkforceImport.referencePeriod !== 'UNKNOWN'
  const displayedWorkforceStatusLabel = displayedWorkforceImport
    ? `Importada · ${displayedWorkforceHasReference ? capitalizeLabel(displayedWorkforceImport?.referenceLabel, 'Base operativa') : 'Base operativa'}`
    : 'Sin cargar'
  const workforceTabStateLabel = displayedWorkforceImport
    ? `Importada · ${capitalizeLabel(displayedWorkforceImport.referenceLabel, 'Periodo sin fecha')}`
    : 'Sin cargar'
  const laborTabStateLabel = activeLaborStatus?.filename ? `Activos · ${laborStatusLabel}` : 'Sin cargar'
  const laborCoverageWarning =
    hasSnapshotReference &&
    !laborCostsImport &&
    activeLaborStatus?.referencePeriod &&
    activeLaborStatus.referencePeriod !== 'UNKNOWN' &&
    activeLaborStatus.referencePeriod !== workforceImport?.referencePeriod
      ? `Los costes corresponden a ${laborStatusLabel} y no están asociados al snapshot de ${snapshotLabel}.`
      : null

  const executiveReadings = useMemo(
    () =>
      summary?.insights?.executiveReadings?.length
        ? summary.insights.executiveReadings
        : buildExecutiveReadings(gestores, hasPairedCostActivity),
    [summary?.insights?.executiveReadings, gestores, hasPairedCostActivity]
  )

  const costActivityReadings = useMemo(
    () =>
      summary?.insights?.costActivityReadings?.length
        ? summary.insights.costActivityReadings
        : buildCostVsActivityReadings(gestores, hasPairedCostActivity),
    [summary?.insights?.costActivityReadings, gestores, hasPairedCostActivity]
  )

  const laborContextMessage =
    hasSnapshotReference &&
    laborCostsImport?.referencePeriod &&
    laborCostsImport.referencePeriod !== 'UNKNOWN' &&
    laborCostsImport.referencePeriod !== workforceImport?.referencePeriod
      ? pairedLaborCostsImport
        ? `La lectura economica activa usa ${economicLaborLabel}; el cruce del snapshot se resuelve con ${pairedLaborLabel}.`
        : `Los costes activos corresponden a ${economicLaborLabel} y no estan asociados al snapshot de ${snapshotLabel}.`
      : laborCoverageWarning

  const analysisBaseOption = comparisonBasePeriod ? analysisPeriodOptions.find((option: any) => option.period === comparisonBasePeriod) || null : null
  const analysisComparisonOption = comparisonPeriod ? analysisPeriodOptions.find((option: any) => option.period === comparisonPeriod) || null : null
  const comparisonBaseLabel = comparisonBasePeriod
    ? capitalizeLabel(analysisBaseOption?.label, formatPeriodLabel(comparisonBaseMonth, comparisonBaseYear, 'Periodo A'))
    : 'Periodo A'
  const comparisonSelectedLabel = comparisonPeriod
    ? capitalizeLabel(analysisComparisonOption?.label, formatPeriodLabel(comparisonMonth, comparisonYear, 'Periodo B'))
    : 'Periodo B'
  const hasRequestedComparisonWindow = !!comparisonBasePeriod && !!comparisonPeriod && comparisonBasePeriod !== comparisonPeriod
  const selectedWorkforceHistoryImports = useMemo(
    () => (hasRequestedComparisonWindow ? buildSelectedComparisonPoints(comparableHistoryImports, comparisonBasePeriod, comparisonPeriod) : comparableHistoryImports.slice(-2)),
    [hasRequestedComparisonWindow, comparableHistoryImports, comparisonBasePeriod, comparisonPeriod]
  )
  const selectedLaborHistoryImports = useMemo(
    () => (hasRequestedComparisonWindow ? buildSelectedComparisonPoints(comparableLaborHistoryImports, comparisonBasePeriod, comparisonPeriod) : comparableLaborHistoryImports.slice(-2)),
    [hasRequestedComparisonWindow, comparableLaborHistoryImports, comparisonBasePeriod, comparisonPeriod]
  )
  const workforceWindowReady = selectedWorkforceHistoryImports.length === 2
  const hasLaborHistorySelection = selectedLaborHistoryImports.length > 0
  const laborWindowReady = selectedLaborHistoryImports.length === 2

  const historyComparisonLabel = useMemo(
    () => (hasRequestedComparisonWindow ? `${comparisonBaseLabel} vs ${comparisonSelectedLabel}` : buildComparisonWindowLabel(selectedWorkforceHistoryImports)),
    [hasRequestedComparisonWindow, comparisonBaseLabel, comparisonSelectedLabel, selectedWorkforceHistoryImports]
  )
  const laborHistoryComparisonLabel = useMemo(
    () => (hasRequestedComparisonWindow ? `${comparisonBaseLabel} vs ${comparisonSelectedLabel}` : buildComparisonWindowLabel(selectedLaborHistoryImports)),
    [hasRequestedComparisonWindow, comparisonBaseLabel, comparisonSelectedLabel, selectedLaborHistoryImports]
  )
  const historyUnknownPeriods = useMemo(
    () => historyImports.filter((point: any) => !hasKnownReferencePeriod(point)).length,
    [historyImports]
  )
  const laborHistoryUnknownPeriods = useMemo(
    () => laborHistoryImports.filter((point: any) => !hasKnownReferencePeriod(point)).length,
    [laborHistoryImports]
  )

  const historyDeltas = useMemo(
    () => ({
      clients: buildDelta(selectedWorkforceHistoryImports, 'totalClients'),
      minutes: buildDelta(selectedWorkforceHistoryImports, 'totalMinutas'),
      carga: buildDelta(selectedWorkforceHistoryImports, 'totalCarga'),
      asientos: buildDelta(selectedWorkforceHistoryImports, 'totalVolumenAsientos'),
    }),
    [selectedWorkforceHistoryImports]
  )

  const laborHistoryDeltas = useMemo(
    () => ({
      personal: buildDelta(selectedLaborHistoryImports, 'totalCostePersonalAnual'),
      ss: buildDelta(selectedLaborHistoryImports, 'totalSsEmpresaAnual'),
      total: buildDelta(selectedLaborHistoryImports, 'totalCosteLaboralAnual'),
    }),
    [selectedLaborHistoryImports]
  )

  const concentration = useMemo(
    () => ({
      clients: buildConcentration(gestores, 'totalClients'),
      minutes: buildConcentration(gestores, 'totalMinutas'),
      seats: buildConcentration(gestores, 'totalVolumenAsientos'),
      cost: buildConcentration(gestores, 'costeLaboralAnual'),
      carga: buildConcentration(gestores, 'totalCarga'),
    }),
    [gestores]
  )

  const selectedGestorKey = normalizeGestorName(selectedGestor)
  const activeGestor = useMemo(
    () => findGestorBySelection(gestores, selectedGestor),
    [gestores, selectedGestor]
  )
  const activeEconomicGestor = useMemo(
    () => findGestorBySelection(economicDisplayRows, selectedGestor),
    [economicDisplayRows, selectedGestor]
  )

  const activeGestorIndex = activeGestor ? gestores.findIndex((gestor) => gestor.gestor === activeGestor.gestor) : -1
  const activeEconomicGestorIndex = activeEconomicGestor
    ? economicDisplayRows.findIndex((gestor) => normalizeGestorName(gestor.gestor) === normalizeGestorName(activeEconomicGestor.gestor))
    : -1

  const activeGestorHistory = useMemo(() => {
    const points = history?.gestores?.find((gestor) => normalizeGestorName(gestor.gestor) === selectedGestorKey)?.points || []
    return buildComparablePeriodHistory(points)
  }, [history?.gestores, selectedGestorKey])
  const activeEconomicGestorHistory = useMemo(() => {
    const source = economicHistoryGestores.find((gestor) => normalizeGestorName(gestor.gestor) === selectedGestorKey) || null
    const comparablePoints = buildComparablePeriodHistory(source?.points || [])
    return hasRequestedComparisonWindow
      ? buildSelectedComparisonPoints(comparablePoints, comparisonBasePeriod, comparisonPeriod)
      : comparablePoints
  }, [economicHistoryGestores, selectedGestorKey, hasRequestedComparisonWindow, comparisonBasePeriod, comparisonPeriod])
  const activeEconomicGestorHistorySeries = useMemo(
    () =>
      activeEconomicGestorHistory.map((point: any) => ({
        ...point,
        totalCostePersonalAnual: point?.costePersonalAnual,
        totalSsEmpresaAnual: point?.ssEmpresaAnual,
        totalCosteLaboralAnual: point?.costeLaboralAnual,
      })),
    [activeEconomicGestorHistory]
  )
  const activeGestorMonthlyCosts = useMemo(() => extractMonthlyGestorCosts(activeEconomicGestor), [activeEconomicGestor])
  const monthlyTotals = laborCosts?.monthlyTotals || []
  const activeGestorCostValue = activeEconomicGestor?.costeLaboralAnual ?? activeGestor?.costeLaboralAnual ?? null

  const costScopeLabel = laborCostsImport?.annualCoverage ? 'Anual' : laborCostsImport?.referenceLabel || 'Periodo cargado'
  const averageCostLabel = laborCostsImport?.annualCoverage ? 'Media anual' : 'Media del periodo'
  const costChartEyebrow = laborCostsImport?.annualCoverage ? 'Anual' : 'Periodo'
  const effectiveCostChartTitle = laborCostsImport?.annualCoverage ? 'Coste por gestor' : `Coste por gestor · ${economicLaborLabel}`
  const costChartTitle = laborCostsImport?.annualCoverage ? 'Coste por gestor' : `Coste por gestor · ${economicLaborLabel}`

  const activeGestorAnnualSeatsEntries = Object.keys(activeGestor?.annualSeatTotals || {})
  const hasScatterCostMinutes = gestores.some((gestor) => gestor.totalMinutas != null && gestor.costeLaboralAnual != null)
  const hasScatterCostSeats = gestores.some((gestor) => gestor.totalVolumenAsientos != null && gestor.costeLaboralAnual != null)
  const hasScatterClientsCarga = gestores.some((gestor) => gestor.totalClients != null && gestor.cargaMedia != null)
  const hasScatterMinutesSeats = gestores.some((gestor) => gestor.totalMinutas != null && gestor.totalVolumenAsientos != null)
  const hasStoredBaseComparisonPeriod = !!comparisonBasePeriod && laborPeriodOptions.some((option: any) => option.period === comparisonBasePeriod)
  const hasStoredComparisonPeriod = !!comparisonPeriod && laborPeriodOptions.some((option: any) => option.period === comparisonPeriod)
  const canRunComparison =
    !!companyId &&
    !!comparisonBasePeriod &&
    !!comparisonPeriod &&
    comparisonBasePeriod !== comparisonPeriod &&
    (!!comparisonBaseFile || hasStoredBaseComparisonPeriod) &&
    (!!comparisonFile || hasStoredComparisonPeriod)
  const comparisonReady = comparisonData?.status === 'READY'
  const storedBaseComparisonImport =
    laborCostImports.find((item: any) => item?.status === 'ACTIVE' && item.referencePeriod === comparisonBasePeriod) || null
  const storedComparisonImport =
    laborCostImports.find((item: any) => item?.status === 'ACTIVE' && item.referencePeriod === comparisonPeriod) || null
  const showBaseComparisonUpload = !!comparisonBasePeriod && (!hasStoredBaseComparisonPeriod || replaceComparisonBaseImport)
  const showComparisonUpload = !!comparisonPeriod && (!hasStoredComparisonPeriod || replaceComparisonImport)
  const comparisonPeriodsSummary =
    comparisonBasePeriod && comparisonPeriod
      ? `${comparisonBaseLabel} vs ${comparisonSelectedLabel}`
      : 'Selecciona Periodo A y Periodo B para activar la lectura de variacion.'
  const comparisonReusedPeriods = [hasStoredBaseComparisonPeriod, hasStoredComparisonPeriod].filter(Boolean).length
  const comparisonStatusLabel = comparisonReady ? 'Lista' : comparisonBasePeriod && comparisonPeriod ? 'Pendiente' : 'Opcional'
  const comparisonStatusClass = comparisonReady ? 'is-success' : comparisonBasePeriod && comparisonPeriod ? 'is-pending' : 'is-muted'
  const comparisonSummaryNote = comparisonReady
    ? comparisonData?.message || 'La comparación ya está disponible.'
    : 'Reutiliza periodos activos y sube solo el fichero que falte o quieras sustituir.'
  const workforceTimelineStateLabel = workforceImport ? 'Activo' : 'Sin snapshot'
  const laborTimelineStateLabel = laborCostsImport ? 'Asociados' : activeLaborStatus?.filename ? 'Fuera del snapshot' : 'Sin pairing'
  const laborTimelineStateTone = laborCostsImport ? 'is-success' : activeLaborStatus?.filename ? 'is-warning' : 'is-muted'
  const pairingSummaryLabel = laborCostsImport ? 'Con costes asociados' : activeLaborStatus?.filename ? 'Costes cargados fuera del snapshot' : 'Solo cartera'
  const effectiveLaborTimelineStateLabel = pairedLaborCostsImport ? 'Compatible' : laborCostsImport?.filename ? 'Sin cruce' : 'Sin costes'
  const effectiveLaborTimelineStateTone = pairedLaborCostsImport ? 'is-success' : laborCostsImport?.filename ? 'is-warning' : 'is-muted'
  const effectivePairingSummaryLabel = pairedLaborCostsImport ? 'Cruce disponible' : laborCostsImport?.filename ? 'Cruce no disponible' : 'Solo cartera'

  return (
    <div className="workforce-page">
      <PageHeader
        title="Trabajadores"
        subtitle="Cartera, carga, actividad y costes por gestor."
        actions={
          <div className="workforce-top-actions">
            <span className="badge">{displayedWorkforceImport?.filename ? 'Base operativa' : 'Pendiente'}</span>
            {activeLaborStatus?.filename ? <span className="badge badge-ok">Costes activos</span> : null}
          </div>
        }
      />

      {!companyId ? <Alert tone="warning">Selecciona una empresa.</Alert> : null}
      {workforceSummaryQuery.error ? <Alert tone="danger">{String(workforceSummaryQuery.error?.message || 'No se pudo cargar Trabajadores.')}</Alert> : null}
      {workforceError ? <Alert tone="danger">{workforceError}</Alert> : null}
      {workforceSuccess ? <Alert tone="success">{workforceSuccess}</Alert> : null}
      {laborError ? <Alert tone="danger">{laborError}</Alert> : null}
      {laborSuccess ? <Alert tone="success">{laborSuccess}</Alert> : null}

      <section className="workforce-hero card">
        <div className="workforce-hero-copy">
          <span className="workforce-eyebrow">Workforce</span>
          <h2>Cartera, actividad y coste por gestor.</h2>
          <p>Importa la base operativa y añade costes solo cuando quieras enriquecer el análisis.</p>

          <div className="workforce-hero-meta">
            <div>
              <span>Última carga</span>
              <strong>{displayedWorkforceImport?.createdAt ? formatDateTime(displayedWorkforceImport.createdAt) : EMPTY_VALUE}</strong>
            </div>
            <div>
              <span>Columnas detectadas</span>
              <strong>{formatInteger(displayedWorkforceImport?.detectedColumns?.length || 0)}</strong>
            </div>
            <div>
              <span>Series N AS</span>
              <strong>{activityYearsRange}</strong>
            </div>
          </div>
        </div>

        <div className="workforce-hero-side">
          <div className="workforce-import-tabs" role="tablist" aria-label="Tipos de importación Workforce">
            <button
              type="button"
              role="tab"
              aria-selected={activeImportTab === 'workforce'}
              className={`workforce-import-tab ${activeImportTab === 'workforce' ? 'is-active' : ''}`}
              onClick={() => setActiveImportTab('workforce')}
            >
              <span className="workforce-import-tab-top">
                <span className="workforce-upload-badge">Principal</span>
                <span className={`workforce-import-tab-state ${displayedWorkforceImport?.filename ? 'is-success' : 'is-muted'}`}>
                  {displayedWorkforceStatusLabel}
                </span>
              </span>
              <strong>Cartera y actividad</strong>
              <small>Gestores, clientes, minutos y carga.</small>
            </button>

            <button
              type="button"
              role="tab"
              aria-selected={activeImportTab === 'labor-costs'}
              className={`workforce-import-tab ${activeImportTab === 'labor-costs' ? 'is-active' : ''}`}
              onClick={() => setActiveImportTab('labor-costs')}
            >
              <span className="workforce-import-tab-top">
                <span className="workforce-upload-badge workforce-upload-badge-muted">Opcional</span>
                <span className={`workforce-import-tab-state ${activeLaborStatus?.filename ? 'is-success' : 'is-muted'}`}>
                  {laborTabStateLabel}
                </span>
              </span>
              <strong>Costes laborales</strong>
              <small>Ratios económicos por gestor.</small>
            </button>
          </div>

          <div
            className="imports-fold-panel workforce-fold-panel workforce-import-panel"
            role="tabpanel"
            aria-label={activeImportTab === 'workforce' ? 'Formulario de cartera y actividad' : 'Formulario de costes laborales'}
          >
            <div className="imports-fold-body" key={activeImportTab}>
              {activeImportTab === 'workforce' ? (
                <>
                  <div className="workforce-upload-intro">
                    <span className="workforce-upload-badge">Principal</span>
                    <p>Base operativa de cartera, actividad y reparto por gestor.</p>
                  </div>

                  <div className="workforce-upload-grid workforce-upload-grid-primary">
                    <div className="workforce-upload-card">
                      <label className="workforce-upload-label">Archivo</label>
                      <label className="workforce-file-trigger mt-8">
                        <input
                          type="file"
                          accept=".xlsx,.csv"
                          onChange={(event) => setWorkforceFile(event.target.files?.[0] ?? null)}
                          className="sr-only"
                        />
                        <span className="workforce-file-trigger-button">Seleccionar archivo</span>
                        <span className="workforce-file-trigger-name" title={workforceFile ? workforceFile.name : 'Sin archivo seleccionado.'}>
                          {workforceFile ? workforceFile.name : 'Sin archivo seleccionado.'}
                        </span>
                      </label>
                      <div className="workforce-upload-note">XLSX o CSV de cartera y actividad.</div>
                      <div className="workforce-upload-helper">Los costes laborales se cargan en la pestaña opcional.</div>
                    </div>

                    <div className="workforce-upload-card">
                      <label className="workforce-upload-label">Periodo de los datos</label>
                      <div className="workforce-period-picker">
                        <select value={workforceMonth} onChange={(event) => setWorkforceMonth(event.target.value)}>
                          <option value="">Mes</option>
                          {MONTH_OPTIONS.map((option) => (
                            <option key={`workforce-month-${option.value}`} value={option.value}>
                              {option.label}
                            </option>
                          ))}
                        </select>
                        <select value={workforceYear} onChange={(event) => setWorkforceYear(event.target.value)}>
                          <option value="">Año</option>
                          {yearOptions.map((year) => (
                            <option key={`workforce-year-${year}`} value={year}>
                              {year}
                            </option>
                          ))}
                        </select>
                      </div>
                      <div className="workforce-upload-note mt-8">
                        Mes al que corresponde esta cartera. Se usa para comparar cargas y asociar costes.
                      </div>
                      <div className="workforce-upload-helper">
                        {buildReferencePeriod(workforceMonth, workforceYear)
                          ? `Periodo seleccionado: ${workforcePeriodPreview}`
                          : 'Si lo dejas vacío, la cartera se importará sin periodo asociado.'}
                      </div>
                    </div>

                    <div className="workforce-upload-card">
                      <label className="workforce-upload-label">Estado</label>
                      <strong className={`workforce-upload-status ${displayedWorkforceImport?.filename ? 'is-success' : 'is-pending'}`}>
                        {displayedWorkforceImport?.filename ? 'Importado' : 'Pendiente'}
                      </strong>
                      <div className="workforce-upload-note mt-8" title={displayedWorkforceFilename}>
                        {displayedWorkforceFilename}
                      </div>
                      <div className="workforce-upload-helper">
                        {displayedWorkforceHasReference
                          ? `Snapshot activo: ${capitalizeLabel(displayedWorkforceImport?.referenceLabel, displayedWorkforceImport?.referencePeriod || 'sin periodo')}`
                          : 'El periodo solo se usa para historico y cruce con costes.'}
                      </div>
                    </div>
                  </div>

                  <div className="workforce-inline-period-card mt-12">
                    <div className="workforce-inline-period-head">
                      <div className="workforce-inline-period-title">
                        <span className="workforce-upload-badge workforce-upload-badge-muted">Opcional</span>
                        <label className="workforce-upload-label">Periodo de la base operativa</label>
                      </div>
                      <Button
                        type="button"
                        variant={showWorkforcePeriodFields ? 'secondary' : 'ghost'}
                        size="sm"
                        onClick={() => setShowWorkforcePeriodFields((current) => !current)}
                      >
                        {showWorkforcePeriodFields
                          ? 'Ocultar periodo'
                          : pendingWorkforceReference
                            ? workforcePeriodPreview
                            : 'Anadir periodo'}
                      </Button>
                    </div>

                    <div className="workforce-inline-period-body">
                      <p>Usalo solo si quieres comparar snapshots o asociar esta cartera con costes del mismo periodo.</p>

                      {showWorkforcePeriodFields ? (
                        <>
                          <div className="workforce-period-picker">
                            <select value={workforceMonth} onChange={(event) => setWorkforceMonth(event.target.value)}>
                              <option value="">Mes</option>
                              {MONTH_OPTIONS.map((option) => (
                                <option key={`workforce-month-${option.value}`} value={option.value}>
                                  {option.label}
                                </option>
                              ))}
                            </select>
                            <select value={workforceYear} onChange={(event) => setWorkforceYear(event.target.value)}>
                              <option value="">Ano</option>
                              {yearOptions.map((year) => (
                                <option key={`workforce-year-${year}`} value={year}>
                                  {year}
                                </option>
                              ))}
                            </select>
                          </div>
                          <div className="workforce-upload-helper">
                            {pendingWorkforceReference
                              ? `Periodo listo: ${workforcePeriodPreview}`
                              : 'Si lo dejas vacio, la cartera se importara sin periodo asociado.'}
                          </div>
                        </>
                      ) : (
                        <div className="workforce-upload-helper">
                          {displayedWorkforceHasReference
                            ? `Ultimo snapshot con periodo ${capitalizeLabel(displayedWorkforceImport?.referenceLabel, displayedWorkforceImport?.referencePeriod || 'sin periodo')}.`
                            : 'Ahora mismo esta carga puede funcionar sin mes ni ano.'}
                        </div>
                      )}
                    </div>
                  </div>

                  <div className="row row-wrap gap-8 mt-12">
                    <Button onClick={handleWorkforceUpload} disabled={!companyId || !workforceFile || workforceUploading} loading={workforceUploading}>
                      Analizar cartera
                    </Button>
                  </div>

                  {displayedWorkforceImport?.detectedColumns?.length ? (
                    <div className="workforce-detected-columns mt-12">
                      <span className="workforce-detected-columns-label">Columnas detectadas</span>
                      <div className="workforce-detected-columns-list">
                        {displayedWorkforceImport.detectedColumns.map((column: string) => (
                          <span key={`workforce-column-${column}`} className="workforce-detected-chip">
                            {column}
                          </span>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </>
              ) : null}

              {activeImportTab === 'labor-costs' ? (
                <>
                  <span className="workforce-upload-summary-title">
                    <strong>Añadir costes laborales</strong>
                    <small>Opcional</small>
                  </span>
                  <span className="workforce-upload-summary-file" title={activeLaborStatus?.filename || 'Opcional'}>
                    {activeLaborStatus?.filename || 'Opcional'}
                  </span>

                  <div className="workforce-upload-intro workforce-upload-intro-secondary">
                    <span className="workforce-upload-badge workforce-upload-badge-muted">Opcional</span>
                    <p>Añade costes para calcular ratios económicos por gestor.</p>
                  </div>

                  <div className="workforce-upload-grid">
                    <div className="workforce-upload-card">
                      <label className="workforce-upload-label">Archivo</label>
                      <label className="workforce-file-trigger mt-8">
                        <input
                          type="file"
                          accept=".xlsx,.csv"
                          onChange={(event) => setLaborFile(event.target.files?.[0] ?? null)}
                          className="sr-only"
                        />
                        <span className="workforce-file-trigger-button">Seleccionar archivo</span>
                        <span className="workforce-file-trigger-name" title={laborFile ? laborFile.name : 'Sin fichero seleccionado.'}>
                          {laborFile ? laborFile.name : 'Sin fichero seleccionado.'}
                        </span>
                      </label>
                      <div className="workforce-upload-note">XLSX o CSV complementario del equipo.</div>
                    </div>

                    <div className="workforce-upload-card">
                      <label className="workforce-upload-label">Periodo de costes</label>

                      {hasSnapshotReference ? (
                        <>
                          <div className="workforce-period-mode">
                            <button
                              type="button"
                              className={`workforce-period-choice ${useSnapshotPeriod ? 'is-active' : ''}`}
                              onClick={() => setUseSnapshotPeriod(true)}
                            >
                              Usar periodo del snapshot: {snapshotLabel}
                            </button>
                            <button
                              type="button"
                              className={`workforce-period-choice ${useSnapshotPeriod ? '' : 'is-active'}`}
                              onClick={() => {
                                setUseSnapshotPeriod(false)
                                if (!laborMonth) setLaborMonth(parsedWorkforceReference.month)
                                if (!laborYear) setLaborYear(parsedWorkforceReference.year)
                              }}
                            >
                              Elegir otro periodo
                            </button>
                          </div>

                          {useSnapshotPeriod ? (
                            <div className="workforce-upload-note mt-8">
                              Se tomará como referencia el mismo periodo de la cartera seleccionada.
                            </div>
                          ) : (
                            <>
                              <div className="workforce-period-picker">
                                <select value={laborMonth} onChange={(event) => setLaborMonth(event.target.value)}>
                                  <option value="">Mes</option>
                                  {MONTH_OPTIONS.map((option) => (
                                    <option key={`labor-month-${option.value}`} value={option.value}>
                                      {option.label}
                                    </option>
                                  ))}
                                </select>
                                <select value={laborYear} onChange={(event) => setLaborYear(event.target.value)}>
                                  <option value="">Año</option>
                                  {yearOptions.map((year) => (
                                    <option key={`labor-year-${year}`} value={year}>
                                      {year}
                                    </option>
                                  ))}
                                </select>
                              </div>
                              <div className="workforce-upload-note mt-8">El fichero validará la cobertura real al importar.</div>
                            </>
                          )}
                        </>
                      ) : (
                        <>
                          <div className="workforce-period-picker">
                            <select value={laborMonth} onChange={(event) => setLaborMonth(event.target.value)}>
                              <option value="">Mes</option>
                              {MONTH_OPTIONS.map((option) => (
                                <option key={`labor-standalone-month-${option.value}`} value={option.value}>
                                  {option.label}
                                </option>
                              ))}
                            </select>
                            <select value={laborYear} onChange={(event) => setLaborYear(event.target.value)}>
                              <option value="">Año</option>
                              {yearOptions.map((year) => (
                                <option key={`labor-standalone-year-${year}`} value={year}>
                                  {year}
                                </option>
                              ))}
                            </select>
                          </div>
                          <div className="workforce-upload-note mt-8">Periodo esperado del fichero. El backend validará la cobertura real.</div>
                        </>
                      )}

                      <div className="workforce-upload-helper">Periodo objetivo: {laborTargetPeriodLabel}</div>
                      {laborContextMessage ? <div className="workforce-upload-warning">{laborContextMessage}</div> : null}
                    </div>

                    <div className="workforce-upload-card">
                      <label className="workforce-upload-label">Estado</label>
                      <strong className={`workforce-upload-status ${activeLaborStatus?.filename ? 'is-success' : 'is-muted'}`}>
                        {activeLaborStatus?.filename ? 'Activo' : 'Sin cargar'}
                      </strong>
                      <div
                        className="workforce-upload-note mt-8"
                        title={activeLaborStatus?.filename || 'Activa el análisis económico por gestor.'}
                      >
                        {activeLaborStatus?.filename || 'Activa el análisis económico por gestor.'}
                      </div>
                      <div className="workforce-upload-helper">
                        {activeLaborStatus?.referenceLabel
                          ? `Cobertura detectada: ${laborStatusLabel}`
                          : 'Costes laborales no disponibles. Carga un fichero para activar este análisis.'}
                      </div>
                    </div>
                  </div>

                  <div className="row row-wrap gap-8 mt-12">
                    <Button onClick={handleLaborUpload} disabled={!companyId || !laborFile || laborUploading} loading={laborUploading}>
                      Importar costes
                    </Button>
                  </div>

                  {activeLaborStatus?.detectedColumns?.length ? (
                    <div className="workforce-detected-columns mt-12">
                      <span className="workforce-detected-columns-label">Columnas detectadas</span>
                      <div className="workforce-detected-columns-list">
                        {activeLaborStatus.detectedColumns.map((column: string) => (
                          <span key={`labor-column-${column}`} className="workforce-detected-chip">
                            {column}
                          </span>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </>
              ) : null}
            </div>
          </div>
        </div>
      </section>

      {workforceImport || workforceImports.length || laborCostImports.length ? (
        <section className="workforce-section">
          <div className="workforce-section-head workforce-section-head-inline">
            <div>
              <span>Contexto temporal</span>
              <h2>{workforceImport ? `Snapshot analizado · ${snapshotLabel}` : 'Snapshot analizado'}</h2>
            </div>
            <div className="workforce-section-caption">Base activa, costes asociados e histórico listo para cambiar de foco.</div>
          </div>

          <div className="workforce-timeline-shell">
            <div className="workforce-period-context">
              <div className="workforce-period-pill workforce-period-pill-primary">
                <div className="workforce-period-pill-head">
                  <span>Workforce</span>
                  <span className="workforce-mini-status is-success">{workforceTimelineStateLabel}</span>
                </div>
                <strong>{snapshotLabel}</strong>
                <small>{workforceImport?.filename || 'Sin snapshot seleccionado'}</small>
              </div>
              <div className="workforce-period-pill">
                <div className="workforce-period-pill-head">
                  <span>Costes</span>
                  <span className={`workforce-mini-status ${effectiveLaborTimelineStateTone}`}>{effectiveLaborTimelineStateLabel}</span>
                </div>
                <strong>{economicLaborLabel}</strong>
                <small>{laborCostsImport?.filename || 'Sin costes activos'}</small>
              </div>
            </div>

            <div className="workforce-context-band">
              <div className="workforce-context-stat">
                <span>Snapshot activo</span>
                <strong>{snapshotLabel}</strong>
                <small>{displayedWorkforceImport?.createdAt ? formatDateTime(displayedWorkforceImport.createdAt) : 'Sin fecha disponible'}</small>
              </div>
              <div className="workforce-context-stat">
                <span>Cruce coste/actividad</span>
                <strong>{effectivePairingSummaryLabel}</strong>
                <small>{laborContextMessage || 'Los ratios mixtos solo se activan cuando Workforce y costes comparten periodo compatible.'}</small>
              </div>
              <div className="workforce-context-stat">
                <span>Histórico Workforce</span>
                <strong>{formatInteger(workforceImports.length)}</strong>
                <small>{workforceImports.length === 1 ? '1 carga disponible' : `${formatInteger(workforceImports.length)} cargas disponibles`}</small>
              </div>
              <div className="workforce-context-stat">
                <span>Histórico costes</span>
                <strong>{formatInteger(laborCostImports.length)}</strong>
                <small>{laborCostImports.length === 1 ? '1 carga disponible' : `${formatInteger(laborCostImports.length)} cargas disponibles`}</small>
              </div>
            </div>

            {analysisPeriodOptions.length ? (
              <div className="workforce-table-card workforce-compare-panel workforce-compare-shell mt-16">
                <div className="workforce-compare-summary-bar">
                  <div className="workforce-compare-summary-copy">
                    <span className="workforce-upload-badge workforce-upload-badge-muted">Ventana de analisis</span>
                    <strong>Compara cualquier Periodo A contra Periodo B.</strong>
                    <small>La vista prioriza los meses elegidos al importar, no el orden tecnico de las cargas.</small>
                  </div>
                  <div className="workforce-compare-summary-status">
                    <strong className={`workforce-upload-status ${hasRequestedComparisonWindow ? 'is-success' : 'is-muted'}`}>
                      {hasRequestedComparisonWindow ? 'Activa' : 'Pendiente'}
                    </strong>
                    <span>{hasRequestedComparisonWindow ? `${comparisonBaseLabel} vs ${comparisonSelectedLabel}` : 'Selecciona dos periodos'}</span>
                    <small>
                      {hasRequestedComparisonWindow
                        ? 'La evolucion operativa y economica se recalcula con esta pareja de periodos.'
                        : 'Cuando elijas Periodo A y Periodo B, la pagina se reordena sobre esa comparacion.'}
                    </small>
                  </div>
                </div>

                <div className="workforce-upload-grid workforce-compare-form-grid">
                  <div className="workforce-upload-card">
                    <label className="workforce-upload-label">Periodo A</label>
                    <div className="workforce-period-picker mt-8">
                      <select
                        value={comparisonBasePeriod || ''}
                        onChange={(event) => {
                          const parsed = parseReferencePeriod(event.target.value)
                          setComparisonBaseYear(parsed.year)
                          setComparisonBaseMonth(parsed.month)
                        }}
                      >
                        <option value="">Selecciona periodo</option>
                        {analysisPeriodOptions.map((option: any) => (
                          <option key={`analysis-base-${option.period}`} value={option.period}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div className="workforce-upload-helper">
                      {analysisBaseOption
                        ? `${analysisBaseOption.hasWorkforce ? 'Workforce OK' : 'Sin Workforce'} · ${analysisBaseOption.hasCosts ? 'Costes OK' : 'Sin costes'}`
                        : 'Selecciona el primer periodo a estudiar.'}
                    </div>
                  </div>

                  <div className="workforce-upload-card">
                    <label className="workforce-upload-label">Periodo B</label>
                    <div className="workforce-period-picker mt-8">
                      <select
                        value={comparisonPeriod || ''}
                        onChange={(event) => {
                          const parsed = parseReferencePeriod(event.target.value)
                          setComparisonYear(parsed.year)
                          setComparisonMonth(parsed.month)
                        }}
                      >
                        <option value="">Selecciona periodo</option>
                        {analysisPeriodOptions.map((option: any) => (
                          <option key={`analysis-compare-${option.period}`} value={option.period}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div className="workforce-upload-helper">
                      {analysisComparisonOption
                        ? `${analysisComparisonOption.hasWorkforce ? 'Workforce OK' : 'Sin Workforce'} · ${analysisComparisonOption.hasCosts ? 'Costes OK' : 'Sin costes'}`
                        : 'Selecciona el segundo periodo a estudiar.'}
                    </div>
                  </div>

                  <div className="workforce-upload-card">
                    <label className="workforce-upload-label">Cobertura de la comparacion</label>
                    <strong className={`workforce-upload-status ${workforceWindowReady || hasLaborHistorySelection ? 'is-success' : 'is-pending'}`}>
                      {workforceWindowReady || hasLaborHistorySelection ? 'Lista' : 'Parcial'}
                    </strong>
                    <div className="workforce-upload-note mt-8">
                      Operativo {workforceWindowReady ? 'disponible' : 'no disponible'} · Economico {laborWindowReady ? 'disponible' : 'no disponible'}
                    </div>
                    <div className="workforce-upload-helper">
                      Si un periodo no tiene Workforce o LABOR_COSTS, esa parte se oculta pero la otra sigue funcionando.
                    </div>
                  </div>
                </div>
              </div>
            ) : null}
          </div>

          <div className="workforce-history-layout">
            <div className="workforce-table-card workforce-history-card">
              <div className="workforce-table-panel-head workforce-table-panel-head-rich">
                <div className="workforce-table-panel-copy">
                  <span>Historial Workforce</span>
                  <strong>Snapshots de cartera y actividad</strong>
                  <p className="workforce-table-panel-note">Selecciona una fila para cambiar el snapshot analizado.</p>
                </div>
                <div className="workforce-table-panel-side">
                  <small>{formatInteger(workforceImports.length)} carga(s)</small>
                  <span className="workforce-mini-status is-success">{workforceImport ? `Activa · ${snapshotLabel}` : 'Sin activa'}</span>
                </div>
              </div>
              <div className="workforce-table-wrap workforce-table-wrap-compact">
                <table className="table workforce-table workforce-import-table">
                  <thead>
                    <tr>
                      <th>Periodo</th>
                      <th>Fecha de carga</th>
                      <th>Archivo</th>
                      <th>Estado</th>
                    </tr>
                  </thead>
                  <tbody>
                    {workforceImports.map((item: any) => (
                      <tr
                        key={`workforce-import-${item.id}`}
                        className={item.id === workforceImport?.id ? 'workforce-table-row-active' : ''}
                        onClick={() => setSelectedWorkforceImportId(item.id)}
                      >
                        <td>
                          <div className="workforce-history-cell">
                            <strong>{item.referenceLabel}</strong>
                            {item.id === workforceImport?.id ? <span className="workforce-history-inline-tag">Activa</span> : null}
                          </div>
                        </td>
                        <td>{formatDateTime(item.createdAt)}</td>
                        <td className="workforce-import-file-cell">{item.filename}</td>
                        <td>
                          <span className={`workforce-mini-status ${importStatusTone(item.status)}`}>{formatImportStatusLabel(item.status)}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="workforce-table-card workforce-history-card">
              <div className="workforce-table-panel-head workforce-table-panel-head-rich">
                <div className="workforce-table-panel-copy">
                  <span>Historial costes</span>
                  <strong>Ficheros laborales vinculables</strong>
                  <p className="workforce-table-panel-note">Muestra el periodo cargado y cuál queda realmente asociado al snapshot actual.</p>
                </div>
                <div className="workforce-table-panel-side">
                  <small>{formatInteger(laborCostImports.length)} carga(s)</small>
                  <span className={`workforce-mini-status ${effectiveLaborTimelineStateTone}`}>{effectiveLaborTimelineStateLabel}</span>
                </div>
              </div>
              <div className="workforce-table-wrap workforce-table-wrap-compact">
                <table className="table workforce-table workforce-import-table">
                  <thead>
                    <tr>
                      <th>Cobertura</th>
                      <th>Fecha de carga</th>
                      <th>Archivo</th>
                      <th>Estado</th>
                    </tr>
                  </thead>
                  <tbody>
                    {laborCostImports.map((item: any) => (
                      <tr key={`labor-import-${item.id}`} className={item.id === laborCostsImport?.id ? 'workforce-table-row-active' : ''}>
                        <td>
                          <div className="workforce-history-cell">
                            <strong>{item.referenceLabel}</strong>
                            {item.id === laborCostsImport?.id ? <span className="workforce-history-inline-tag">Activa</span> : null}
                            {item.id === pairedLaborCostsImport?.id && item.id !== laborCostsImport?.id ? <span className="workforce-history-inline-tag">Compatible</span> : null}
                          </div>
                        </td>
                        <td>{formatDateTime(item.createdAt)}</td>
                        <td className="workforce-import-file-cell">{item.filename}</td>
                        <td>
                          <span className={`workforce-mini-status ${importStatusTone(item.status)}`}>{formatImportStatusLabel(item.status)}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </section>
      ) : null}

      <section className="workforce-section workforce-compare-section">
        <div className="workforce-section-head workforce-section-head-inline">
          <div>
            <span>Detalle economico</span>
            <h2>Costes entre Periodo A y Periodo B</h2>
          </div>
          <Button type="button" variant={showComparisonPanel ? 'secondary' : 'ghost'} size="sm" onClick={() => setShowComparisonPanel((current) => !current)}>
            {showComparisonPanel ? 'Ocultar detalle' : comparisonReady ? 'Ver detalle' : 'Abrir detalle'}
          </Button>
        </div>

        <div className="workforce-table-card workforce-compare-panel workforce-compare-shell">
          <div className="workforce-compare-summary-bar">
            <div className="workforce-compare-summary-copy">
              <span className="workforce-upload-badge workforce-upload-badge-muted">Avanzado</span>
              <strong>Compara dos periodos sin duplicar cargas.</strong>
              <small>Reutiliza costes activos y sube solo el fichero del periodo que falte o quieras sustituir.</small>
            </div>
            <div className="workforce-compare-summary-status">
              <strong className={`workforce-upload-status ${comparisonStatusClass}`}>{comparisonStatusLabel}</strong>
              <span>{comparisonPeriodsSummary}</span>
              <small>{comparisonSummaryNote}</small>
            </div>
          </div>

          {comparisonReady && !showComparisonPanel ? (
            <div className="workforce-kpi-strip workforce-kpi-strip-compare workforce-compare-kpis-compact">
              <WorkforceKpiCard
                label="Coste base"
                value={formatCurrencyByCode(comparisonData?.totals?.baseCosteTotal, comparisonData?.currency)}
                detail={comparisonBaseLabel}
                tone="accent"
              />
              <WorkforceKpiCard
                label="Coste comparado"
                value={formatCurrencyByCode(comparisonData?.totals?.comparisonCosteTotal, comparisonData?.currency)}
                detail={comparisonSelectedLabel}
              />
              <WorkforceKpiCard
                label="Variación"
                value={formatCurrencyByCode(comparisonData?.totals?.deltaCosteTotal, comparisonData?.currency)}
                detail={formatComparisonDeltaPercent(comparisonData?.totals?.deltaPctCosteTotal, comparisonData?.totals?.deltaPctCosteTotalState)}
                tone="teal"
              />
              <WorkforceKpiCard
                label="Total reconciliado"
                value={formatCurrencyByCode(comparisonData?.totals?.reconciledComparisonCosteTotal, comparisonData?.currency)}
                detail={`Base ${formatCurrencyByCode(comparisonData?.totals?.reconciledBaseCosteTotal, comparisonData?.currency)}`}
              />
            </div>
          ) : null}

          {showComparisonPanel ? (
            <div className="workforce-compare-detail">
              {comparisonError ? <Alert tone="danger">{comparisonError}</Alert> : null}
              {comparisonSuccess ? <Alert tone="success">{comparisonSuccess}</Alert> : null}
              {workforceComparisonQuery.error ? <Alert tone="danger">{String(workforceComparisonQuery.error?.message || 'No se pudo cargar la comparación de costes.')}</Alert> : null}

              <div className="workforce-block-head workforce-block-head-compact">
                <span>Configuracion</span>
                <h3>Analisis economico entre dos periodos</h3>
                <p className="workforce-compare-lead">Selecciona Periodo A y Periodo B. Si ya existe una importacion activa, se reutiliza por defecto.</p>
              </div>

              <div className="workforce-upload-grid workforce-compare-form-grid">
                <div className="workforce-upload-card">
                  <label className="workforce-upload-label">Periodo A</label>
                  <div className="workforce-period-picker mt-8">
                    <select value={comparisonBaseMonth} onChange={(event) => setComparisonBaseMonth(event.target.value)}>
                      <option value="">Mes</option>
                      {MONTH_OPTIONS.map((option) => (
                        <option key={`comparison-base-month-${option.value}`} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    <select value={comparisonBaseYear} onChange={(event) => setComparisonBaseYear(event.target.value)}>
                      <option value="">Año</option>
                      {yearOptions.map((year) => (
                        <option key={`comparison-base-year-${year}`} value={year}>
                          {year}
                        </option>
                      ))}
                    </select>
                  </div>

                  {hasStoredBaseComparisonPeriod && !showBaseComparisonUpload ? (
                    <>
                      <strong className="workforce-upload-status is-success mt-8">Importación activa</strong>
                      <div className="workforce-upload-note mt-8" title={storedBaseComparisonImport?.filename || comparisonBaseLabel}>
                        {storedBaseComparisonImport?.filename || comparisonBaseLabel}
                      </div>
                      <div className="workforce-upload-helper">Se reutiliza {comparisonBaseLabel} sin volver a cargar fichero.</div>
                      <div className="workforce-compare-inline-actions">
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          onClick={() => setReplaceComparisonBaseImport(true)}
                        >
                          Subir otro fichero
                        </Button>
                      </div>
                    </>
                  ) : comparisonBasePeriod ? (
                    <>
                      <label className="workforce-file-trigger mt-8">
                        <input
                          type="file"
                          accept=".xlsx,.csv"
                          onChange={(event) => setComparisonBaseFile(event.target.files?.[0] ?? null)}
                          className="sr-only"
                        />
                        <span className="workforce-file-trigger-button">Fichero base</span>
                        <span className="workforce-file-trigger-name" title={comparisonBaseFile ? comparisonBaseFile.name : 'Sin fichero seleccionado.'}>
                          {comparisonBaseFile ? comparisonBaseFile.name : 'Sin fichero seleccionado.'}
                        </span>
                      </label>
                      <div className="workforce-upload-helper">
                        {hasStoredBaseComparisonPeriod
                          ? `Puedes sustituir la importacion activa de ${comparisonBaseLabel} para esta comparacion.`
                          : 'Carga el fichero de Periodo A solo si ese mes todavia no existe en el sistema.'}
                      </div>
                      {hasStoredBaseComparisonPeriod ? (
                        <div className="workforce-compare-inline-actions">
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => {
                              setReplaceComparisonBaseImport(false)
                              setComparisonBaseFile(null)
                            }}
                          >
                            Usar activa
                          </Button>
                        </div>
                      ) : null}
                    </>
                  ) : (
                    <div className="workforce-upload-helper">Selecciona primero el Periodo A.</div>
                  )}
                </div>

                <div className="workforce-upload-card">
                  <label className="workforce-upload-label">Periodo B</label>
                  <div className="workforce-period-picker mt-8">
                    <select value={comparisonMonth} onChange={(event) => setComparisonMonth(event.target.value)}>
                      <option value="">Mes</option>
                      {MONTH_OPTIONS.map((option) => (
                        <option key={`comparison-month-${option.value}`} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    <select value={comparisonYear} onChange={(event) => setComparisonYear(event.target.value)}>
                      <option value="">Año</option>
                      {yearOptions.map((year) => (
                        <option key={`comparison-year-${year}`} value={year}>
                          {year}
                        </option>
                      ))}
                    </select>
                  </div>

                  {hasStoredComparisonPeriod && !showComparisonUpload ? (
                    <>
                      <strong className="workforce-upload-status is-success mt-8">Importación activa</strong>
                      <div className="workforce-upload-note mt-8" title={storedComparisonImport?.filename || comparisonSelectedLabel}>
                        {storedComparisonImport?.filename || comparisonSelectedLabel}
                      </div>
                      <div className="workforce-upload-helper">Se reutiliza {comparisonSelectedLabel} sin volver a cargar fichero.</div>
                      <div className="workforce-compare-inline-actions">
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          onClick={() => setReplaceComparisonImport(true)}
                        >
                          Subir otro fichero
                        </Button>
                      </div>
                    </>
                  ) : comparisonPeriod ? (
                    <>
                      <label className="workforce-file-trigger mt-8">
                        <input
                          type="file"
                          accept=".xlsx,.csv"
                          onChange={(event) => setComparisonFile(event.target.files?.[0] ?? null)}
                          className="sr-only"
                        />
                        <span className="workforce-file-trigger-button">Fichero comparado</span>
                        <span className="workforce-file-trigger-name" title={comparisonFile ? comparisonFile.name : 'Sin fichero seleccionado.'}>
                          {comparisonFile ? comparisonFile.name : 'Sin fichero seleccionado.'}
                        </span>
                      </label>
                      <div className="workforce-upload-helper">
                        {hasStoredComparisonPeriod
                          ? `Puedes sustituir la importacion activa de ${comparisonSelectedLabel} para esta comparacion.`
                          : 'Carga el fichero de Periodo B solo si ese mes todavia no existe en el sistema.'}
                      </div>
                      {hasStoredComparisonPeriod ? (
                        <div className="workforce-compare-inline-actions">
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => {
                              setReplaceComparisonImport(false)
                              setComparisonFile(null)
                            }}
                          >
                            Usar activa
                          </Button>
                        </div>
                      ) : null}
                    </>
                  ) : (
                    <div className="workforce-upload-helper">Selecciona el Periodo B.</div>
                  )}
                </div>

                <div className="workforce-upload-card">
                  <label className="workforce-upload-label">Estado de la comparacion</label>
                  <strong className={`workforce-upload-status ${comparisonReady ? 'is-success' : 'is-pending'}`}>{comparisonReady ? 'Lista' : 'Pendiente'}</strong>
                  <div className="workforce-upload-note mt-8">{comparisonPeriodsSummary}</div>
                  <div className="workforce-upload-helper">
                    {comparisonData?.message || 'La comparacion se calcula desde importaciones persistidas por periodo.'}
                  </div>
                  {comparisonReusedPeriods ? (
                    <div className="workforce-upload-helper">
                      Reutilizando {comparisonReusedPeriods} periodo(s) activo(s) y solicitando fichero solo cuando hace falta.
                    </div>
                  ) : null}
                  {comparisonBasePeriod && comparisonPeriod && comparisonBasePeriod === comparisonPeriod ? (
                    <div className="workforce-upload-warning">Periodo A y Periodo B deben ser distintos.</div>
                  ) : null}
                </div>
              </div>

              <div className="row row-wrap gap-8">
                <Button onClick={handleImportAndCompare} disabled={!canRunComparison || comparisonLoading} loading={comparisonLoading}>
                  Importar y comparar periodos
                </Button>
              </div>

              {comparisonBasePeriod && comparisonPeriod ? (
                comparisonReady ? (
                  <>
                    <div className="workforce-kpi-strip workforce-kpi-strip-compare">
                      <WorkforceKpiCard
                        label="Coste base"
                        value={formatCurrencyByCode(comparisonData?.totals?.baseCosteTotal, comparisonData?.currency)}
                        detail={comparisonBaseLabel}
                        tone="accent"
                      />
                      <WorkforceKpiCard
                        label="Coste comparado"
                        value={formatCurrencyByCode(comparisonData?.totals?.comparisonCosteTotal, comparisonData?.currency)}
                        detail={comparisonSelectedLabel}
                      />
                      <WorkforceKpiCard
                        label="Variación"
                        value={formatCurrencyByCode(comparisonData?.totals?.deltaCosteTotal, comparisonData?.currency)}
                        detail={formatComparisonDeltaPercent(comparisonData?.totals?.deltaPctCosteTotal, comparisonData?.totals?.deltaPctCosteTotalState)}
                        tone="teal"
                      />
                      <WorkforceKpiCard
                        label="Total reconciliado"
                        value={formatCurrencyByCode(comparisonData?.totals?.reconciledComparisonCosteTotal, comparisonData?.currency)}
                        detail={`Base ${formatCurrencyByCode(comparisonData?.totals?.reconciledBaseCosteTotal, comparisonData?.currency)}`}
                      />
                    </div>

                    <div className="workforce-kpi-strip workforce-kpi-strip-compare">
                      <div className="workforce-delta-card workforce-delta-card-neutral">
                        <span>Comparables</span>
                        <strong>{formatInteger(comparisonData?.counts?.comparableWorkers)}</strong>
                        <small>Trabajadores conciliados en ambos periodos</small>
                      </div>
                      <div className="workforce-delta-card workforce-delta-card-up">
                        <span>Solo comparado</span>
                        <strong>{formatInteger(comparisonData?.counts?.onlyComparisonWorkers)}</strong>
                        <small>Alta neta sobre periodos válidos</small>
                      </div>
                      <div className="workforce-delta-card workforce-delta-card-down">
                        <span>Solo base</span>
                        <strong>{formatInteger(comparisonData?.counts?.onlyBaseWorkers)}</strong>
                        <small>Baja neta sobre periodos válidos</small>
                      </div>
                      <div className="workforce-delta-card workforce-delta-card-neutral">
                        <span>Revisión / no match</span>
                        <strong>{formatInteger(comparisonData?.counts?.reviewWorkers)}</strong>
                        <small>{formatInteger(comparisonData?.counts?.unmatchedWorkers)} sin correspondencia</small>
                      </div>
                    </div>

                    <div className="workforce-cost-layout workforce-compare-results">
                      <div className="workforce-table-card">
                        <div className="workforce-table-panel-head">
                          <div className="workforce-table-panel-copy">
                            <span>Por gestor</span>
                            <strong>Coste agregado y variación</strong>
                          </div>
                          <small>{formatInteger(comparisonData?.gestores?.length || 0)} gestor(es)</small>
                        </div>
                        <div className="workforce-table-wrap workforce-table-wrap-costs">
                          <table className="table workforce-table workforce-cost-table">
                            <thead>
                              <tr>
                                <th>Gestor</th>
                                <th>Base</th>
                                <th>Comparado</th>
                                <th>Var. abs.</th>
                                <th>Var. %</th>
                                <th>Trab. base</th>
                                <th>Trab. comp.</th>
                              </tr>
                            </thead>
                            <tbody>
                              {(comparisonData?.gestores || []).map((row: any) => (
                                <tr key={`comparison-manager-${row.gestor}`}>
                                  <td className="workforce-cell-strong">{row.gestor}</td>
                                  <td>{formatCurrencyByCode(row.baseCosteTotal, comparisonData?.currency)}</td>
                                  <td>{formatCurrencyByCode(row.comparisonCosteTotal, comparisonData?.currency)}</td>
                                  <td>{formatCurrencyByCode(row.deltaCosteTotal, comparisonData?.currency)}</td>
                                  <td>{formatComparisonDeltaPercent(row.deltaPctCosteTotal, row.deltaPctCosteTotalState)}</td>
                                  <td>{formatInteger(row.baseWorkers)}</td>
                                  <td>{formatInteger(row.comparisonWorkers)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>

                      <div className="workforce-table-card">
                        <div className="workforce-table-panel-head">
                          <div className="workforce-table-panel-copy">
                            <span>Por trabajador</span>
                            <strong>Comparables entre ambos periodos</strong>
                          </div>
                          <small>{formatInteger(comparisonData?.comparableWorkers?.length || 0)} fila(s)</small>
                        </div>
                        <div className="workforce-table-wrap workforce-table-wrap-costs">
                          <table className="table workforce-table workforce-cost-activity-table">
                            <thead>
                              <tr>
                                <th>Trabajador</th>
                                <th>Gestor base</th>
                                <th>Gestor comp.</th>
                                <th>Base</th>
                                <th>Comparado</th>
                                <th>Var. abs.</th>
                                <th>Var. %</th>
                              </tr>
                            </thead>
                            <tbody>
                              {(comparisonData?.comparableWorkers || []).map((row: any) => (
                                <tr key={`comparison-worker-${row.canonicalWorkerId || row.workerLabel}`}>
                                  <td className="workforce-cell-strong">{row.workerLabel}</td>
                                  <td>{row.baseGestor || EMPTY_VALUE}</td>
                                  <td>{row.comparisonGestor || EMPTY_VALUE}</td>
                                  <td>{formatCurrencyByCode(row.baseCosteTotal, comparisonData?.currency)}</td>
                                  <td>{formatCurrencyByCode(row.comparisonCosteTotal, comparisonData?.currency)}</td>
                                  <td>{formatCurrencyByCode(row.deltaCosteTotal, comparisonData?.currency)}</td>
                                  <td>{formatComparisonDeltaPercent(row.deltaPctCosteTotal, row.deltaPctCosteTotalState)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    </div>

                    <div className="workforce-relation-grid workforce-compare-status-grid">
                      <div className="workforce-table-card">
                        <div className="workforce-table-panel-head">
                          <div className="workforce-table-panel-copy">
                            <span>Solo base</span>
                            <strong>Presentes solo en el periodo base</strong>
                          </div>
                          <small>{formatInteger(comparisonData?.onlyBaseWorkers?.length || 0)}</small>
                        </div>
                        <div className="workforce-table-wrap workforce-table-wrap-compact">
                          <table className="table workforce-table workforce-import-table">
                            <thead>
                              <tr>
                                <th>Trabajador</th>
                                <th>Gestor</th>
                                <th>Coste</th>
                              </tr>
                            </thead>
                            <tbody>
                              {(comparisonData?.onlyBaseWorkers || []).map((row: any) => (
                                <tr key={`comparison-base-only-${row.canonicalWorkerId || row.workerLabel}`}>
                                  <td>{row.workerLabel}</td>
                                  <td>{row.baseGestor || EMPTY_VALUE}</td>
                                  <td>{formatCurrencyByCode(row.baseCosteTotal, comparisonData?.currency)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>

                      <div className="workforce-table-card">
                        <div className="workforce-table-panel-head">
                          <div className="workforce-table-panel-copy">
                            <span>Solo comparado</span>
                            <strong>Presentes solo en el periodo comparado</strong>
                          </div>
                          <small>{formatInteger(comparisonData?.onlyComparisonWorkers?.length || 0)}</small>
                        </div>
                        <div className="workforce-table-wrap workforce-table-wrap-compact">
                          <table className="table workforce-table workforce-import-table">
                            <thead>
                              <tr>
                                <th>Trabajador</th>
                                <th>Gestor</th>
                                <th>Coste</th>
                              </tr>
                            </thead>
                            <tbody>
                              {(comparisonData?.onlyComparisonWorkers || []).map((row: any) => (
                                <tr key={`comparison-only-${row.canonicalWorkerId || row.workerLabel}`}>
                                  <td>{row.workerLabel}</td>
                                  <td>{row.comparisonGestor || EMPTY_VALUE}</td>
                                  <td>{formatCurrencyByCode(row.comparisonCosteTotal, comparisonData?.currency)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    </div>

                    <div className="workforce-table-card">
                      <div className="workforce-table-panel-head">
                        <div className="workforce-table-panel-copy">
                          <span>Estado de conciliación</span>
                          <strong>Revisión manual y no correspondencias</strong>
                        </div>
                        <small>{formatInteger(comparisonData?.reviewWorkers?.length || 0)} caso(s)</small>
                      </div>
                      <div className="workforce-table-wrap workforce-table-wrap-costs">
                        <table className="table workforce-table workforce-cost-activity-table">
                          <thead>
                            <tr>
                              <th>Trabajador</th>
                              <th>Estado base</th>
                              <th>Estado comp.</th>
                              <th>Base</th>
                              <th>Comparado</th>
                              <th>Detalle</th>
                            </tr>
                          </thead>
                          <tbody>
                            {(comparisonData?.reviewWorkers || []).map((row: any) => (
                              <tr key={`comparison-review-${row.canonicalWorkerId || row.workerLabel}-${row.comparisonState}`}>
                                <td className="workforce-cell-strong">{row.workerLabel}</td>
                                <td>{row.baseMatchingState || EMPTY_VALUE}</td>
                                <td>{row.comparisonMatchingState || EMPTY_VALUE}</td>
                                <td>{formatCurrencyByCode(row.baseCosteTotal, comparisonData?.currency)}</td>
                                <td>{formatCurrencyByCode(row.comparisonCosteTotal, comparisonData?.currency)}</td>
                                <td>{row.detail || EMPTY_VALUE}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  </>
                ) : (
                  <div className="workforce-empty-premium">{comparisonData?.message || 'Importa o selecciona dos periodos distintos para activar la comparación.'}</div>
                )
              ) : (
                <div className="workforce-empty-premium">Selecciona un periodo base y otro comparado para activar la lectura de variación.</div>
              )}
            </div>
          ) : null}
        </div>
      </section>

      {hasGestores ? (
        <>
          <section className="workforce-section">
            <div className="workforce-section-head">
              <div>
                <span>Resumen</span>
                <h2>Lectura ejecutiva</h2>
              </div>
              <SectionChips
                items={[
                  `${formatInteger(kpis?.totalGestores)} gestores`,
                  `${formatInteger(kpis?.activeClients)} activos`,
                  laborCosts ? 'Costes activos' : 'Sin costes',
                ]}
              />
            </div>

            <div className="workforce-kpi-strip workforce-kpi-strip-summary">
              <WorkforceKpiCard
                label="Clientes totales"
                value={formatInteger(kpis?.totalClients)}
                detail={`Activos ${formatInteger(kpis?.activeClients)} · Bajas ${formatInteger(kpis?.inactiveClients)}`}
                tone="accent"
              />
              <WorkforceKpiCard label="Minutos" value={formatNumber(kpis?.totalMinutas)} detail="Asignación agregada" />
              <WorkforceKpiCard label="Carga total" value={formatNumber(kpis?.totalCarga)} detail="Carga acumulada" />
              <WorkforceKpiCard label="Volumen de asientos" value={formatNumber(kpis?.totalVolumenAsientos)} detail="Promedio agregado" />
              {laborCosts ? (
                <WorkforceKpiCard
                  label="Coste laboral total"
                  value={formatCurrency(laborCosts.totalCosteLaboralAnual)}
                  detail={costScopeLabel}
                  tone="teal"
                />
              ) : null}
            </div>

            <div className="workforce-executive-grid">
              <div className="workforce-executive-card">
                <div className="workforce-block-head">
                  <span>Lectura ejecutiva</span>
                  <h3>Lo esencial del reparto actual</h3>
                </div>
                <div className="workforce-note-list">
                  {executiveReadings.map((reading: string) => (
                    <div key={reading} className="workforce-note-item">
                      <span />
                      <p>{reading}</p>
                    </div>
                  ))}
                </div>
              </div>

              <div className="workforce-executive-card workforce-executive-card-quiet">
                <div className="workforce-block-head">
                  <span>Contexto</span>
                  <h3>Base actual</h3>
                </div>
                <div className="workforce-context-grid">
                  <div>
                    <span>Gestores</span>
                    <strong>{formatInteger(kpis?.totalGestores)}</strong>
                  </div>
                  <div>
                    <span>Activos</span>
                    <strong>{formatInteger(kpis?.activeClients)}</strong>
                  </div>
                  <div>
                    <span>Bajas</span>
                    <strong>{formatInteger(kpis?.inactiveClients)}</strong>
                  </div>
                  <div>
                    <span>Histórico</span>
                    <strong>
                      {comparableHistoryImports.length === 0
                        ? 'Sin periodos'
                        : comparableHistoryImports.length === 1
                          ? '1 periodo'
                          : `${comparableHistoryImports.length} periodos`}
                    </strong>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section className="workforce-section">
            <div className="workforce-section-head">
              <div>
                <span>Evolución</span>
                <h2>Evolución entre periodos</h2>
              </div>
              <SectionChips
                items={[
                  `${formatInteger(comparableHistoryImports.length)} periodos`,
                  historyComparisonLabel,
                  workforceWindowReady ? 'Delta disponible' : 'Sin comparativa',
                  historyUnknownPeriods ? `${formatInteger(historyUnknownPeriods)} sin periodo` : null,
                ]}
              />
            </div>

            {!workforceWindowReady ? (
              <div className="workforce-empty-premium">
                {hasRequestedComparisonWindow
                  ? `No hay dos snapshots Workforce comparables para ${comparisonBaseLabel} vs ${comparisonSelectedLabel}.`
                  : historyUnknownPeriods
                    ? 'Necesitas dos snapshots Workforce con periodo conocido. Ahora mismo hay cargas sin mes o año y no se pueden comparar como junio vs julio.'
                    : 'Sube una segunda carga de Workforce con su periodo para activar la evolución entre meses.'}
              </div>
            ) : (
              <>
                <div className="workforce-kpi-strip workforce-kpi-strip-history">
                  <div className={`workforce-delta-card workforce-delta-card-${getDeltaTone(historyDeltas.clients?.delta)}`}>
                    <span>Clientes</span>
                    <strong>{historyDeltas.clients ? `${historyDeltas.clients.delta > 0 ? '+' : ''}${formatInteger(historyDeltas.clients.delta)}` : EMPTY_VALUE}</strong>
                    <small>vs carga anterior</small>
                  </div>
                  <div className={`workforce-delta-card workforce-delta-card-${getDeltaTone(historyDeltas.minutes?.delta)}`}>
                    <span>Minutos</span>
                    <strong>{historyDeltas.minutes ? `${historyDeltas.minutes.delta > 0 ? '+' : ''}${formatNumber(historyDeltas.minutes.delta)}` : EMPTY_VALUE}</strong>
                    <small>vs carga anterior</small>
                  </div>
                  <div className={`workforce-delta-card workforce-delta-card-${getDeltaTone(historyDeltas.carga?.delta)}`}>
                    <span>Carga</span>
                    <strong>{historyDeltas.carga ? `${historyDeltas.carga.delta > 0 ? '+' : ''}${formatNumber(historyDeltas.carga.delta)}` : EMPTY_VALUE}</strong>
                    <small>vs carga anterior</small>
                  </div>
                  <div className={`workforce-delta-card workforce-delta-card-${getDeltaTone(historyDeltas.asientos?.delta)}`}>
                    <span>Asientos</span>
                    <strong>{historyDeltas.asientos ? `${historyDeltas.asientos.delta > 0 ? '+' : ''}${formatNumber(historyDeltas.asientos.delta)}` : EMPTY_VALUE}</strong>
                    <small>vs carga anterior</small>
                  </div>
                </div>

                <div className="workforce-chart-grid">
                  <div className="workforce-chart-card">
                    <div className="workforce-block-head">
                      <span>Timeline</span>
                      <h3>Clientes y minutos</h3>
                      <p className="workforce-card-note">Comparativa clara entre los últimos periodos útiles de Workforce.</p>
                    </div>
                    <EChart
                      module="tribunal"
                      actions={false}
                      className="workforce-chart-shell"
                      height={300}
                      option={buildHistoryOption(selectedWorkforceHistoryImports, [
                        { key: 'totalClients', name: 'Clientes' },
                        { key: 'totalMinutas', name: 'Minutos' },
                      ])}
                    />
                  </div>

                  <div className="workforce-chart-card">
                    <div className="workforce-block-head">
                      <span>Timeline</span>
                      <h3>Carga y asientos</h3>
                      <p className="workforce-card-note">Cruza intensidad operativa y volumen contable por periodo comparable.</p>
                    </div>
                    <EChart
                      module="tribunal"
                      actions={false}
                      className="workforce-chart-shell"
                      height={300}
                      option={buildHistoryOption(selectedWorkforceHistoryImports, [
                        { key: 'totalCarga', name: 'Carga total' },
                        { key: 'totalVolumenAsientos', name: 'Volumen de asientos', kind: 'bar' },
                      ])}
                    />
                  </div>
                </div>
              </>
            )}
          </section>

          <section className="workforce-section">
            <div className="workforce-section-head">
              <div>
                <span>Visión por gestor</span>
                <h2>Actividad operativa</h2>
              </div>
              <SectionChips items={[`${formatInteger(gestores.length)} gestores`, 'Click en barras']} />
            </div>

            <div className="workforce-chart-grid">
              <GestorBarCard title="Clientes totales por gestor" eyebrow="Cartera" note="Visión directa de reparto de cartera por gestor." rows={gestores} metricKey="totalClients" kind="integer" activeIndex={activeGestorIndex} onSelect={setSelectedGestor} />
              <GestorBarCard title="Minutos por gestor" eyebrow="Tiempo" note="Carga de dedicación agregada sobre la cartera actual." rows={gestores} metricKey="totalMinutas" activeIndex={activeGestorIndex} onSelect={setSelectedGestor} />
              <GestorBarCard title="Carga por gestor" eyebrow="Carga" note="Lectura acumulada de complejidad operativa por cartera." rows={gestores} metricKey="totalCarga" activeIndex={activeGestorIndex} onSelect={setSelectedGestor} />
              <GestorBarCard title="Asientos por gestor" eyebrow="Volumen" note="Volumen histórico de asientos detectado por responsable." rows={gestores} metricKey="totalVolumenAsientos" activeIndex={activeGestorIndex} onSelect={setSelectedGestor} />
            </div>
          </section>

          <section className="workforce-section">
            <div className="workforce-section-head">
              <div>
                <span>Comparativas</span>
                <h2>Cuadrantes y concentración</h2>
              </div>
              <SectionChips items={[laborCosts ? 'Coste vs actividad' : 'Operativa pura', 'Top 3 concentración']} />
            </div>

            <div className="workforce-kpi-strip workforce-kpi-strip-compare">
              <WorkforceKpiCard
                label="Top 3 clientes"
                value={concentration.clients ? formatPercent(concentration.clients.share) : EMPTY_VALUE}
                detail={concentration.clients ? concentration.clients.leaders.join(', ') : 'Sin datos'}
                tone="accent"
              />
              <WorkforceKpiCard
                label="Top 3 minutos"
                value={concentration.minutes ? formatPercent(concentration.minutes.share) : EMPTY_VALUE}
                detail={concentration.minutes ? concentration.minutes.leaders.join(', ') : 'Sin datos'}
              />
              <WorkforceKpiCard
                label="Top 3 asientos"
                value={concentration.seats ? formatPercent(concentration.seats.share) : EMPTY_VALUE}
                detail={concentration.seats ? concentration.seats.leaders.join(', ') : 'Sin datos'}
              />
              <WorkforceKpiCard
                label={laborCosts ? 'Top 3 coste' : 'Top 3 carga'}
                value={laborCosts ? (concentration.cost ? formatPercent(concentration.cost.share) : EMPTY_VALUE) : concentration.carga ? formatPercent(concentration.carga.share) : EMPTY_VALUE}
                detail={
                  laborCosts
                    ? concentration.cost
                      ? concentration.cost.leaders.join(', ')
                      : 'Sin datos'
                    : concentration.carga
                      ? concentration.carga.leaders.join(', ')
                      : 'Sin datos'
                }
                tone="teal"
              />
            </div>

            <div className="workforce-chart-grid">
              <div className="workforce-chart-card">
                <div className="workforce-block-head">
                  <span>Cuadrante</span>
                  <h3>{laborCosts ? 'Coste laboral vs minutos' : 'Clientes vs carga media'}</h3>
                  <p className="workforce-card-note">
                    {laborCosts ? 'Relaciona coste anual y dedicación para leer intensidad operativa.' : 'Relaciona tamaño de cartera y carga media sin añadir juicio de calidad.'}
                  </p>
                </div>
                {laborCosts ? (
                  hasScatterCostMinutes ? (
                    <EChart
                      module="tribunal"
                      actions={false}
                      className="workforce-chart-shell"
                      height={320}
                      option={buildScatterOption(gestores, {
                        xLabel: 'Minutos',
                        yLabel: 'Coste laboral',
                        xKind: 'number',
                        yKind: 'currency',
                        getX: (row) => row.totalMinutas,
                        getY: (row) => row.costeLaboralAnual,
                      })}
                    />
                  ) : (
                    <div className="workforce-empty-inline">No hay datos suficientes para representar coste laboral vs minutos.</div>
                  )
                ) : hasScatterClientsCarga ? (
                  <EChart
                    module="tribunal"
                    actions={false}
                    className="workforce-chart-shell"
                    height={320}
                    option={buildScatterOption(gestores, {
                      xLabel: 'Clientes',
                      yLabel: 'Carga media',
                      xKind: 'integer',
                      yKind: 'number',
                      getX: (row) => row.totalClients,
                      getY: (row) => row.cargaMedia,
                    })}
                  />
                ) : (
                  <div className="workforce-empty-inline">No hay datos suficientes para representar clientes vs carga media.</div>
                )}
              </div>

              <div className="workforce-chart-card">
                <div className="workforce-block-head">
                  <span>Cuadrante</span>
                  <h3>{laborCosts ? 'Coste laboral vs asientos' : 'Minutos vs asientos'}</h3>
                  <p className="workforce-card-note">
                    {laborCosts ? 'Contrasta coste y volumen contable gestionado por cada gestor.' : 'Mide si el volumen de asientos acompaña al tiempo asignado.'}
                  </p>
                </div>
                {laborCosts ? (
                  hasScatterCostSeats ? (
                    <EChart
                      module="tribunal"
                      actions={false}
                      className="workforce-chart-shell"
                      height={320}
                      option={buildScatterOption(gestores, {
                        xLabel: 'Volumen de asientos',
                        yLabel: 'Coste laboral',
                        xKind: 'number',
                        yKind: 'currency',
                        getX: (row) => row.totalVolumenAsientos,
                        getY: (row) => row.costeLaboralAnual,
                      })}
                    />
                  ) : (
                    <div className="workforce-empty-inline">No hay datos suficientes para representar coste laboral vs asientos.</div>
                  )
                ) : hasScatterMinutesSeats ? (
                  <EChart
                    module="tribunal"
                    actions={false}
                    className="workforce-chart-shell"
                    height={320}
                    option={buildScatterOption(gestores, {
                      xLabel: 'Minutos',
                      yLabel: 'Volumen de asientos',
                      xKind: 'number',
                      yKind: 'number',
                      getX: (row) => row.totalMinutas,
                      getY: (row) => row.totalVolumenAsientos,
                    })}
                  />
                ) : (
                  <div className="workforce-empty-inline">No hay datos suficientes para representar minutos vs asientos.</div>
                )}
              </div>
            </div>
          </section>

          {activeGestor ? (
            <section className="workforce-section">
              <div className="workforce-section-head">
                <div>
                  <span>Drill-down</span>
                  <h2>Detalle por gestor</h2>
                </div>
                <SectionChips
                  items={[
                    formatText(activeGestor.gestor),
                    `${formatInteger(activeGestor.totalClients)} clientes`,
                    activeGestorCostValue != null ? formatCurrency(activeGestorCostValue) : null,
                  ]}
                />
              </div>

              <div className="workforce-gestor-selector">
                {gestores.map((gestor: any) => (
                  <button
                    key={gestor.gestor}
                    type="button"
                    className={`workforce-gestor-pill ${gestor.gestor === activeGestor.gestor ? 'workforce-gestor-pill-active' : ''}`}
                    onClick={() => setSelectedGestor(gestor.gestor)}
                  >
                    {formatText(gestor.gestor)}
                  </button>
                ))}
              </div>

              <div className="workforce-drill-grid">
                <div className="workforce-executive-card">
                  <div className="workforce-block-head">
                    <span>Gestor seleccionado</span>
                    <h3>{formatText(activeGestor.gestor)}</h3>
                  </div>

                  <div className="workforce-drill-kpis">
                    <div>
                      <span>Clientes totales</span>
                      <strong>{formatInteger(activeGestor.totalClients)}</strong>
                    </div>
                    <div>
                      <span>Activos</span>
                      <strong>{formatInteger(activeGestor.activeClients)}</strong>
                    </div>
                    <div>
                      <span>Bajas</span>
                      <strong>{formatInteger(activeGestor.inactiveClients)}</strong>
                    </div>
                    <div>
                      <span>Minutos</span>
                      <strong>{formatNumber(activeGestor.totalMinutas)}</strong>
                    </div>
                    <div>
                      <span>Carga total</span>
                      <strong>{formatNumber(activeGestor.totalCarga)}</strong>
                    </div>
                    <div>
                      <span>Carga media</span>
                      <strong>{formatNumber(activeGestor.cargaMedia)}</strong>
                    </div>
                    <div>
                      <span>Asientos</span>
                      <strong>{formatNumber(activeGestor.totalVolumenAsientos)}</strong>
                    </div>
                    <div>
                      <span>% contabilidad</span>
                      <strong>{formatPercent(activeGestor.pctContabilidadMedio)}</strong>
                    </div>
                    <div>
                      <span>Coste laboral</span>
                      <strong>{formatCurrency(activeGestorCostValue)}</strong>
                    </div>
                  </div>

                  <div className="workforce-status-grid">
                    <div className="workforce-status-card">
                      <h4>CONT/MODELOS</h4>
                      <p>OK {formatInteger(activeGestor.contModelosStates?.ok)}</p>
                      <p>NO {formatInteger(activeGestor.contModelosStates?.no)}</p>
                      <p>PDTE {formatInteger(activeGestor.contModelosStates?.pending)}</p>
                      <p>NEG {formatInteger(activeGestor.contModelosStates?.negative)}</p>
                    </div>
                    <div className="workforce-status-card">
                      <h4>IS/IRPF</h4>
                      <p>OK {formatInteger(activeGestor.isIrpfStates?.ok)}</p>
                      <p>NO {formatInteger(activeGestor.isIrpfStates?.no)}</p>
                      <p>PDTE {formatInteger(activeGestor.isIrpfStates?.pending)}</p>
                      <p>NEG {formatInteger(activeGestor.isIrpfStates?.negative)}</p>
                    </div>
                    <div className="workforce-status-card">
                      <h4>DDCC</h4>
                      <p>OK {formatInteger(activeGestor.ddccStates?.ok)}</p>
                      <p>NO {formatInteger(activeGestor.ddccStates?.no)}</p>
                      <p>PDTE {formatInteger(activeGestor.ddccStates?.pending)}</p>
                      <p>NEG {formatInteger(activeGestor.ddccStates?.negative)}</p>
                    </div>
                    <div className="workforce-status-card">
                      <h4>LIBROS</h4>
                      <p>OK {formatInteger(activeGestor.librosStates?.ok)}</p>
                      <p>NO {formatInteger(activeGestor.librosStates?.no)}</p>
                      <p>PDTE {formatInteger(activeGestor.librosStates?.pending)}</p>
                      <p>NEG {formatInteger(activeGestor.librosStates?.negative)}</p>
                    </div>
                  </div>
                </div>

                <div className="workforce-chart-card">
                  <div className="workforce-block-head">
                    <span>Histórico N AS</span>
                    <h3>Asientos por año</h3>
                    <p className="workforce-card-note">Serie dinámica N AS YYYY del gestor seleccionado.</p>
                  </div>
                  {activeGestorAnnualSeatsEntries.length ? (
                    <EChart module="tribunal" actions={false} className="workforce-chart-shell" height={300} option={buildAnnualSeatsOption(activeGestor.annualSeatTotals)} />
                  ) : (
                    <div className="workforce-empty-inline">No hay histórico anual de asientos para este gestor.</div>
                  )}
                </div>
              </div>

              <div className="workforce-drill-grid">
                <div className="workforce-chart-card">
                  <div className="workforce-block-head">
                    <span>Evolución del gestor</span>
                    <h3>Minutos, carga y asientos</h3>
                    <p className="workforce-card-note">Muestra cómo cambia la cartera del gestor entre snapshots.</p>
                  </div>
                  {activeGestorHistory.length > 1 ? (
                    <EChart module="tribunal" actions={false} className="workforce-chart-shell" height={300} option={buildGestorHistoryOption(activeGestorHistory)} />
                  ) : (
                    <div className="workforce-empty-inline">Necesitas al menos dos snapshots Workforce comparables para ver esta evolución. LABOR_COSTS no aporta minutos, carga ni asientos.</div>
                  )}
                </div>

                <div className="workforce-chart-card">
                  <div className="workforce-block-head">
                    <span>{activeEconomicGestorHistorySeries.length ? 'Coste por periodo' : 'Coste mensual'}</span>
                    <h3>Coste personal vs SS empresa</h3>
                    <p className="workforce-card-note">
                      {activeEconomicGestorHistorySeries.length
                        ? 'Serie del gestor activo usando solo el historico de LABOR_COSTS por periodo.'
                        : 'Desglose mensual del gestor activo en la carga economica disponible.'}
                    </p>
                  </div>
                  {activeEconomicGestorHistorySeries.length ? (
                    <EChart module="tribunal" actions={false} valueSuffix="EUR" className="workforce-chart-shell" height={300} option={buildLaborCostHistoryOption(activeEconomicGestorHistorySeries)} />
                  ) : activeGestorMonthlyCosts.length ? (
                    <EChart module="tribunal" actions={false} valueSuffix="EUR" className="workforce-chart-shell" height={300} option={buildMonthlyCostOption(activeGestorMonthlyCosts)} />
                  ) : (
                    <div className="workforce-empty-inline">No hay serie economica ni desglose mensual de LABOR_COSTS para este gestor.</div>
                  )}
                </div>
              </div>
            </section>
          ) : null}

          <section className="workforce-section">
            <div className="workforce-section-head">
              <div>
                <span>Detalle operativo</span>
                <h2>Tabla por gestor</h2>
              </div>
              <SectionChips items={[`${formatInteger(gestores.length)} gestores`, activeGestor ? `Foco · ${formatText(activeGestor.gestor)}` : null]} />
            </div>

            <div className="workforce-table-card">
              <div className="workforce-table-panel-head workforce-table-panel-head-rich">
                <div className="workforce-table-panel-copy">
                  <span>Tabla por gestor</span>
                  <strong>Vista operativa consolidada</strong>
                  <p className="workforce-table-panel-note">Haz clic en una fila para llevar el drill-down al gestor correspondiente.</p>
                </div>
                <div className="workforce-table-panel-side">
                  <small>{formatInteger(gestores.length)} gestor(es)</small>
                  {activeGestor ? <span className="workforce-mini-status is-success">{formatText(activeGestor.gestor)}</span> : null}
                </div>
              </div>
              <div className="workforce-table-wrap">
                <table className="table workforce-table">
                  <thead>
                    <tr>
                      <th>Gestor</th>
                      <th className="ta-right">Clientes</th>
                      <th className="ta-right">Minutos</th>
                      <th className="ta-right">Carga total</th>
                      <th className="ta-right">Carga media</th>
                      <th className="ta-right">Asientos</th>
                      <th className="ta-right">% contabilidad</th>
                      <th>Servicios</th>
                    </tr>
                  </thead>
                  <tbody>
                    {gestores.map((gestor: any) => (
                      <tr
                        key={`${gestor.gestor}-${gestor.totalClients}`}
                        className={gestor.gestor === activeGestor?.gestor ? 'workforce-table-row-active' : ''}
                        onClick={() => setSelectedGestor(gestor.gestor)}
                      >
                        <td className="workforce-gestor-cell">
                          <div className="workforce-history-cell">
                            <strong>{formatText(gestor.gestor)}</strong>
                            {gestor.gestor === activeGestor?.gestor ? <span className="workforce-history-inline-tag">Detalle</span> : null}
                          </div>
                        </td>
                        <td className="ta-right workforce-cell-strong">{formatInteger(gestor.totalClients)}</td>
                        <td className="ta-right">{formatNumber(gestor.totalMinutas)}</td>
                        <td className="ta-right">{formatNumber(gestor.totalCarga)}</td>
                        <td className="ta-right workforce-cell-accent">{formatNumber(gestor.cargaMedia)}</td>
                        <td className="ta-right">{formatNumber(gestor.totalVolumenAsientos)}</td>
                        <td className="ta-right">{formatPercent(gestor.pctContabilidadMedio)}</td>
                        <td className="workforce-services-cell">
                          <div className="workforce-service-pill-row">
                            {serviceEntries(gestor).map((service) => (
                              <span key={`${gestor.gestor}-${service.label}`} className="workforce-service-pill">
                                {service.label}
                                <strong>{service.value}</strong>
                              </span>
                            ))}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </section>

          <section className="workforce-section workforce-costs-section">
            <div className="workforce-section-head">
              <div>
                <span>Economia</span>
                <h2>Evolucion de costes</h2>
              </div>
              <SectionChips
                items={[
                  hasEconomicData ? costScopeLabel : null,
                  hasEconomicData ? `${formatInteger(comparableLaborHistoryImports.length)} periodos` : null,
                  laborHistoryComparisonLabel,
                  laborHistoryUnknownPeriods ? `${formatInteger(laborHistoryUnknownPeriods)} sin periodo` : null,
                  hasPairedCostActivity ? 'Cruce activo' : hasEconomicData ? 'Sin cruce' : 'Sin costes',
                ]}
              />
            </div>

            {hasEconomicData ? (
              <>
                <section className="workforce-kpi-strip workforce-kpi-strip-costs">
                  <WorkforceKpiCard label="Coste personal" value={formatCurrency(laborCosts.totalCostePersonalAnual)} detail={costScopeLabel} tone="accent" />
                  <WorkforceKpiCard label="SS empresa" value={formatCurrency(laborCosts.totalSsEmpresaAnual)} detail={costScopeLabel} />
                  <WorkforceKpiCard label="Coste laboral total" value={formatCurrency(laborCosts.totalCosteLaboralAnual)} detail={costScopeLabel} tone="teal" />
                  <WorkforceKpiCard label="Coste medio por gestor" value={formatCurrency(laborCosts.costeMedioPorGestor)} detail={averageCostLabel} />
                </section>

                {hasLaborHistorySelection ? (
                  <div className="workforce-chart-grid">
                    <div className="workforce-chart-card">
                      <div className="workforce-block-head">
                        <span>Timeline</span>
                        <h3>Serie economica</h3>
                        <p className="workforce-card-note">La evolución económica se lee sobre Periodo A y Periodo B, no sobre la secuencia técnica de cargas.</p>
                      </div>
                      <EChart
                        module="tribunal"
                        actions={false}
                        valueSuffix="EUR"
                        className="workforce-chart-shell"
                        height={320}
                        option={buildLaborCostHistoryOption(selectedLaborHistoryImports)}
                      />
                    </div>

                    <div className="workforce-chart-card">
                      <div className="workforce-block-head">
                        <span>Gestores</span>
                        <h3>Coste total por periodo</h3>
                        <p className="workforce-card-note">Evolución de los principales gestores usando solo la pareja de periodos seleccionada.</p>
                      </div>
                      <EChart
                        module="tribunal"
                        actions={false}
                        valueSuffix="EUR"
                        className="workforce-chart-shell"
                        height={320}
                        option={buildLaborCostGestorHistoryOption(selectedLaborHistoryImports, economicHistoryGestores)}
                      />
                    </div>
                  </div>
                ) : (
                  <div className="workforce-empty-premium">
                    {hasRequestedComparisonWindow
                      ? `No hay dos LABOR_COSTS comparables para ${comparisonBaseLabel} vs ${comparisonSelectedLabel}.`
                      : laborHistoryUnknownPeriods
                        ? 'Necesitas dos LABOR_COSTS con periodo conocido para comparar meses. Hay cargas económicas sin periodo utilizable.'
                        : 'Solo hay un periodo de costes disponible. La evolución se activará al cargar un segundo LABOR_COSTS.'}
                  </div>
                )}

                {laborWindowReady ? (
                  <div className="workforce-kpi-strip workforce-kpi-strip-history">
                    <div className={`workforce-delta-card workforce-delta-card-${getDeltaTone(laborHistoryDeltas.personal?.delta)}`}>
                      <span>Coste personal</span>
                      <strong>{laborHistoryDeltas.personal ? `${laborHistoryDeltas.personal.delta > 0 ? '+' : ''}${formatCurrency(laborHistoryDeltas.personal.delta)}` : EMPTY_VALUE}</strong>
                      <small>vs periodo anterior</small>
                    </div>
                    <div className={`workforce-delta-card workforce-delta-card-${getDeltaTone(laborHistoryDeltas.ss?.delta)}`}>
                      <span>SS empresa</span>
                      <strong>{laborHistoryDeltas.ss ? `${laborHistoryDeltas.ss.delta > 0 ? '+' : ''}${formatCurrency(laborHistoryDeltas.ss.delta)}` : EMPTY_VALUE}</strong>
                      <small>vs periodo anterior</small>
                    </div>
                    <div className={`workforce-delta-card workforce-delta-card-${getDeltaTone(laborHistoryDeltas.total?.delta)}`}>
                      <span>Coste total</span>
                      <strong>{laborHistoryDeltas.total ? `${laborHistoryDeltas.total.delta > 0 ? '+' : ''}${formatCurrency(laborHistoryDeltas.total.delta)}` : EMPTY_VALUE}</strong>
                      <small>vs periodo anterior</small>
                    </div>
                  </div>
                ) : null}
              </>
            ) : (
              <div className="workforce-empty-premium">Costes laborales no disponibles. Carga un fichero para activar este analisis.</div>
            )}
          </section>

          <section className="workforce-section workforce-costs-section">
            <div className="workforce-section-head">
              <div>
                <span>Costes laborales</span>
                <h2>Lectura económica del equipo</h2>
              </div>
              <SectionChips
                items={[
                  laborCosts ? costScopeLabel : null,
                  laborCosts ? `${formatInteger(economicDisplayRows.length)} gestores` : null,
                  laborCosts ? (laborCosts.reviewCount > 0 ? `${formatInteger(laborCosts.reviewCount)} review` : 'Matching OK') : 'Pendiente',
                ]}
              />
            </div>

            {hasEconomicData ? (
              <>
                {laborCosts.reviewCount > 0 ? (
                  <Alert tone="warning">{laborCosts.reviewCount} fila(s) de costes han quedado en REVIEW. Revisa el matching de gestores.</Alert>
                ) : null}
                {!hasPairedCostActivity && workforceImport ? (
                  <Alert tone="warning">{laborContextMessage || 'El cruce coste/actividad no esta disponible para el snapshot actual.'}</Alert>
                ) : null}

                <div className="workforce-cost-layout">
                  <div className="workforce-chart-card workforce-chart-card-wide">
                    <div className="workforce-block-head">
                      <span>{costChartEyebrow}</span>
                      <h3>Coste personal vs SS empresa</h3>
                      <p className="workforce-card-note">Serie agregada del periodo cargado para entender composición del coste.</p>
                    </div>
                    {monthlyTotals.length ? (
                      <EChart module="tribunal" actions={false} valueSuffix="EUR" className="workforce-chart-shell" height={336} option={buildMonthlyCostOption(monthlyTotals)} />
                    ) : (
                      <div className="workforce-empty-inline workforce-empty-inline-compact">
                        No hay suficiente detalle mensual para representar la serie de costes en esta carga.
                      </div>
                    )}
                  </div>

                  <div className="workforce-chart-card">
                    <div className="workforce-block-head">
                      <span>{costChartEyebrow}</span>
                      <h3>{effectiveCostChartTitle}</h3>
                      <p className="workforce-card-note">Barras anuales por gestor con el mismo criterio económico del dashboard.</p>
                    </div>
                    <EChart
                      module="tribunal"
                      actions={false}
                      valueSuffix="EUR"
                      className="workforce-chart-shell"
                      height={320}
                      activeDataIndex={activeEconomicGestorIndex >= 0 ? activeEconomicGestorIndex : null}
                      onClick={(params) => {
                        const index = Number(params?.dataIndex)
                        if (!Number.isFinite(index) || index < 0 || index >= economicDisplayRows.length) return
                        setSelectedGestor(economicDisplayRows[index].gestor)
                      }}
                      option={buildBarOption(economicDisplayRows, 'costeLaboralAnual', 'currency')}
                    />
                    <ChartMeta rows={economicDisplayRows} metricKey="costeLaboralAnual" kind="currency" />
                  </div>
                </div>

                <div className="workforce-table-card">
                  <div className="workforce-table-panel-head">
                    <div className="workforce-table-panel-copy">
                      <span>Tabla de costes</span>
                      <strong>Coste anual por gestor</strong>
                    </div>
                    <small>{formatInteger(economicDisplayRows.length)} gestor(es)</small>
                  </div>
                  <div className="workforce-table-wrap workforce-table-wrap-costs">
                    <table className="table workforce-table workforce-cost-table">
                      <thead>
                        <tr>
                          <th>Gestor</th>
                          <th className="ta-right">Coste personal</th>
                          <th className="ta-right">SS</th>
                          <th className="ta-right">Coste total</th>
                          <th className="ta-right">Coste/cliente total</th>
                          <th className="ta-right">Coste/1.000 asientos</th>
                        </tr>
                      </thead>
                      <tbody>
                        {economicDisplayRows.map((gestor: any) => (
                          <tr
                            key={`${gestor.gestor}-costes`}
                            className={normalizeGestorName(gestor.gestor) === normalizeGestorName(activeEconomicGestor?.gestor) ? 'workforce-table-row-active' : ''}
                            onClick={() => setSelectedGestor(gestor.gestor)}
                          >
                            <td className="workforce-gestor-cell">
                              <div className="workforce-history-cell">
                                <strong>{formatText(gestor.gestor)}</strong>
                                {gestor.gestor === activeEconomicGestor?.gestor ? <span className="workforce-history-inline-tag">Activo</span> : null}
                              </div>
                            </td>
                            <td className="ta-right">{formatCurrency(gestor.costePersonalAnual)}</td>
                            <td className="ta-right">{formatCurrency(gestor.ssEmpresaAnual)}</td>
                            <td className="ta-right workforce-cell-strong">{formatCurrency(gestor.costeLaboralAnual)}</td>
                            <td className="ta-right">{formatCurrency(gestor.costePorCliente)}</td>
                            <td className="ta-right workforce-cell-accent">{formatCurrency(gestor.costePor1000Asientos)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              </>
            ) : (
              <div className="workforce-empty-premium">
                {hasEconomicData
                  ? laborContextMessage || 'El cruce coste/actividad no esta disponible para el snapshot actual.'
                  : 'Costes laborales no disponibles. Carga un fichero para activar este análisis.'}
              </div>
            )}
          </section>

          <section className="workforce-section">
            <div className="workforce-section-head">
              <div>
                <span>Relación coste/actividad</span>
                <h2>Indicadores operativos</h2>
              </div>
              <SectionChips items={[hasPairedCostActivity ? 'Backend' : null, hasPairedCostActivity ? pairedLaborLabel : null, `${formatInteger(gestores.length)} gestores`]} />
            </div>

            {hasPairedCostActivity ? (
              <div className="workforce-relation-grid">
                <div className="workforce-executive-card workforce-executive-card-quiet">
                  <div className="workforce-block-head">
                    <span>Coste vs actividad</span>
                    <h3>Lectura visual breve</h3>
                  </div>
                  <div className="workforce-note-list">
                    {costActivityReadings.map((reading: string) => (
                      <div key={reading} className="workforce-note-item">
                        <span />
                        <p>{reading}</p>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="workforce-table-card">
                  <div className="workforce-table-panel-head">
                    <div className="workforce-table-panel-copy">
                      <span>Ratios combinados</span>
                      <strong>Coste e intensidad operativa</strong>
                      <p className="workforce-table-panel-note">Indicadores económicos derivados del backend, sin recalcular nada en frontend.</p>
                    </div>
                    <small>{formatInteger(gestores.length)} gestor(es)</small>
                  </div>
                  <div className="workforce-table-wrap workforce-table-wrap-compact">
                    <table className="table workforce-table workforce-cost-activity-table">
                      <thead>
                        <tr>
                          <th>Gestor</th>
                          <th className="ta-right">Coste/cliente total</th>
                          <th className="ta-right">Coste/1.000 min</th>
                          <th className="ta-right">Min/1.000 EUR</th>
                          <th className="ta-right">Asientos/1.000 EUR</th>
                        </tr>
                      </thead>
                      <tbody>
                        {gestores.map((gestor: any) => (
                          <tr key={`${gestor.gestor}-ratios`}>
                            <td className="workforce-gestor-cell">
                              <div className="workforce-history-cell">
                                <strong>{formatText(gestor.gestor)}</strong>
                                {gestor.gestor === activeGestor?.gestor ? <span className="workforce-history-inline-tag">Activo</span> : null}
                              </div>
                            </td>
                            <td className="ta-right">{formatCurrency(gestor.costePorCliente)}</td>
                            <td className="ta-right">{formatCurrency(gestor.costePor1000Minutas)}</td>
                            <td className="ta-right">{formatNumber(gestor.minutasPor1000Coste)}</td>
                            <td className="ta-right workforce-cell-accent">{formatNumber(gestor.asientosPor1000Coste)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              </div>
            ) : (
              <div className="workforce-empty-premium">
                {hasEconomicData
                  ? laborContextMessage || 'El cruce coste/actividad no esta disponible para el snapshot actual.'
                  : 'Costes laborales no disponibles. Carga un fichero para activar este análisis.'}
              </div>
            )}
          </section>

          <section className="workforce-section">
            <div className="workforce-section-head">
              <div>
                <span>Informe</span>
                <h2>Descargar informe</h2>
              </div>
              <SectionChips items={['Métricas del dashboard', laborCosts ? 'Con costes' : 'Solo Workforce']} />
            </div>

            <div className="workforce-download-card">
              <div className="workforce-download-copy">
                <strong>PDF Workforce</strong>
                <p>Resumen ejecutivo, distribucion operativa, costes e historico en el mismo informe.</p>
              </div>
              <div className="workforce-download-actions">
                <Button onClick={handleDownloadPdf} disabled={!companyId || !hasGestores} loading={pdfLoading}>
                  Descargar informe PDF
                </Button>
              </div>
            </div>
          </section>
        </>
      ) : hasEconomicData ? (
        <>
          <section className="workforce-section workforce-costs-section">
            <div className="workforce-section-head">
              <div>
                <span>Economia</span>
                <h2>LABOR_COSTS sin Workforce</h2>
              </div>
              <SectionChips items={[costScopeLabel, `${formatInteger(economicDisplayRows.length)} gestores`, `${formatInteger(comparableLaborHistoryImports.length)} periodos`, laborHistoryComparisonLabel]} />
            </div>

            <section className="workforce-kpi-strip workforce-kpi-strip-costs">
              <WorkforceKpiCard label="Coste personal" value={formatCurrency(laborCosts.totalCostePersonalAnual)} detail={costScopeLabel} tone="accent" />
              <WorkforceKpiCard label="SS empresa" value={formatCurrency(laborCosts.totalSsEmpresaAnual)} detail={costScopeLabel} />
              <WorkforceKpiCard label="Coste laboral total" value={formatCurrency(laborCosts.totalCosteLaboralAnual)} detail={costScopeLabel} tone="teal" />
              <WorkforceKpiCard label="Coste medio por gestor" value={formatCurrency(laborCosts.costeMedioPorGestor)} detail={averageCostLabel} />
            </section>

            <div className="workforce-chart-grid">
              <div className="workforce-chart-card">
                <div className="workforce-block-head">
                  <span>Timeline</span>
                  <h3>Evolucion economica</h3>
                  <p className="workforce-card-note">Serie construida solo con referencePeriod de los LABOR_COSTS.</p>
                </div>
                {comparableLaborHistoryImports.length ? (
                  <EChart module="tribunal" actions={false} valueSuffix="EUR" className="workforce-chart-shell" height={320} option={buildLaborCostHistoryOption(comparableLaborHistoryImports)} />
                ) : (
                  <div className="workforce-empty-inline">No hay periodos de costes disponibles todavia.</div>
                )}
              </div>

              <div className="workforce-chart-card">
                <div className="workforce-block-head">
                  <span>Actual</span>
                  <h3>{effectiveCostChartTitle}</h3>
                  <p className="workforce-card-note">Reparto actual por gestor sin depender de Workforce.</p>
                </div>
                <EChart module="tribunal" actions={false} valueSuffix="EUR" className="workforce-chart-shell" height={320} option={buildBarOption(economicDisplayRows, 'costeLaboralAnual', 'currency')} />
              </div>
            </div>

            <div className="workforce-table-card">
              <div className="workforce-table-panel-head">
                <div className="workforce-table-panel-copy">
                  <span>Tabla de costes</span>
                  <strong>Gestores economicos del periodo activo</strong>
                </div>
                <small>{formatInteger(economicDisplayRows.length)} gestor(es)</small>
              </div>
              <div className="workforce-table-wrap workforce-table-wrap-costs">
                <table className="table workforce-table workforce-cost-table">
                  <thead>
                    <tr>
                      <th>Gestor</th>
                      <th className="ta-right">Coste personal</th>
                      <th className="ta-right">SS</th>
                      <th className="ta-right">Coste total</th>
                    </tr>
                  </thead>
                  <tbody>
                    {economicDisplayRows.map((gestor: any) => (
                      <tr
                        key={`${gestor.gestor}-economic-only`}
                        className={normalizeGestorName(gestor.gestor) === normalizeGestorName(activeEconomicGestor?.gestor) ? 'workforce-table-row-active' : ''}
                        onClick={() => setSelectedGestor(gestor.gestor)}
                      >
                        <td className="workforce-gestor-cell">
                          <div className="workforce-history-cell">
                            <strong>{formatText(gestor.gestor)}</strong>
                            {gestor.gestor === activeEconomicGestor?.gestor ? <span className="workforce-history-inline-tag">Activo</span> : null}
                          </div>
                        </td>
                        <td className="ta-right">{formatCurrency(gestor.costePersonalAnual)}</td>
                        <td className="ta-right">{formatCurrency(gestor.ssEmpresaAnual)}</td>
                        <td className="ta-right workforce-cell-strong">{formatCurrency(gestor.costeLaboralAnual)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </section>

        </>
      ) : (
        <div className="card section soft">
          <div className="empty">{EMPTY_DATA_TEXT} de trabajadores. Importa la plantilla para ver métricas por gestor.</div>
        </div>
      )}
    </div>
  )
}
