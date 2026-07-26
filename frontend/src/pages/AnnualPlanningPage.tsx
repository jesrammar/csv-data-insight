// @ts-nocheck
import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  downloadBudgetReportPdf,
  getBudgetLongInsights,
  getBudgetSummary,
  getBudgetWorkflow,
  getUniversalLineage,
  listUniversalImports,
  type BudgetComparisonMonth,
  type BudgetLongInsights,
  type BudgetSummary,
  type BudgetWorkflowDto,
  type UniversalImportDto,
  type UniversalImportLineageDto,
  type UniversalIntakeDiagnosis
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import Section from '../components/ui/Section'
import EChart from '../components/charts/EChart'
import { EMPTY_DATA_TEXT, formatDateTime, formatMoney } from '../utils/format'
import { useToast } from '../components/ui/ToastProvider'
import { intakeDetail, intakeDisplayLabel, intakeKind, isAnnualBudgetDiagnosis } from '../utils/intakeDiagnosis'
import { setWorkPeriod } from '../utils/workPeriod'

function fmtDeltaMoney(value?: number | null) {
  if (value == null || Number.isNaN(Number(value))) return EMPTY_DATA_TEXT
  const num = Number(value)
  const sign = num > 0 ? '+' : ''
  return `${sign}${formatMoney(num)}`
}

function toNumber(value?: number | null) {
  const num = Number(value ?? 0)
  return Number.isFinite(num) ? num : 0
}

function monthKeyToPeriod(monthKey: string, year: number) {
  const normalized = String(monthKey || '').trim().toUpperCase()
  const monthNumber =
    normalized === 'ENERO'
      ? 1
      : normalized === 'FEBRERO'
        ? 2
        : normalized === 'MARZO'
          ? 3
          : normalized === 'ABRIL'
            ? 4
            : normalized === 'MAYO'
              ? 5
              : normalized === 'JUNIO'
                ? 6
                : normalized === 'JULIO'
                  ? 7
                  : normalized === 'AGOSTO'
                    ? 8
                    : normalized === 'SEPTIEMBRE'
                      ? 9
                      : normalized === 'OCTUBRE'
                        ? 10
                        : normalized === 'NOVIEMBRE'
                          ? 11
                          : normalized === 'DICIEMBRE'
                            ? 12
                            : 0

  if (!monthNumber) return null
  return `${year}-${String(monthNumber).padStart(2, '0')}`
}

function buildNetComparisonChart(months: BudgetComparisonMonth[]) {
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['Neto previsto', 'Neto real', 'Desviación'] },
    xAxis: { type: 'category', data: months.map((item) => item.monthLabel) },
    yAxis: [
      { type: 'value', axisLabel: { formatter: (value: number) => formatMoney(value) } },
      { type: 'value', axisLabel: { formatter: (value: number) => formatMoney(value) } }
    ],
    series: [
      {
        name: 'Neto previsto',
        type: 'bar',
        barMaxWidth: 24,
        data: months.map((item) => toNumber(item.plannedNet))
      },
      {
        name: 'Neto real',
        type: 'bar',
        barMaxWidth: 24,
        data: months.map((item) => (item.hasActual ? toNumber(item.actualNet) : null))
      },
      {
        name: 'Desviación',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: months.map((item) => (item.hasActual ? toNumber(item.netVariance) : null)),
        lineStyle: { width: 3 },
        markLine: {
          symbol: 'none',
          lineStyle: { type: 'dashed', width: 1.5, opacity: 0.7 },
          data: [{ yAxis: 0, name: 'Objetivo' }]
        }
      }
    ]
  }
}

function buildBalanceComparisonChart(months: BudgetComparisonMonth[]) {
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['Saldo previsto', 'Saldo real'] },
    xAxis: { type: 'category', data: months.map((item) => item.monthLabel) },
    yAxis: { type: 'value', axisLabel: { formatter: (value: number) => formatMoney(value) } },
    series: [
      {
        name: 'Saldo previsto',
        type: 'line',
        smooth: true,
        data: months.map((item) => toNumber(item.plannedEndingBalance)),
        lineStyle: { width: 3 }
      },
      {
        name: 'Saldo real',
        type: 'line',
        smooth: true,
        data: months.map((item) => (item.hasActual ? toNumber(item.actualEndingBalance) : null)),
        lineStyle: { width: 3 }
      }
    ]
  }
}

export default function AnnualPlanningPage() {
  const { id: companyId, plan } = useCompanySelection()
  const navigate = useNavigate()
  const location = useLocation()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [downloading, setDownloading] = useState(false)

  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const cameFromAnnualUpload = new URLSearchParams(location.search).get('source') === 'upload'

  useEffect(() => {
    if (!cameFromAnnualUpload || !companyId || !hasGold) return
    queryClient.invalidateQueries({ queryKey: ['budget-workflow', companyId] })
    queryClient.invalidateQueries({ queryKey: ['budget-summary-workflow', companyId] })
    queryClient.invalidateQueries({ queryKey: ['budget-insights-workflow', companyId] })
    queryClient.invalidateQueries({ queryKey: ['universal-imports', companyId] })
    queryClient.invalidateQueries({ queryKey: ['universal-lineage-latest', companyId] })
  }, [cameFromAnnualUpload, companyId, hasGold, queryClient])

  const { data: workflowData, error: workflowError } = useQuery({
    queryKey: ['budget-workflow', companyId],
    queryFn: () => getBudgetWorkflow(companyId as number),
    enabled: !!companyId && hasGold,
    refetchOnMount: 'always'
  })

  const { data: summaryData } = useQuery({
    queryKey: ['budget-summary-workflow', companyId],
    queryFn: () => getBudgetSummary(companyId as number),
    enabled: !!companyId && hasGold,
    refetchOnMount: 'always'
  })

  const { data: insightsData } = useQuery({
    queryKey: ['budget-insights-workflow', companyId],
    queryFn: () => getBudgetLongInsights(companyId as number),
    enabled: !!companyId && hasGold,
    refetchOnMount: 'always'
  })

  const { data: universalImportsData } = useQuery({
    queryKey: ['universal-imports', companyId],
    queryFn: () => listUniversalImports(companyId as number),
    enabled: !!companyId && hasGold,
    refetchOnMount: 'always'
  })

  const { data: universalLineageData } = useQuery({
    queryKey: ['universal-lineage-latest', companyId],
    queryFn: () => getUniversalLineage(companyId as number),
    enabled: !!companyId && hasGold,
    refetchOnMount: 'always'
  })

  const workflow = workflowData as BudgetWorkflowDto | undefined
  const summary = summaryData as BudgetSummary | undefined
  const insights = insightsData as BudgetLongInsights | undefined
  const latestUniversalImport = ((universalImportsData as UniversalImportDto[] | undefined) || [])[0]
  const latestUniversalLineage = (universalLineageData as UniversalImportLineageDto | null | undefined) || null
  const latestUniversalDiagnosis = (latestUniversalLineage?.analysis?.intakeDiagnosis || null) as UniversalIntakeDiagnosis | null
  const sourceDiagnosisKind = intakeKind(latestUniversalDiagnosis)

  const sourceFilename = workflow?.sourceFilename || latestUniversalImport?.filename || latestUniversalLineage?.filename || null
  const sourceCreatedAt = workflow?.sourceCreatedAt || latestUniversalImport?.createdAt || latestUniversalLineage?.createdAt || null
  const sourceSheetIndex = workflow?.sourceSheetIndex ?? latestUniversalLineage?.analysis?.xlsx?.sheetIndex ?? null
  const sourceHeaderRow = workflow?.sourceHeaderRow ?? latestUniversalLineage?.analysis?.xlsx?.headerRow1Based ?? null

  const wrongAnnualSource =
    !workflow?.sourcePresent &&
    !summary &&
    Boolean(latestUniversalImport?.filename) &&
    Boolean(latestUniversalDiagnosis) &&
    !isAnnualBudgetDiagnosis(latestUniversalDiagnosis)

  const annualSourceDetected = Boolean((workflow?.sourcePresent || sourceFilename) && !wrongAnnualSource)
  const structureValidated = Boolean(workflow?.structureValidated || summary)
  const annualInsightsReady = Boolean(workflow?.annualInsightsReady || insights)
  const comparison = workflow?.comparisonSummary || null
  const comparisonMonths = workflow?.comparisonMonths || []
  const comparisonYear = comparison?.comparisonYear || new Date().getFullYear()
  const comparisonReady = Boolean(workflow?.comparisonReady || (comparison?.commonMonths || 0) > 0)
  const plannedMonthsAvailable = workflow?.plannedMonthsAvailable ?? summary?.months?.length ?? 0
  const actualMonthsAvailable = workflow?.actualMonthsAvailable ?? 0
  const needsValidation = annualSourceDetected && !structureValidated
  const annualReadingReady = structureValidated && annualInsightsReady && !comparisonReady
  const comparisonLive = comparisonReady && comparisonMonths.length > 0
  const showRecoveryHint = cameFromAnnualUpload && annualSourceDetected && !structureValidated

  const statusTitle =
    wrongAnnualSource
      ? 'La última carga no corresponde a un plan anual'
      : !annualSourceDetected
        ? 'Sin presupuesto anual'
        : !structureValidated
          ? 'Presupuesto cargado'
          : !annualInsightsReady
            ? 'Lectura anual pendiente'
            : !comparisonReady
              ? 'Plan anual listo'
              : 'Comparativa activa'

  const statusDetail =
    wrongAnnualSource
      ? intakeDetail(latestUniversalDiagnosis, workflow?.statusDetail || 'La última carga encaja mejor en otro flujo.')
      : !annualSourceDetected
        ? 'Sube un presupuesto anual o una previsión mensual para activar esta vista.'
        : !structureValidated
          ? 'El fichero ya está dentro. Falta validar hoja y cabecera para convertirlo en una lectura anual útil.'
          : !annualInsightsReady
            ? 'La estructura existe, pero todavía falta cerrar la lectura anual consultiva.'
            : !comparisonReady
              ? 'El plan ya puede leerse. La comparativa se activará cuando existan meses reales comparables.'
              : 'Ya existe contraste real vs presupuesto para este ejercicio.'

  const nextStepTitle = wrongAnnualSource
    ? 'Este fichero no entra en Plan anual'
    : !annualSourceDetected
      ? 'Sube un presupuesto anual'
      : needsValidation
        ? 'Falta validar hoja y cabecera'
        : comparisonLive
          ? 'Comparativa real vs presupuesto lista'
          : annualReadingReady
            ? 'Ya puedes leer el plan'
            : 'El plan ya está dentro'

  const nextStepDetail = wrongAnnualSource
    ? 'La última carga encaja mejor en otro módulo. No la uses como presupuesto anual.'
    : !annualSourceDetected
      ? 'Necesitas un XLSX o CSV anual con meses y partidas para activar esta vista.'
      : needsValidation
        ? 'El fichero ya se detectó como anual, pero todavía no se ha convertido en una lectura fiable.'
        : comparisonLive
          ? `${comparison?.commonMonths || 0} meses ya comparan plan frente a real del ejercicio ${comparisonYear}.`
          : annualReadingReady
            ? 'La lectura anual ya es útil. Falta que entren más meses reales para abrir la comparativa.'
            : 'La estructura está entrando, pero aún no ofrece una lectura anual completa.'

  const comparisonMonthsByLabel = useMemo(() => {
    const map = new Map<string, BudgetComparisonMonth>()
    for (const month of comparisonMonths) map.set(month.monthLabel, month)
    return map
  }, [comparisonMonths])

  const netChart = useMemo(() => buildNetComparisonChart(comparisonMonths), [comparisonMonths])
  const balanceChart = useMemo(() => buildBalanceComparisonChart(comparisonMonths), [comparisonMonths])

  const resolvePeriod = (month?: BudgetComparisonMonth | null) => {
    if (!month) return null
    const actualPeriod = String(month.actualPeriod || '').trim()
    if (actualPeriod) return actualPeriod
    return monthKeyToPeriod(month.monthKey, comparisonYear)
  }

  const openMonthWorkflow = (month?: BudgetComparisonMonth | null) => {
    const period = resolvePeriod(month)
    if (!companyId || !period) return
    setWorkPeriod(companyId, period)
    navigate(`/monthly-close?period=${encodeURIComponent(period)}`)
  }

  const openMonthCash = (month?: BudgetComparisonMonth | null) => {
    const period = resolvePeriod(month)
    if (!companyId || !period) return
    setWorkPeriod(companyId, period)
    navigate('/dashboard', { state: { drillPeriod: period } })
  }

  const drilldownCards = useMemo(() => {
    const cards: Array<{
      key: string
      eyebrow: string
      title: string
      detail: string
      tone: 'positive' | 'negative' | 'neutral'
      month: BudgetComparisonMonth
    }> = []
    const used = new Set<string>()

    const positive = comparison?.strongestPositiveMonth ? comparisonMonthsByLabel.get(comparison.strongestPositiveMonth) || null : null
    const negative = comparison?.strongestNegativeMonth ? comparisonMonthsByLabel.get(comparison.strongestNegativeMonth) || null : null
    const latest = comparison?.latestComparedPeriod
      ? comparisonMonths.find((month) => String(month.actualPeriod || '') === String(comparison.latestComparedPeriod))
      : null

    const pushCard = (
      key: string,
      eyebrow: string,
      month: BudgetComparisonMonth | null | undefined,
      tone: 'positive' | 'negative' | 'neutral',
      detail: string
    ) => {
      if (!month) return
      const period = resolvePeriod(month)
      const dedupeKey = period || month.monthLabel
      if (used.has(dedupeKey)) return
      used.add(dedupeKey)
      cards.push({ key, eyebrow, title: period || month.monthLabel, detail, tone, month })
    }

    pushCard(
      'positive',
      'Mes más por encima del plan',
      positive,
      'positive',
      positive
        ? `Neto real ${formatMoney(positive.actualNet)} frente a ${formatMoney(positive.plannedNet)}. Desviación ${fmtDeltaMoney(positive.netVariance)}.`
        : ''
    )

    pushCard(
      'negative',
      'Mes más por debajo del plan',
      negative,
      'negative',
      negative
        ? `Neto real ${formatMoney(negative.actualNet)} frente a ${formatMoney(negative.plannedNet)}. Desviación ${fmtDeltaMoney(negative.netVariance)}.`
        : ''
    )

    pushCard(
      'latest',
      'Último mes comparable',
      latest,
      'neutral',
      latest
        ? `Saldo real ${formatMoney(latest.actualEndingBalance)} frente a saldo previsto ${formatMoney(latest.plannedEndingBalance)}.`
        : ''
    )

    for (const month of comparisonMonths) {
      if (cards.length >= 3) break
      if (!month.hasActual) continue
      const period = resolvePeriod(month)
      const dedupeKey = period || month.monthLabel
      if (used.has(dedupeKey)) continue
      used.add(dedupeKey)
      cards.push({
        key: `fallback-${dedupeKey}`,
        eyebrow: 'Mes comparable',
        title: period || month.monthLabel,
        detail: `Neto real ${formatMoney(month.actualNet)} frente a ${formatMoney(month.plannedNet)}. Desviación ${fmtDeltaMoney(month.netVariance)}.`,
        tone: toNumber(month.netVariance) >= 0 ? 'positive' : 'negative',
        month
      })
    }

    return cards.slice(0, 2)
  }, [comparison, comparisonMonths, comparisonMonthsByLabel])

  const handleDownloadAnnualReport = async () => {
    if (!companyId) return
    setDownloading(true)
    try {
      const blob = await downloadBudgetReportPdf(companyId)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `budget-report-${companyId}.pdf`
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
      toast.push({ tone: 'success', title: 'Informe anual', message: 'La descarga del PDF ya está en marcha.' })
    } catch (err: any) {
      toast.push({ tone: 'danger', title: 'Error', message: String(err?.message || err || 'No se pudo descargar el informe anual.') })
    } finally {
      setDownloading(false)
    }
  }

  const primaryAction = wrongAnnualSource
    ? { label: 'Abrir Universal', onClick: () => navigate('/universal') }
    : !annualSourceDetected
      ? { label: 'Subir presupuesto', onClick: () => navigate('/imports?mode=universal&flow=budget') }
      : !structureValidated
        ? { label: 'Corregir carga', onClick: () => navigate('/imports?mode=universal&flow=budget') }
        : !comparisonReady
          ? { label: 'Abrir análisis técnico', onClick: () => navigate('/budget/dashboard') }
          : { label: 'Descargar informe anual', onClick: handleDownloadAnnualReport }

  return (
    <div className="annual-budget-page">
      <PageHeader
        title="Plan anual"
        subtitle="Una vista breve para saber si el plan vale y dónde se desvía."
        actions={<span className="badge">{statusTitle}</span>}
      />

      {!hasGold ? <Alert tone="warning">Disponible desde Gold.</Alert> : null}
      {!companyId ? <Alert tone="warning">Selecciona una empresa.</Alert> : null}
      {cameFromAnnualUpload && annualSourceDetected ? (
        <Alert tone="info">Presupuesto detectado. Esta pantalla ya relee la última carga anual válida.</Alert>
      ) : null}
      {wrongAnnualSource ? (
        <Alert tone="warning">
          La última carga parece {intakeDisplayLabel(latestUniversalDiagnosis, 'otro tipo de base').toLowerCase()}, no un plan anual. Abre Universal o sube un presupuesto anual real.
        </Alert>
      ) : null}
      {showRecoveryHint ? (
        <Alert tone="info">El fichero anual ya está cargado. Si aún no ves una lectura útil, abre el análisis técnico o corrige hoja y cabecera.</Alert>
      ) : null}
      {workflowError ? <Alert tone="danger">{String((workflowError as any)?.message || workflowError)}</Alert> : null}

      <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">1. Estado del plan</h3>
          <span className="upload-hint">Solo la lectura operativa del flujo anual.</span>
        </div>
        <div className="grid grid-autofit-220 mt-12">
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Situación</div>
            <div className="fw-800 mt-1">{statusTitle}</div>
            <div className="upload-hint mt-1">{statusDetail}</div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Fuente</div>
            <div className="fw-800 mt-1">{sourceFilename || 'Sin fichero anual'}</div>
            <div className="upload-hint mt-1">{sourceCreatedAt ? formatDateTime(sourceCreatedAt) : 'Carga pendiente.'}</div>
            {latestUniversalDiagnosis ? (
              <div className="upload-hint mt-8">
                Tipo detectado: <strong>{intakeDisplayLabel(latestUniversalDiagnosis, 'Base detectada')}</strong>
              </div>
            ) : null}
            {sourceSheetIndex != null || sourceHeaderRow != null ? (
              <div className="upload-hint mt-8">
                Último intento usado: hoja {sourceSheetIndex != null ? Number(sourceSheetIndex) + 1 : '-'} · cabecera fila {sourceHeaderRow ?? '-'}
              </div>
            ) : null}
            {workflow?.sourceAttemptTrendTitle ? (
              <div className="upload-hint mt-8">
                <strong>{workflow.sourceAttemptTrendTitle}.</strong> {workflow?.sourceAttemptTrendDetail || ''}
              </div>
            ) : null}
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Meses comparables</div>
            <div className="fw-800 mt-1">{comparison?.commonMonths ?? 0}</div>
            <div className="upload-hint mt-1">
              {plannedMonthsAvailable} plan · {actualMonthsAvailable} contraste
            </div>
          </div>
        </div>
        <div className="row row-wrap gap-8 mt-12">
          <Button size="sm" variant="secondary" loading={downloading} onClick={primaryAction.onClick}>
            {primaryAction.label}
          </Button>
          {wrongAnnualSource ? (
            <Button size="sm" variant="ghost" onClick={() => navigate('/imports?mode=universal&flow=budget')}>
              Subir presupuesto correcto
            </Button>
          ) : null}
          {annualSourceDetected && !structureValidated ? (
            <Button size="sm" variant="ghost" onClick={() => navigate('/budget/dashboard')}>
              Abrir análisis técnico
            </Button>
          ) : null}
          {needsValidation ? (
            <>
              <Button size="sm" variant="ghost" onClick={() => navigate('/imports?mode=universal&flow=budget&focus=sheet')}>
                Elegir otra hoja
              </Button>
              <Button size="sm" variant="ghost" onClick={() => navigate('/imports?mode=universal&flow=budget&focus=header')}>
                Cambiar fila de cabecera
              </Button>
            </>
          ) : null}
        </div>
        {needsValidation ? (
          <div className="upload-hint mt-8">
            Qué probar ahora: si el Excel tiene varias hojas, empieza por <strong>Elegir otra hoja</strong>. Si la hoja es correcta pero los meses no se detectan bien, prueba <strong>Cambiar fila de cabecera</strong>.
          </div>
        ) : null}
      </div>

      <Section title="2. Qué falta exactamente" subtitle="Una sola salida clara según el estado real del flujo.">
        <div className="card soft">
          <div className="overview-card-eyebrow">
            {comparisonLive ? 'Comparativa activa' : needsValidation ? 'Bloqueo operativo' : 'Siguiente paso'}
          </div>
          <div className="annual-stage-title mt-1">{nextStepTitle}</div>
          <div className="annual-stage-detail mt-1">{nextStepDetail}</div>
          <div className="grid grid-autofit-220 mt-12">
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Hoja usada</div>
              <div className="fw-800 mt-1">{sourceSheetIndex != null ? `Hoja ${Number(sourceSheetIndex) + 1}` : EMPTY_DATA_TEXT}</div>
              <div className="upload-hint mt-1">Último intento guardado</div>
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Cabecera usada</div>
              <div className="fw-800 mt-1">{sourceHeaderRow != null ? `Fila ${sourceHeaderRow}` : EMPTY_DATA_TEXT}</div>
              <div className="upload-hint mt-1">Punto de arranque del parseo</div>
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Meses listos</div>
              <div className="fw-800 mt-1">{plannedMonthsAvailable}</div>
              <div className="upload-hint mt-1">Meses del plan ya reconocidos</div>
            </div>
          </div>
          {needsValidation ? (
            <div className="row row-wrap gap-8 mt-12">
              <Button size="sm" variant="secondary" onClick={() => navigate('/imports?mode=universal&flow=budget&focus=sheet')}>
                Elegir otra hoja
              </Button>
              <Button size="sm" variant="ghost" onClick={() => navigate('/imports?mode=universal&flow=budget&focus=header')}>
                Cambiar cabecera
              </Button>
            </div>
          ) : null}
          {comparisonLive ? (
            <div className="row row-wrap gap-8 mt-12">
              <Button size="sm" variant="secondary" onClick={() => navigate('/budget/dashboard')}>
                Ver análisis anual
              </Button>
              <Button size="sm" variant="ghost" loading={downloading} onClick={handleDownloadAnnualReport}>
                Descargar informe anual
              </Button>
            </div>
          ) : null}
        </div>
      </Section>

      <Section
        title="3. Señales clave"
        subtitle={
          comparisonLive
            ? 'Ya hay valor consultivo: solo tres señales para explicar el ejercicio.'
            : 'Solo las tres señales que merece la pena mirar ahora.'
        }
      >
        <div className="grid grid-autofit-220 mt-12">
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Margen previsto</div>
            <div className="fw-800 mt-1">{formatMoney(summary?.totalMargin)}</div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Neto YTD real vs previsto</div>
            <div className="fw-800 mt-1">{fmtDeltaMoney(comparison?.netVarianceYtd)}</div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Saldo final YTD</div>
            <div className="fw-800 mt-1">{fmtDeltaMoney(comparison?.endingBalanceVarianceYtd)}</div>
          </div>
        </div>
      </Section>

      {comparisonLive ? (
        <Section title="4. Comparativa real vs presupuesto" subtitle="Aquí ya se ve si el plan aguanta o se desvía.">
          {drilldownCards.length ? (
            <div className="grid annual-drill-grid mb-3">
              {drilldownCards.map((card) => (
                <div key={card.key} className={`card soft annual-drill-card annual-drill-card-${card.tone}`}>
                  <div className="upload-hint">{card.eyebrow}</div>
                  <div className="annual-stage-title mt-1">{card.title}</div>
                  <div className="annual-stage-detail mt-1">{card.detail}</div>
                  <div className="annual-drill-actions mt-12">
                    <Button size="sm" variant="secondary" onClick={() => openMonthWorkflow(card.month)}>
                      Abrir cierre mensual
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => openMonthCash(card.month)}>
                      Ver caja
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          ) : null}
          <div className="grid">
            <div className="card">
              <h3 className="h3-reset">Neto previsto vs real</h3>
              <div className="upload-hint mt-1">Cada punto muestra si el mes fue mejor o peor de lo previsto.</div>
              <EChart
                module="budget"
                valueSuffix="€"
                height={320}
                option={netChart as any}
                onClick={(params) => {
                  const clicked = comparisonMonthsByLabel.get(String(params?.name || ''))
                  if (clicked) openMonthCash(clicked)
                }}
              />
            </div>
            <div className="card">
              <h3 className="h3-reset">Saldo previsto vs real</h3>
              <div className="upload-hint mt-1">Sirve para ver si el plan protege caja o se queda corto.</div>
              <EChart
                module="budget"
                valueSuffix="€"
                height={320}
                option={balanceChart as any}
                onClick={(params) => {
                  const clicked = comparisonMonthsByLabel.get(String(params?.name || ''))
                  if (clicked) openMonthCash(clicked)
                }}
              />
            </div>
          </div>
        </Section>
      ) : null}
    </div>
  )
}
