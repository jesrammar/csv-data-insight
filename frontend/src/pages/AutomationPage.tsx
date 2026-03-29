import { useMutation, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useCompanySelection } from '../hooks/useCompany'
import { listAutomationJobs, runMonthlyReport, runRecomputeKpis, runSnapshotRecommendations } from '../api'
import { useToast } from '../components/ui/ToastProvider'

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
  const [reportPeriod, setReportPeriod] = useState(() => nowYmMinus(1))
  const [recPeriod, setRecPeriod] = useState(() => nowYmMinus(0))
  const [recObjective, setRecObjective] = useState<'GENERAL' | 'CASH' | 'COST' | 'MARGIN' | 'GROWTH' | 'RISK'>('GENERAL')

  const { data: jobs, error, refetch, isFetching } = useQuery({
    queryKey: ['automation-jobs', companyId],
    queryFn: () => listAutomationJobs(companyId as number),
    enabled: !!companyId
  })

  const rows = useMemo(() => (Array.isArray(jobs) ? jobs : jobs?.value || []), [jobs])

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
        title="Automatización"
        subtitle="Jobs programados + cola con reintentos. Útil para operar como consultora sin tareas manuales."
        actions={<span className="badge">{plan}</span>}
      />

      {!companyId ? (
        <Alert tone="warning">Selecciona una empresa arriba para operar la automatización.</Alert>
      ) : null}
      {error ? <Alert tone="danger">No se pudieron cargar los jobs: {String((error as any).message)}</Alert> : null}

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Run now</h3>
          <div className="upload-row" style={{ marginTop: 10 }}>
            <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <span style={{ width: 140 }}>Recalcular KPIs</span>
              <input
                type="number"
                min={1}
                max={24}
                value={monthsBack}
                onChange={(e) => setMonthsBack(Number(e.target.value))}
                style={{ width: 90 }}
              />
              <small className="upload-hint">meses</small>
            </label>
            <Button onClick={() => recompute.mutate()} disabled={!companyId} loading={recompute.isPending}>
              Encolar
            </Button>
          </div>

          <div className="upload-row">
            <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <span style={{ width: 140 }}>Informe mensual</span>
              <input value={reportPeriod} onChange={(e) => setReportPeriod(e.target.value)} placeholder="YYYY-MM" />
            </label>
            <Button onClick={() => monthly.mutate()} disabled={!companyId} loading={monthly.isPending}>
              Encolar
            </Button>
          </div>

          <div className="upload-row">
            <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <span style={{ width: 140 }}>Recomendaciones</span>
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

          <div className="upload-hint" style={{ marginTop: 10 }}>
            Tip: los jobs programados también se encolan solos por cron (configurable en `application.yml`).
          </div>
        </div>

        <div className="card">
          <div className="mini-row" style={{ marginTop: 0 }}>
            <h3 style={{ margin: 0 }}>Estado</h3>
            <Button variant="ghost" size="sm" onClick={() => refetch()} loading={isFetching}>
              Refrescar
            </Button>
          </div>
          {!rows.length ? (
            <div className="empty" style={{ marginTop: 12 }}>
              No hay jobs todavía.
            </div>
          ) : (
            <div style={{ marginTop: 12 }}>
              <table className="table">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Tipo</th>
                    <th>Status</th>
                    <th>Attempts</th>
                    <th>Run after</th>
                    <th>Trace</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.slice(0, 20).map((j: any) => (
                    <tr key={j.id}>
                      <td>{j.id}</td>
                      <td>{j.type}</td>
                      <td>
                        <span
                          className={`badge ${
                            j.status === 'SUCCESS'
                              ? 'ok'
                              : j.status === 'RETRY'
                              ? 'warn'
                              : j.status === 'DEAD'
                              ? 'err'
                              : ''
                          }`}
                        >
                          {j.status}
                        </span>
                      </td>
                      <td>
                        {j.attempts}/{j.maxAttempts}
                      </td>
                      <td className="upload-hint">{String(j.runAfter || '').slice(0, 19)}</td>
                      <td className="upload-hint">{String(j.traceId || '').slice(0, 12)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {rows.some((j: any) => j.lastError) ? (
                <div className="empty" style={{ marginTop: 12 }}>
                  Hay errores en jobs recientes. Revisa `lastError` desde API o logs del backend.
                </div>
              ) : null}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

