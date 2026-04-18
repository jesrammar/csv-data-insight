import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import { getAlerts } from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import Alert from '../components/ui/Alert'
import Card from '../components/ui/Card'
import PageHeader from '../components/ui/PageHeader'
import Table from '../components/ui/Table'
import { formatIsoDateTime } from '../utils/format'

export default function AlertsPage() {
  const { id: companyId, plan } = useCompanySelection()
  const [params, setParams] = useSearchParams()
  const period = (params.get('period') || '').trim()

  const { data, error, isLoading } = useQuery({
    queryKey: ['alerts', companyId, period || 'all'],
    queryFn: () => getAlerts(companyId as number, period || undefined),
    enabled: !!companyId
  })

  const rows = useMemo(() => (Array.isArray(data) ? data : []), [data])

  return (
    <div>
      <PageHeader
        title="Alertas"
        subtitle="Riesgos y avisos que requieren atención (caja, anomalías, reglas)."
        actions={
          <div className="row row-wrap row-center row-end gap-2">
            {period ? (
              <button
                className="badge"
                onClick={() => {
                  params.delete('period')
                  setParams(params, { replace: true })
                }}
                title="Quitar filtro"
              >
                Periodo: {period} · Ver todo
              </button>
            ) : null}
            <span className="badge">{(plan || 'BRONZE').toUpperCase()}</span>
          </div>
        }
      />

      {!companyId ? (
        <Alert tone="warning" title="Falta seleccionar empresa">
          Selecciona una empresa arriba para ver las alertas.
        </Alert>
      ) : null}
      {error ? <Alert tone="danger">No se pudieron cargar las alertas.</Alert> : null}

      <Card className="section">
        <h3 className="h3-reset">Últimas alertas</h3>
        {isLoading ? (
          <div className="empty">Cargando…</div>
        ) : !rows.length ? (
          <div className="empty">Sin alertas por ahora.</div>
        ) : (
          <Table>
            <thead>
              <tr>
                <th>Fecha</th>
                <th>Periodo</th>
                <th>Tipo</th>
                <th>Mensaje</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((a: any) => (
                <tr key={a.id}>
                  <td className="upload-hint">{formatIsoDateTime(a.createdAt)}</td>
                  <td>{a.period}</td>
                  <td>
                    <span className="badge warn">{a.type}</span>
                  </td>
                  <td>{a.message}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </div>
  )
}

