import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { getUniversalViewDataForImport, listUniversalImports, type UniversalChartData, type UniversalImportDto } from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import EChart from '../components/charts/EChart'

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

  const importsList = (imports || []) as UniversalImportDto[]

  const chart = (data && typeof data === 'object' ? (data as UniversalChartData) : undefined) as UniversalChartData | undefined
  const labels = chart?.labels || []
  const series0 = (chart?.series as any)?.[0] || null
  const values = series0?.data || []
  const t = String(chart?.type || '').toUpperCase()
  const isBar = t.includes('CATEGORY')

  return (
    <div>
      <PageHeader
        title="Dashboard Universal"
        subtitle="Vista compartible a partir de plantilla guardada."
        actions={
          <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
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
        <Alert tone="danger" title="ID inválido">
          URL inválida.
        </Alert>
      ) : null}

      {error ? (
        <div style={{ marginTop: 12 }}>
          <Alert tone="danger">{String((error as any)?.message || error)}</Alert>
        </div>
      ) : null}

      {isPending ? (
        <div className="card section" aria-busy="true">
          <div className="empty">Cargando dashboard…</div>
        </div>
      ) : null}

      {!isPending && !error && companyId && id && !chart ? (
        <div style={{ marginTop: 12 }}>
          <Alert tone="warning" title="Sin datos para este dashboard">
            Este dashboard se calcula con el último dataset subido en Universal. Sube (o re‑sube) un fichero en Universal y vuelve a abrir este enlace.
          </Alert>
        </div>
      ) : null}

      {chart && t === 'KPI_CARDS' ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>KPIs</h3>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(160px,1fr))', gap: 12 }}>
            {labels.map((k, idx) => (
              <div key={`${k}-${idx}`} className="card soft">
                <div className="upload-hint" style={{ textTransform: 'uppercase' }}>
                  {k}
                </div>
                <div style={{ fontSize: 22, fontWeight: 700, marginTop: 6 }}>{String(values?.[idx] ?? '—')}</div>
              </div>
            ))}
          </div>
          {chart.meta ? (
            <div className="upload-hint" style={{ marginTop: 10 }}>
              {Object.entries(chart.meta)
                .slice(0, 6)
                .map(([k, v]) => `${k}=${String(v)}`)
                .join(' • ')}
            </div>
          ) : null}
          {Array.isArray((chart.meta as any)?.warnings) && (chart.meta as any).warnings.length ? (
            <div style={{ marginTop: 10 }}>
              <Alert tone="warning" title="Avisos">
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {(chart.meta as any).warnings.slice(0, 8).map((w: any, idx: number) => (
                    <li key={`${idx}`}>{String(w)}</li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}
        </div>
      ) : chart && t === 'SCATTER' ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>{series0?.name || 'Scatter'}</h3>
          <EChart
            style={{ height: 360 }}
            option={
              {
                tooltip: { trigger: 'item' },
                xAxis: { type: 'value', name: (chart.meta as any)?.xColumn || 'X' },
                yAxis: { type: 'value', name: (chart.meta as any)?.yColumn || 'Y' },
                series: [{ name: series0?.name || 'Puntos', type: 'scatter', symbolSize: 6, data: values || [] }]
              } as any
            }
          />
          {chart.meta ? (
            <div className="upload-hint" style={{ marginTop: 10 }}>
              {Object.entries(chart.meta)
                .slice(0, 6)
                .map(([k, v]) => `${k}=${String(v)}`)
                .join(' • ')}
            </div>
          ) : null}
          {Array.isArray((chart.meta as any)?.warnings) && (chart.meta as any).warnings.length ? (
            <div style={{ marginTop: 10 }}>
              <Alert tone="warning" title="Avisos">
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {(chart.meta as any).warnings.slice(0, 8).map((w: any, idx: number) => (
                    <li key={`${idx}`}>{String(w)}</li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}
        </div>
      ) : chart && t === 'HEATMAP' ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>{series0?.name || 'Heatmap'}</h3>
          <EChart
            style={{ height: 420 }}
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
          {chart.meta ? (
            <div className="upload-hint" style={{ marginTop: 10 }}>
              {Object.entries(chart.meta)
                .slice(0, 6)
                .map(([k, v]) => `${k}=${String(v)}`)
                .join(' • ')}
            </div>
          ) : null}
          {Array.isArray((chart.meta as any)?.warnings) && (chart.meta as any).warnings.length ? (
            <div style={{ marginTop: 10 }}>
              <Alert tone="warning" title="Avisos">
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {(chart.meta as any).warnings.slice(0, 8).map((w: any, idx: number) => (
                    <li key={`${idx}`}>{String(w)}</li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}
        </div>
      ) : chart && t === 'PIVOT_MONTHLY' ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>Tabla pivote</h3>
          <EChart
            style={{ height: 360 }}
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
          <div style={{ overflowX: 'auto', marginTop: 12 }}>
            <table className="table">
              <thead>
                <tr>
                  <th>Categoría</th>
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
          {chart.meta ? (
            <div className="upload-hint" style={{ marginTop: 10 }}>
              {Object.entries(chart.meta)
                .slice(0, 6)
                .map(([k, v]) => `${k}=${String(v)}`)
                .join(' • ')}
            </div>
          ) : null}
          {Array.isArray((chart.meta as any)?.warnings) && (chart.meta as any).warnings.length ? (
            <div style={{ marginTop: 10 }}>
              <Alert tone="warning" title="Avisos">
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {(chart.meta as any).warnings.slice(0, 8).map((w: any, idx: number) => (
                    <li key={`${idx}`}>{String(w)}</li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}
        </div>
      ) : chart && labels.length ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>{series0?.name || 'Gráfico'}</h3>
          <EChart
            style={{ height: 360 }}
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
          {chart.meta ? (
            <div className="upload-hint" style={{ marginTop: 10 }}>
              {Object.entries(chart.meta)
                .slice(0, 6)
                .map(([k, v]) => `${k}=${String(v)}`)
                .join(' • ')}
            </div>
          ) : null}
          {Array.isArray((chart.meta as any)?.warnings) && (chart.meta as any).warnings.length ? (
            <div style={{ marginTop: 10 }}>
              <Alert tone="warning" title="Avisos">
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {(chart.meta as any).warnings.slice(0, 8).map((w: any, idx: number) => (
                    <li key={`${idx}`}>{String(w)}</li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}
        </div>
      ) : chart && !labels.length ? (
        <div className="card section">
          <div className="empty">Sin datos para graficar.</div>
        </div>
      ) : null}
    </div>
  )
}
