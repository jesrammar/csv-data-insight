import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useCompanySelection } from '../hooks/useCompany'
import { listAutomationJobs, runMonthlyReport, runRecomputeKpis, runSnapshotRecommendations } from '../api'
import { useToast } from '../components/ui/ToastProvider'
import { getWorkPeriod, nowYm } from '../utils/workPeriod'

function nowYmMinus(months: number) {
  const d = new Date()
  const ym = new Date(d.getFullYear(), d.getMonth() - months, 1)
  const y = ym.getFullYear()
  const m = String(ym.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

export default function AutomationPage() {
  const { id: companyId, plan } = useCompanySelection()
  const toast = useToast()
  const [monthsBack, setMonthsBack] = useState(2)
  const [reportPeriod, setReportPeriod] = useState(() => getWorkPeriod(companyId) || nowYmMinus(1))
  const [recPeriod, setRecPeriod] = useState(() => getWorkPeriod(companyId) || nowYm())
  const [recObjective, setRecObjective] = useState<'GENERAL' | 'CASH' | 'COST' | 'MARGIN' | 'GROWTH' | 'RISK'>('GENERAL')

  useEffect(() => {
    if (!companyId) return
    const p = getWorkPeriod(companyId) || nowYmMinus(1)
    setReportPeriod(p)
    setRecPeriod(getWorkPeriod(companyId) || nowYm())
  }, [companyId])

  const { data: jobs, error, refetch, isFetching } = useQuery({
    queryKey: ['automation-jobs', companyId],
    queryFn: () => listAutomationJobs(companyId as number),
    enabled: !!companyId
  })

  const rows = useMemo(() => (Array.isArray(jobs) ? jobs : jobs?.value || []), [jobs])

  const typeLabel = (t: string) => {
    const v = String(t || '').toUpperCase()
    if (v.includes('RECOMPUTE') && v.includes('KPI')) return 'Recalcular KPIs'
    if (v.includes('MONTH') && v.includes('REPORT')) return 'Informe mensual'
    if (v.includes('SNAPSHOT') && v.includes('RECOMMEND')) return 'Snapshot recomendaciones'
    return String(t || '—')
  }

  const statusTone = (s: string) => {
    const v = String(s || '').toUpperCase()
    if (v === 'SUCCESS') return 'ok'
    if (v === 'RETRY') return 'warn'
    if (v === 'DEAD' || v === 'ERROR' || v === 'FAILED') return 'err'
    return ''
  }

  const copyText = async (label: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      toast.push({ tone: 'success', title: 'Copiado', message: `${label} copiado.` })
    } catch {
      toast.push({ tone: 'warning', title: 'Copia manual', message: 'No se pudo copiar automáticamente.' })
    }
  }

  const recompute = useMutation({
    mutationFn: () => runRecomputeKpis(companyId as number, monthsBack),
    onSuccess: async () => {
      toast.push({ tone: 'success', title: 'Automatización', message: 'Job de KPIs encolado.' })
      await refetch()
    },
    onError: (e: any) => toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo encolar.' })
  })

  const monthly = useMutation({
    mutationFn: () => runMonthlyReport(companyId as number, reportPeriod),
    onSuccess: async () => {
      toast.push({ tone: 'success', title: 'Automatización', message: 'Job de informe mensual encolado.' })
      await refetch()
    },
    onError: (e: any) => toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo encolar.' })
  })

  const snapshot = useMutation({
    mutationFn: () => runSnapshotRecommendations(companyId as number, recPeriod, recObjective),
    onSuccess: async () => {
      toast.push({ tone: 'success', title: 'Automatización', message: 'Job de recomendaciones encolado.' })
      await refetch()
    },
    onError: (e: any) => toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo encolar.' })
  })

  return (
    <div>
      <PageHeader
        title="Operaciones · Automatización"
        subtitle="Pantalla avanzada para forzar/reintentar jobs (KPIs, informes, snapshots) cuando algo no corre solo."
        actions={<span className="badge">{plan}</span>}
      />

      {!companyId ? (
        <Alert tone="warning">Selecciona una empresa arriba para operar la automatización.</Alert>
      ) : null}
      {error ? <Alert tone="danger">No se pudieron cargar los jobs: {String((error as any).message)}</Alert> : null}

      <Alert tone="info" title="¿Cuándo usar esto?">
        <div className="upload-hint mt-8">
          Solo si acabas de subir datos y quieres resultados ya, o si un job se quedó en RETRY/DEAD. Si no sabes qué es, probablemente no lo
          necesitas.
        </div>
      </Alert>

      <div className="grid section">
        <div className="card">
          <h3 className="h3-reset">Ejecutar ahora</h3>
          <div className="upload-hint mt-8">
            Úsalo si acabas de subir datos y quieres forzar cálculo/entregables sin esperar al scheduler.
          </div>
          <div className="upload-row tight">
            <label className="row row-center gap-8">
              <span className="w-140">Recalcular KPIs</span>
              <input
                type="number"
                min={1}
                max={24}
                value={monthsBack}
                onChange={(e) => setMonthsBack(Number(e.target.value))}
                className="w-90"
              />
              <small className="upload-hint">meses</small>
            </label>
            <Button onClick={() => recompute.mutate()} disabled={!companyId} loading={recompute.isPending}>
              Encolar
            </Button>
          </div>

          <div className="upload-row">
            <label className="row row-center gap-8">
              <span className="w-140">Informe mensual</span>
              <input value={reportPeriod} onChange={(e) => setReportPeriod(e.target.value)} placeholder="YYYY-MM" />
            </label>
            <Button onClick={() => monthly.mutate()} disabled={!companyId} loading={monthly.isPending}>
              Encolar
            </Button>
          </div>

          <div className="upload-row">
            <label className="row row-center gap-8">
              <span className="w-140">Recomendaciones</span>
              <select value={recObjective} onChange={(e) => setRecObjective(e.target.value as any)}>
                <option value="GENERAL">General</option>
                <option value="CASH">Caja</option>
                <option value="COST">Costes</option>
                <option value="MARGIN">Margen</option>
                <option value="GROWTH">Crecimiento</option>
                <option value="RISK">Riesgo</option>
              </select>
              <input value={recPeriod} onChange={(e) => setRecPeriod(e.target.value)} placeholder="YYYY-MM" />
            </label>
            <Button onClick={() => snapshot.mutate()} disabled={!companyId} loading={snapshot.isPending}>
              Encolar
            </Button>
          </div>

          <div className="upload-hint mt-2">
            Tip: los jobs programados también se encolan solos por cron (configurable en `application.yml`).
          </div>
        </div>

        <div className="card">
          <div className="mini-row mt-0 row-center">
            <h3 className="m-0">Estado</h3>
            <Button variant="ghost" size="sm" onClick={() => refetch()} loading={isFetching}>
              Refrescar
            </Button>
          </div>
          {!rows.length ? (
            <div className="empty mt-12">
              No hay jobs todavía.
            </div>
          ) : (
            <div className="mt-12">
              <div className="table-wrap">
                <table className="table table-fixed">
                  <thead>
                    <tr>
                      <th className="w-56">ID</th>
                      <th className="w-220">Tipo</th>
                      <th className="w-110">Estado</th>
                      <th className="w-110">Intentos</th>
                      <th className="w-160">Run after</th>
                      <th className="w-180">Trace</th>
                      <th className="w-120" />
                    </tr>
                  </thead>
                  <tbody>
                    {rows.slice(0, 20).map((j: any) => {
                      const trace = String(j.traceId || '')
                      const runAfter = String(j.runAfter || '').slice(0, 19)
                      return (
                        <tr key={j.id}>
                          <td className="mono">{j.id}</td>
                          <td title={String(j.type || '')}>{typeLabel(j.type)}</td>
                          <td>
                            <span className={`badge ${statusTone(j.status)}`}>{String(j.status || '—')}</span>
                          </td>
                          <td className="mono">
                            {j.attempts}/{j.maxAttempts}
                          </td>
                          <td className="upload-hint mono" title={runAfter}>
                            {runAfter || '—'}
                          </td>
                          <td className="upload-hint mono" title={trace}>
                            {trace ? trace.slice(0, 12) : '—'}
                          </td>
                          <td className="text-right nowrap">
                            {trace ? (
                              <Button size="sm" variant="ghost" onClick={() => copyText('Trace', trace)}>
                                Copiar
                              </Button>
                            ) : null}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>

              {rows.some((j: any) => j.lastError) ? (
                <details className="mt-12">
                  <summary className="upload-hint cursor-pointer">
                    Ver errores recientes
                  </summary>
                  <div className="card soft card-pad-sm mt-2">
                    {(rows as any[])
                      .filter((j) => j.lastError)
                      .slice(0, 3)
                      .map((j) => (
                        <div key={`err-${j.id}`} className="mb-2">
                          <div className="mini-row mt-0">
                            <span className="badge err">JOB {j.id}</span>
                            <span className="upload-hint">{typeLabel(j.type)}</span>
                          </div>
                          <div className="upload-hint mono mt-1 pre-wrap">
                            {String(j.lastError || '').slice(0, 600)}
                          </div>
                        </div>
                      ))}
                    <div className="upload-hint">Tip: usa el Trace para buscar en logs/auditoría.</div>
                  </div>
                </details>
              ) : null}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

