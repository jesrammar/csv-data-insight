import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { getUniversalLineage, getUniversalViewDataForImport, getUniversalViewEvidenceForImport, listUniversalImports, type UniversalChartData, type UniversalEvidenceDto, type UniversalImportDto } from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import EChart from '../components/charts/EChart'
import ChartNarrative from '../components/charts/ChartNarrative'
import LineagePanel from '../components/charts/LineagePanel'
import ExplainThisChart from '../components/charts/ExplainThisChart'
import Reveal from '../components/ui/Reveal'
import { buildUniversalChartNarrative } from '../utils/universalChartNarrative'

function asNum(x: string | undefined) {
  const n = Number(x)
  return Number.isFinite(n) ? n : null
}

export default function UniversalViewPage() {
  const { id: companyId, plan } = useCompanySelection()
  const { viewId } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const id = asNum(viewId)
  const overrideImportId = asNum(searchParams.get('importId') || undefined)

  const { data, error, isPending, refetch } = useQuery({
    queryKey: ['universal-view', companyId, id, overrideImportId ?? 'template'],
    queryFn: () => getUniversalViewDataForImport(companyId as number, id as number, overrideImportId),
    enabled: !!companyId && !!id
  })

  const { data: imports } = useQuery({
    queryKey: ['universal-imports', companyId],
    queryFn: () => listUniversalImports(companyId as number),
    enabled: !!companyId
  })

  const { data: lineage } = useQuery({
    queryKey: ['universal-lineage', companyId, overrideImportId ?? 'latest'],
    queryFn: () => getUniversalLineage(companyId as number, overrideImportId),
    enabled: !!companyId
  })

  const importsList = (imports || []) as UniversalImportDto[]

  const chart = (data && typeof data === 'object' ? (data as UniversalChartData) : undefined) as UniversalChartData | undefined
  const labels = chart?.labels || []
  const series0 = (chart?.series as any)?.[0] || null
  const values = series0?.data || []
  const t = String(chart?.type || '').toUpperCase()
  const isBar = t.includes('CATEGORY')
  const isAdvancedType = t === 'SCATTER' || t === 'HEATMAP' || t === 'PIVOT_MONTHLY'
  const canUseEvidence = plan === 'GOLD' || plan === 'PLATINUM'

  const lastLabel = labels.length ? String(labels[labels.length - 1] || '') : ''
  const [focusLabel, setFocusLabel] = useState(lastLabel)

  useEffect(() => {
    setFocusLabel(lastLabel)
  }, [lastLabel])

  const narrative = useMemo(() => buildUniversalChartNarrative(chart, focusLabel), [chart, focusLabel])

  const [evidence, setEvidence] = useState<UniversalEvidenceDto | null>(null)
  const [evidenceLoading, setEvidenceLoading] = useState(false)
  const [evidenceError, setEvidenceError] = useState<string | null>(null)
  const [autoEvidence, setAutoEvidence] = useState(true)
  const evidenceRequestSeq = useRef(0)

  useEffect(() => {
    evidenceRequestSeq.current += 1
    setEvidence(null)
    setEvidenceError(null)
  }, [id, overrideImportId, t])

  useEffect(() => {
    if (!canUseEvidence) return
    if (!autoEvidence) return
    const focus = String(focusLabel || '').trim()
    if (!focus) return
    const timer = window.setTimeout(() => {
      loadEvidence(focus)
    }, 350)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canUseEvidence, autoEvidence, focusLabel, t, id, overrideImportId, companyId])

  async function loadEvidence(explicitFocus?: string) {
    if (!companyId || !id) return
    if (!canUseEvidence) return
    const focus = String(explicitFocus ?? focusLabel ?? '').trim()
    if (!focus) return
    const requestId = ++evidenceRequestSeq.current
    setEvidenceLoading(true)
    setEvidenceError(null)
    try {
      const res = await getUniversalViewEvidenceForImport(companyId as number, id as number, focus, 40, overrideImportId)
      if (requestId !== evidenceRequestSeq.current) return
      setEvidence(res)
    } catch (e: any) {
      if (requestId !== evidenceRequestSeq.current) return
      setEvidence(null)
      setEvidenceError(e?.message || 'No se pudo cargar evidencia.')
    } finally {
      if (requestId !== evidenceRequestSeq.current) return
      setEvidenceLoading(false)
    }
  }

  function downloadEvidenceCsv(ev: UniversalEvidenceDto) {
    const headers = Array.isArray(ev.headers) ? ev.headers : []
    const rows = Array.isArray(ev.rows) ? ev.rows : []
    const esc = (s: any) => {
      const v = String(s ?? '')
      const needs = v.includes('"') || v.includes(',') || v.includes('\n') || v.includes('\r')
      const q = v.replace(/"/g, '""')
      return needs ? `"${q}"` : q
    }
    const csv = [headers.map(esc).join(','), ...rows.map((r) => (Array.isArray(r) ? r : []).map(esc).join(','))].join('\n')
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `universal-evidencia-${new Date().toISOString().slice(0, 10)}.csv`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  return (
    <div>
      <PageHeader
        title="Dashboard Universal"
        subtitle="Vista compartible a partir de plantilla guardada."
        actions={
          <div className="row row-center row-wrap gap-10">
            <span className="badge">{plan}</span>
            <Link className="badge" to="/universal/views">
              Mis dashboards
            </Link>
            {companyId ? (
              <select
                value={overrideImportId ?? ''}
                onChange={(e) => {
                  const raw = String(e.target.value || '').trim()
                  const next = raw ? Number(raw) : null
                  const sp = new URLSearchParams(searchParams)
                  if (!raw) sp.delete('importId')
                  else sp.set('importId', String(next))
                  setSearchParams(sp, { replace: true })
                }}
                title="Opcional: aplicar esta plantilla a otro dataset (import)"
              >
                <option value="">Dataset del dashboard</option>
                {importsList.slice(0, 20).map((imp) => (
                  <option key={imp.id} value={imp.id}>
                    #{imp.id} · {imp.filename}
                  </option>
                ))}
              </select>
            ) : null}
            <button
              className="badge"
              onClick={() => {
                try {
                  navigator.clipboard.writeText(window.location.href)
                } catch {}
              }}
              title="Copiar enlace"
            >
              Copiar enlace
            </button>
            <button className="badge" onClick={() => refetch()} disabled={!companyId || !id || isPending}>
              Refrescar
            </button>
          </div>
        }
      />

      {!companyId ? (
        <Alert tone="warning" title="Falta seleccionar empresa">
          Selecciona una empresa para abrir el dashboard.
        </Alert>
      ) : null}

      {!id ? (
        <Alert tone="danger" title="ID invalido">
          URL invalida.
        </Alert>
      ) : null}

      {error ? (
        <div className="mt-12">
          <Alert tone="danger">{String((error as any)?.message || error)}</Alert>
        </div>
      ) : null}

      {isPending ? (
        <div className="card section" aria-busy="true">
          <div className="empty">Cargando dashboard...</div>
        </div>
      ) : null}

      {!isPending && !error && companyId && id && !chart ? (
        <div className="mt-12">
          <Alert tone="warning" title="Sin datos para este dashboard">
            Este dashboard se calcula con el ultimo dataset subido en Universal. Sube o vuelve a subir un fichero en Universal y abre este enlace de nuevo.
          </Alert>
        </div>
      ) : null}

      {chart && plan === 'BRONZE' && isAdvancedType ? (
        <div className="mt-12">
          <Alert tone="warning" title="Grafico avanzado">
            Este dashboard usa un tipo de grafico avanzado (scatter, heatmap o pivote). En plan BRONZE se recomienda usar series temporales o rankings para que se entienda solo.
          </Alert>
        </div>
      ) : null}

      {chart && t === 'KPI_CARDS' ? (
        <Reveal className="card section">
          <h3 className="h3-reset">KPIs</h3>
          <div className="grid grid-min-160 grid-gap-12">
            {labels.map((k, idx) => (
              <div key={`${k}-${idx}`} className="card soft">
                <div className="upload-hint ttu">{k}</div>
                <div className="fs-22 fw-700 mt-1">{String(values?.[idx] ?? '-')}</div>
              </div>
            ))}
          </div>
          <ExplainThisChart see={narrative.see} why={narrative.why} todo={narrative.todo} focusLabel={focusLabel} className="mt-2" />
          <details className="mt-12">
            <summary className="upload-hint cursor-pointer">Más contexto</summary>
            <ChartNarrative title="Lectura rápida" see={narrative.see} why={narrative.why} todo={narrative.todo} className="mt-12" />
          </details>

          {Array.isArray((chart.meta as any)?.warnings) && (chart.meta as any).warnings.length ? (
            <div className="mt-2">
              <Alert tone="warning" title="Avisos">
                <ul className="list-steps">
                  {(chart.meta as any).warnings.slice(0, 8).map((w: any, idx: number) => (
                    <li key={`${idx}`}>{String(w)}</li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}
        </Reveal>
      ) : chart && t === 'SCATTER' ? (
        <Reveal className="card section">
          <h3 className="h3-reset">{series0?.name || 'Scatter'}</h3>
          <EChart
            module="universal"
            height={360}
            onClick={(params) => {
              const v = params?.value
              if (Array.isArray(v) && v.length >= 2) setFocusLabel(`${v[0]},${v[1]}`)
            }}
            option={
              {
                tooltip: { trigger: 'item' },
                xAxis: { type: 'value', name: (chart.meta as any)?.xColumn || 'X' },
                yAxis: { type: 'value', name: (chart.meta as any)?.yColumn || 'Y' },
                series: [{ name: series0?.name || 'Puntos', type: 'scatter', symbolSize: 6, data: values || [] }]
              } as any
            }
          />
          <ExplainThisChart see={narrative.see} why={narrative.why} todo={narrative.todo} focusLabel={focusLabel} className="mt-2" />
          <details className="mt-12">
            <summary className="upload-hint cursor-pointer">Más contexto</summary>
            <ChartNarrative title="Lectura rápida" see={narrative.see} why={narrative.why} todo={narrative.todo} className="mt-12" />
          </details>

          {Array.isArray((chart.meta as any)?.warnings) && (chart.meta as any).warnings.length ? (
            <div className="mt-2">
              <Alert tone="warning" title="Avisos">
                <ul className="list-steps">
                  {(chart.meta as any).warnings.slice(0, 8).map((w: any, idx: number) => (
                    <li key={`${idx}`}>{String(w)}</li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}
        </Reveal>
      ) : chart && t === 'HEATMAP' ? (
        <Reveal className="card section">
          <h3 className="h3-reset">{series0?.name || 'Heatmap'}</h3>
          <EChart
            module="universal"
            height={420}
            onClick={(params) => {
              const v = params?.value
              const ix = Array.isArray(v) ? v[0] : null
              const iy = Array.isArray(v) ? v[1] : null
              const xLabel = labels?.[Number(ix)] ?? ''
              const yLabel = ((chart.meta as any)?.yLabels || [])?.[Number(iy)] ?? ''
              if (xLabel && yLabel) setFocusLabel(`${xLabel}||${yLabel}`)
            }}
            option={
              (() => {
                const pts = (values || []) as any[]
                const max = pts.reduce((acc, p) => Math.max(acc, Number(Array.isArray(p) ? p[2] : 0) || 0), 0) || 1
                return {
                  tooltip: { position: 'top' },
                  grid: { height: '70%', top: 30 },
                  xAxis: { type: 'category', data: labels, splitArea: { show: true } },
                  yAxis: { type: 'category', data: (chart.meta as any)?.yLabels || [], splitArea: { show: true } },
                  visualMap: { min: 0, max, calculable: true, orient: 'horizontal', left: 'center', bottom: 0 },
                  series: [{ name: series0?.name || 'Heatmap', type: 'heatmap', data: pts }]
                } as any
              })()
            }
          />
          <ExplainThisChart see={narrative.see} why={narrative.why} todo={narrative.todo} focusLabel={focusLabel} className="mt-2" />
          <details className="mt-12">
            <summary className="upload-hint cursor-pointer">Más contexto</summary>
            <ChartNarrative title="Lectura rápida" see={narrative.see} why={narrative.why} todo={narrative.todo} className="mt-12" />
          </details>

          {Array.isArray((chart.meta as any)?.warnings) && (chart.meta as any).warnings.length ? (
            <div className="mt-2">
              <Alert tone="warning" title="Avisos">
                <ul className="list-steps">
                  {(chart.meta as any).warnings.slice(0, 8).map((w: any, idx: number) => (
                    <li key={`${idx}`}>{String(w)}</li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}
        </Reveal>
      ) : chart && t === 'PIVOT_MONTHLY' ? (
        <Reveal className="card section">
          <h3 className="h3-reset">Tabla pivote</h3>
          <EChart
            module="universal"
            height={360}
            onClick={(params) => {
              const month = String(params?.name ?? params?.axisValue ?? '').trim()
              const cat = String(params?.seriesName ?? '').trim()
              if (cat && month) setFocusLabel(`${cat}||${month}`)
            }}
            option={
              {
                tooltip: { trigger: 'axis' },
                legend: { type: 'scroll' },
                xAxis: { type: 'category', data: labels },
                yAxis: { type: 'value' },
                series: (chart.series as any[]).map((s) => ({ name: s?.name, type: 'bar', stack: 'total', data: s?.data || [] }))
              } as any
            }
          />
          <ExplainThisChart see={narrative.see} why={narrative.why} todo={narrative.todo} focusLabel={focusLabel} className="mt-2" />
          <details className="mt-12">
            <summary className="upload-hint cursor-pointer">Más contexto</summary>
            <ChartNarrative title="Lectura rápida" see={narrative.see} why={narrative.why} todo={narrative.todo} className="mt-12" />
          </details>
          <div className="overflow-auto mt-12">
            <table className="table">
              <thead>
                <tr>
                    <th>Categoria</th>
                  {labels.map((m) => (
                    <th key={m}>{m}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(chart.series as any[]).map((s, idx) => (
                  <tr key={`${s?.name || idx}`}>
                    <td>{s?.name || `S${idx + 1}`}</td>
                    {(s?.data || []).map((v: any, j: number) => (
                      <td key={`${idx}-${j}`}>{String(v ?? '')}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          
          {Array.isArray((chart.meta as any)?.warnings) && (chart.meta as any).warnings.length ? (
            <div className="mt-2">
              <Alert tone="warning" title="Avisos">
                <ul className="list-steps">
                  {(chart.meta as any).warnings.slice(0, 8).map((w: any, idx: number) => (
                    <li key={`${idx}`}>{String(w)}</li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}
        </Reveal>
      ) : chart && labels.length ? (
        <Reveal className="card section">
          <h3 className="h3-reset">{series0?.name || 'Grafico'}</h3>
          <EChart
            module="universal"
            height={360}
            onAxisHover={(label) => setFocusLabel(String(label || ''))}
            onLeave={() => setFocusLabel(lastLabel)}
            option={
              isBar
                ? ({
                    tooltip: { trigger: 'axis' },
                    xAxis: { type: 'category', data: labels },
                    yAxis: { type: 'value' },
                    series: [{ name: series0?.name || 'Valor', type: 'bar', data: values }]
                  } as any)
                : ({
                    tooltip: { trigger: 'axis' },
                    xAxis: { type: 'category', data: labels },
                    yAxis: { type: 'value' },
                    series: [{ name: series0?.name || 'Valor', type: 'line', smooth: true, data: values }]
                  } as any)
            }
          />
          <ExplainThisChart see={narrative.see} why={narrative.why} todo={narrative.todo} focusLabel={focusLabel} className="mt-2" />
          <details className="mt-12">
            <summary className="upload-hint cursor-pointer">Más contexto</summary>
            <ChartNarrative title="Lectura rápida" see={narrative.see} why={narrative.why} todo={narrative.todo} className="mt-12" />
          </details>

          {Array.isArray((chart.meta as any)?.warnings) && (chart.meta as any).warnings.length ? (
            <div className="mt-2">
              <Alert tone="warning" title="Avisos">
                <ul className="list-steps">
                  {(chart.meta as any).warnings.slice(0, 8).map((w: any, idx: number) => (
                    <li key={`${idx}`}>{String(w)}</li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}
        </Reveal>
      ) : chart && !labels.length ? (
        <Reveal className="card section">
          <div className="empty">Sin datos para graficar.</div>
        </Reveal>
      ) : null}

      {chart ? (
        <Reveal delay={1}>
          <LineagePanel lineage={lineage as any} chart={chart as any} />
        </Reveal>
      ) : null}

      {chart && canUseEvidence ? (
        <Reveal delay={2} className="card section soft">
          <div className="row row-between row-center row-wrap gap-10">
            <div>
              <div className="fw-800">Evidencia (GOLD+)</div>
              <div className="upload-hint">Filas detras del punto o etiqueta: {focusLabel || '-'}</div>
            </div>
            <div className="row row-wrap gap-2">
              <label className="upload-hint row row-center gap-6">
                <input
                  type="checkbox"
                  checked={autoEvidence}
                  onChange={(e) => setAutoEvidence(Boolean((e.target as any)?.checked))}
                />
                Auto
              </label>
              <button className="badge" onClick={() => loadEvidence()} disabled={evidenceLoading || !focusLabel}>
                {evidenceLoading ? 'Cargando...' : 'Ver evidencia'}
              </button>
              {evidence?.rows?.length ? (
                <button className="badge" onClick={() => downloadEvidenceCsv(evidence)}>
                  Descargar CSV
                </button>
              ) : null}
            </div>
          </div>
          {evidenceError ? (
            <div className="mt-12">
              <Alert tone="danger" title="Evidencia">
                {evidenceError}
              </Alert>
            </div>
          ) : null}
          {evidence?.rows?.length ? (
            <details className="card soft mt-12">
              <summary className="upload-hint cursor-pointer">
                Ver tabla · {evidence.rows.length} filas
              </summary>
              <div className="overflow-auto mt-12">
                <table className="table">
                  <thead>
                    <tr>
                      <th>#</th>
                      {(evidence.headers || []).map((h) => (
                        <th key={h}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {(evidence.rows || []).slice(0, 60).map((r, idx) => (
                      <tr key={`${idx}`}>
                        <td className="upload-hint">{String((evidence.rowNumbers || [])[idx] ?? '')}</td>
                        {(r || []).map((cell, j) => (
                          <td key={`${idx}-${j}`}>{String(cell ?? '')}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </details>
          ) : null}
        </Reveal>
      ) : chart ? (
        <Reveal delay={2} className="card section soft">
          <div className="row row-between row-center row-wrap gap-10">
            <div>
              <div className="fw-800">Evidencia</div>
              <div className="upload-hint">Disponible en planes GOLD y PLATINUM.</div>
            </div>
            <span className="badge">Upgrade requerido</span>
          </div>
        </Reveal>
      ) : null}
    </div>
  )
}

