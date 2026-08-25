// @ts-nocheck
import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  downloadBudgetReportPdf,
  getBudgetAnalysis,
  type BudgetComparisonMonth,
  type BudgetAnalysisBundle,
  type BudgetLongInsights,
  type BudgetSummary,
  type BudgetWorkflowDto,
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import Section from '../components/ui/Section'
import EChart from '../components/charts/EChart'
import { EMPTY_DATA_TEXT, formatDateTime, formatMoney, formatText, normalizeText } from '../utils/format'
import { useToast } from '../components/ui/ToastProvider'

function isGoldPlan(planRaw?: string | null) {
  const normalized = String(planRaw || '')
    .trim()
    .toUpperCase()
    .replace(/\s+/g, '')
    .replace(/[._]/g, '-')
  return normalized === 'GOLD' || normalized === 'PLATINUM' || normalized.startsWith('GOLD-') || normalized.startsWith('PLATINUM-')
}

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
        name: 'Desviacion',
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

  const hasGold = isGoldPlan(plan)
  const cameFromAnnualUpload = new URLSearchParams(location.search).get('source') === 'upload'

  useEffect(() => {
    if (!cameFromAnnualUpload || !companyId || !hasGold) return
    queryClient.invalidateQueries({ queryKey: ['budget-analysis', companyId] })
  }, [cameFromAnnualUpload, companyId, hasGold, queryClient])

  const { data: analysisData, error: workflowError, isPending: analysisPending, isFetching: analysisFetching } = useQuery({
    queryKey: ['budget-analysis', companyId],
    queryFn: () => getBudgetAnalysis(companyId as number),
    enabled: !!companyId && hasGold,
    refetchOnMount: 'always'
  })
  const analysisLoading = !!companyId && hasGold && (analysisPending || analysisFetching) && !analysisData && !workflowError

  const analysis = analysisData as BudgetAnalysisBundle | undefined
  const workflow = analysis?.workflow as BudgetWorkflowDto | undefined
  const summary = (analysis?.summary || undefined) as BudgetSummary | undefined
  const insights = (analysis?.insights || undefined) as BudgetLongInsights | undefined

  const sourceFilename = normalizeText(analysis?.sourceFilename || workflow?.sourceFilename || summary?.sourceFilename || '', '')
  const sourceCreatedAt = analysis?.sourceCreatedAt || workflow?.sourceCreatedAt || summary?.sourceCreatedAt || null
  const sourceSheetIndex = analysis?.sourceSheetIndex ?? workflow?.sourceSheetIndex ?? null
  const sourceHeaderRow = analysis?.sourceHeaderRow ?? workflow?.sourceHeaderRow ?? null

  const wrongAnnualSource = workflow?.status === 'WRONG_SOURCE'

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
  const statusBadgeClassName = wrongAnnualSource
    ? 'badge annual-status-pill annual-status-pill-err'
    : comparisonLive
      ? 'badge annual-status-pill annual-status-pill-ok'
      : annualSourceDetected
        ? 'badge annual-status-pill annual-status-pill-warn'
        : 'badge annual-status-pill'

  const statusTitle =
    wrongAnnualSource
      ? 'La ultima carga no corresponde a un plan anual'
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
      ? normalizeText(workflow?.statusDetail || 'La ultima carga encaja mejor en otro flujo.')
      : !annualSourceDetected
        ? 'Sube un presupuesto anual o una prevision mensual para activar esta vista.'
        : !structureValidated
          ? 'El fichero ya esta dentro. Falta validar hoja y cabecera para convertirlo en una lectura anual util.'
          : !annualInsightsReady
            ? 'La estructura existe, pero todavia falta cerrar la lectura anual consultiva.'
            : !comparisonReady
              ? 'El plan ya puede leerse. La comparativa se activara cuando existan meses reales comparables.'
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
            : 'El plan ya esta dentro'

  const nextStepDetail = wrongAnnualSource
    ? 'La ultima carga encaja mejor en otro modulo. No la uses como presupuesto anual.'
    : !annualSourceDetected
      ? 'Necesitas un XLSX o CSV anual con meses y partidas para activar esta vista.'
      : needsValidation
        ? 'El fichero ya se detecto como anual, pero todavia no se ha convertido en una lectura fiable.'
        : comparisonLive
          ? `${comparison?.commonMonths || 0} meses ya comparan plan frente a real del ejercicio ${comparisonYear}.`
          : annualReadingReady
            ? 'La lectura anual ya es util. Falta que entren mas meses reales para abrir la comparativa.'
            : 'La estructura esta entrando, pero aun no ofrece una lectura anual completa.'

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

  const openMonthAnalysis = (month?: BudgetComparisonMonth | null) => {
    const period = resolvePeriod(month)
    if (!period) return
    navigate('/budget/dashboard')
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
          ? `Neto real ${formatMoney(positive.actualNet)} frente a ${formatMoney(positive.plannedNet)}. Desviacion ${fmtDeltaMoney(positive.netVariance)}.`
        : ''
    )

    pushCard(
      'negative',
      'Mes más por debajo del plan',
      negative,
      'negative',
      negative
          ? `Neto real ${formatMoney(negative.actualNet)} frente a ${formatMoney(negative.plannedNet)}. Desviacion ${fmtDeltaMoney(negative.netVariance)}.`
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
      toast.push({ tone: 'success', title: 'Informe anual', message: 'La descarga del PDF ya esta en marcha.' })
    } catch (err: any) {
      toast.push({ tone: 'danger', title: 'Error', message: String(err?.message || err || 'No se pudo descargar el informe anual.') })
    } finally {
      setDownloading(false)
    }
  }

  const primaryAction = wrongAnnualSource
    ? { label: 'Ir a Cargar datos', onClick: () => navigate('/imports?mode=universal&flow=budget') }
    : !annualSourceDetected
      ? { label: 'Subir presupuesto', onClick: () => navigate('/imports?mode=universal&flow=budget') }
      : !structureValidated
        ? { label: 'Corregir carga', onClick: () => navigate('/imports?mode=universal&flow=budget') }
        : !comparisonReady
          ? { label: 'Abrir analisis tecnico', onClick: () => navigate('/budget/dashboard') }
          : { label: 'Descargar informe anual', onClick: handleDownloadAnnualReport }

  const annualOpsOpen = analysisLoading || wrongAnnualSource || needsValidation
  const annualGuidanceOpen = annualOpsOpen || !comparisonLive

  return (
    <div className="annual-budget-page">
      <PageHeader
        title="Plan anual"
        subtitle="Presupuesto, caja y desvio real en una lectura anual ejecutiva."
        actions={
          <div className="annual-top-actions">
            <span className={statusBadgeClassName}>{statusTitle}</span>
            {comparisonLive ? <span className="badge badge-ok">Comparativa activa</span> : null}
          </div>
        }
      />

      {!hasGold ? <Alert tone="warning">Disponible desde Gold.</Alert> : null}
      {!companyId ? <Alert tone="warning">Selecciona una empresa.</Alert> : null}
      {analysisLoading ? <Alert tone="info">Leyendo la ultima carga anual. En XLSX complejos puede tardar un poco.</Alert> : null}
      {cameFromAnnualUpload && annualSourceDetected ? (
        <Alert tone="info">Presupuesto detectado. Esta pantalla ya relee la ultima carga anual valida.</Alert>
      ) : null}
      {wrongAnnualSource ? (
        <Alert tone="warning">
          La ultima carga no encaja como plan anual. Corrige la carga desde Cargar datos o sube un presupuesto anual real.
        </Alert>
      ) : null}
      {showRecoveryHint ? (
        <Alert tone="info">El fichero anual ya esta cargado. Si aun no ves una lectura util, abre el analisis tecnico o corrige hoja y cabecera.</Alert>
      ) : null}
      {workflowError ? <Alert tone="danger">{formatText(String((workflowError as any)?.message || workflowError))}</Alert> : null}

      <div className="card section soft annual-hero-shell">
        <div className="annual-hero-grid">
          <div className="annual-hero-copy">
            <span className="annual-eyebrow">Plan anual</span>
            <div className="annual-hero-title">{analysisLoading ? 'Leyendo presupuesto anual' : statusTitle}</div>
            <div className="annual-hero-detail">
              {analysisLoading ? 'Estoy reconstruyendo la lectura canonica y la tesoreria del ultimo fichero anual.' : statusDetail}
            </div>
            <div className="annual-hero-tags">
              <span className="annual-hero-tag">{annualSourceDetected ? 'Fuente detectada' : 'Sin fuente anual'}</span>
              <span className="annual-hero-tag">{plannedMonthsAvailable} meses listos</span>
              <span className="annual-hero-tag">{comparisonLive ? 'Comparativa activa' : 'Sin contraste completo'}</span>
            </div>
            <div className="row row-wrap gap-8 mt-12 annual-hero-actions">
              <Button size="sm" variant="secondary" loading={downloading} onClick={primaryAction.onClick}>
                {primaryAction.label}
              </Button>
              {comparisonLive ? (
                <Button size="sm" variant="ghost" onClick={() => navigate('/budget/dashboard')}>
                  Abrir analisis tecnico
                </Button>
              ) : null}
            </div>
          </div>
          <div className="annual-hero-side">
            <div className="annual-side-heading">
              <span>Base anual</span>
              <strong>Fuente, meses y estado de lectura.</strong>
            </div>
            <div className="annual-side-meta mt-12">
              <div>
                <span className="annual-card-label">Fuente</span>
                <strong>{analysisLoading ? 'Procesando...' : formatText(sourceFilename, 'Sin fichero anual')}</strong>
                <span className="annual-card-note">{analysisLoading ? 'Actualizando lectura...' : sourceCreatedAt ? formatDateTime(sourceCreatedAt) : 'Carga pendiente.'}</span>
              </div>
              <div>
                <span className="annual-card-label">Meses listos</span>
                <strong>{analysisLoading ? '...' : plannedMonthsAvailable}</strong>
                <span className="annual-card-note">{analysisLoading ? 'Preparando lectura anual...' : `${actualMonthsAvailable} con contraste`}</span>
              </div>
              <div>
                <span className="annual-card-label">Estado real</span>
                <strong>{analysisLoading ? 'En proceso' : comparisonLive ? 'Comparativa activa' : annualSourceDetected ? 'Carga anual dentro' : 'Sin lectura anual'}</strong>
                <span className="annual-card-note">
                  {analysisLoading
                    ? 'La vista se refresca al terminar.'
                    : needsValidation
                    ? 'Falta fijar hoja o cabecera.'
                    : comparisonLive
                    ? 'Lista para explicar el ejercicio.'
                    : 'Esperando una lectura anual valida.'}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <details className="card section soft annual-fold-panel" open={annualOpsOpen}>
        <summary>
          <div className="annual-fold-summary">
            <strong>Control de origen</strong>
            <span>Hoja, cabecera y trazabilidad solo cuando hacen falta.</span>
          </div>
          <span className="badge">{annualOpsOpen ? 'abierto' : 'detalle'}</span>
        </summary>
        <div className="annual-fold-body">
          <div className="grid grid-autofit-220">
            <div className="card soft card-pad-sm annual-info-card">
              <div className="annual-card-label">Situacion</div>
              <div className="annual-card-title">{analysisLoading ? 'Leyendo presupuesto anual' : statusTitle}</div>
              <div className="annual-card-note">
                {analysisLoading ? 'Estoy reconstruyendo la lectura canonica y la tesoreria del ultimo fichero anual.' : statusDetail}
              </div>
            </div>
            <div className="card soft card-pad-sm annual-info-card">
              <div className="annual-card-label">Fuente</div>
              <div className="annual-card-title">{analysisLoading ? 'Procesando ultima carga anual' : formatText(sourceFilename, 'Sin fichero anual')}</div>
              <div className="annual-card-note">{analysisLoading ? 'La vista se actualizara sola cuando termine.' : sourceCreatedAt ? formatDateTime(sourceCreatedAt) : 'Carga pendiente.'}</div>
              {sourceSheetIndex != null || sourceHeaderRow != null ? (
                <div className="annual-card-note">
                  Ultimo intento usado: hoja {sourceSheetIndex != null ? Number(sourceSheetIndex) + 1 : '-'} | cabecera fila {sourceHeaderRow ?? '-'}
                </div>
              ) : null}
              {workflow?.sourceAttemptTrendTitle ? (
                <div className="annual-card-note">
                  <strong>{formatText(workflow.sourceAttemptTrendTitle, '')}.</strong> {formatText(workflow?.sourceAttemptTrendDetail, '')}
                </div>
              ) : null}
            </div>
            <div className="card soft card-pad-sm annual-info-card">
              <div className="annual-card-label">Meses comparables</div>
              <div className="annual-card-title">{analysisLoading ? '...' : comparison?.commonMonths ?? 0}</div>
              <div className="annual-card-note">
                {analysisLoading ? 'Preparando lectura anual...' : `${plannedMonthsAvailable} plan | ${actualMonthsAvailable} contraste`}
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
                Abrir analisis tecnico
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
            <div className="annual-inline-note">
              Que probar ahora: si el Excel tiene varias hojas, empieza por <strong>Elegir otra hoja</strong>. Si la hoja es correcta pero los meses no se detectan bien, prueba <strong>Cambiar fila de cabecera</strong>.
            </div>
          ) : null}
        </div>
      </details>

      <details className="card section soft annual-fold-panel" open={annualGuidanceOpen}>
        <summary>
          <div className="annual-fold-summary">
            <strong>Siguiente paso</strong>
            <span>La accion mas util segun el estado real del flujo.</span>
          </div>
          <span className="badge">{comparisonLive ? 'listo' : needsValidation ? 'revisar' : 'siguiente paso'}</span>
        </summary>
        <div className="annual-fold-body">
          <div className="card soft annual-stage-shell">
            <div className="annual-card-label">
              {comparisonLive ? 'Comparativa activa' : needsValidation ? 'Bloqueo operativo' : 'Siguiente paso'}
            </div>
            <div className="annual-stage-title">{nextStepTitle}</div>
            <div className="annual-stage-detail">{nextStepDetail}</div>
            <div className="grid grid-autofit-220 mt-12">
              <div className="card soft card-pad-sm annual-info-card">
                <div className="annual-card-label">Hoja usada</div>
                <div className="annual-card-title">{sourceSheetIndex != null ? `Hoja ${Number(sourceSheetIndex) + 1}` : EMPTY_DATA_TEXT}</div>
                <div className="annual-card-note">Ultimo intento guardado</div>
              </div>
              <div className="card soft card-pad-sm annual-info-card">
                <div className="annual-card-label">Cabecera usada</div>
                <div className="annual-card-title">{sourceHeaderRow != null ? `Fila ${sourceHeaderRow}` : EMPTY_DATA_TEXT}</div>
                <div className="annual-card-note">Punto de arranque del parseo</div>
              </div>
              <div className="card soft card-pad-sm annual-info-card">
                <div className="annual-card-label">Meses listos</div>
                <div className="annual-card-title">{plannedMonthsAvailable}</div>
                <div className="annual-card-note">Meses del plan ya reconocidos</div>
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
                  Ver analisis anual
                </Button>
                <Button size="sm" variant="ghost" loading={downloading} onClick={handleDownloadAnnualReport}>
                  Descargar informe anual
                </Button>
              </div>
            ) : null}
          </div>
        </div>
      </details>

      <Section
        title="3. KPIs clave"
        subtitle={
          comparisonLive
            ? 'Tres cifras para explicar el ejercicio sin ruido.'
            : 'Las tres cifras que merece la pena mirar ahora.'
        }
      >
        <div className="grid annual-kpi-grid mt-12">
          <div className="card soft card-pad-sm annual-kpi-card">
            <div className="annual-card-label">Margen previsto</div>
            <div className="annual-kpi-value">{formatMoney(summary?.totalMargin)}</div>
            <div className="annual-card-note">Base anual agregada</div>
          </div>
          <div className="card soft card-pad-sm annual-kpi-card">
            <div className="annual-card-label">Neto YTD vs plan</div>
            <div className="annual-kpi-value">{fmtDeltaMoney(comparison?.netVarianceYtd)}</div>
            <div className="annual-card-note">Desviacion acumulada</div>
          </div>
          <div className="card soft card-pad-sm annual-kpi-card">
            <div className="annual-card-label">Saldo final YTD</div>
            <div className="annual-kpi-value">{fmtDeltaMoney(comparison?.endingBalanceVarianceYtd)}</div>
            <div className="annual-card-note">Caja frente a objetivo</div>
          </div>
        </div>
      </Section>

      {comparisonLive ? (
        <Section title="4. Comparativa real vs presupuesto" subtitle="Dos vistas para ver si el plan aguanta o se desvia.">
          {drilldownCards.length ? (
            <div className="grid annual-drill-grid mb-3">
              {drilldownCards.map((card) => (
                <div key={card.key} className={`card soft annual-drill-card annual-drill-card-${card.tone}`}>
                  <div className="annual-card-label">{card.eyebrow}</div>
                  <div className="annual-stage-title">{card.title}</div>
                  <div className="annual-stage-detail">{card.detail}</div>
                  <div className="annual-drill-actions mt-12">
                    <Button size="sm" variant="secondary" onClick={() => openMonthAnalysis(card.month)}>
                      Abrir mes
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          ) : null}
          <div className="annual-chart-grid">
            <div className="card annual-chart-card">
              <h3 className="h3-reset">Neto previsto vs real</h3>
              <div className="annual-card-note">Cada mes muestra si el neto real queda por encima o por debajo del plan.</div>
              <EChart
                module="budget"
                valueSuffix="€"
                height={320}
                option={netChart as any}
                onClick={(params) => {
                  const clicked = comparisonMonthsByLabel.get(String(params?.name || ''))
                  if (clicked) openMonthAnalysis(clicked)
                }}
              />
            </div>
            <div className="card annual-chart-card">
              <h3 className="h3-reset">Saldo previsto vs real</h3>
              <div className="annual-card-note">Permite ver si la caja prevista se sostiene con el comportamiento real.</div>
              <EChart
                module="budget"
                valueSuffix="€"
                height={320}
                option={balanceChart as any}
                onClick={(params) => {
                  const clicked = comparisonMonthsByLabel.get(String(params?.name || ''))
                  if (clicked) openMonthAnalysis(clicked)
                }}
              />
            </div>
          </div>
        </Section>
      ) : null}
    </div>
  )
}




