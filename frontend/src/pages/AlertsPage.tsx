import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { getAlerts } from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import Alert from '../components/ui/Alert'
import PageHeader from '../components/ui/PageHeader'
import { formatIsoDateTime } from '../utils/format'

export default function AlertsPage() {
  const { id: companyId, plan } = useCompanySelection()

  const { data, error, isLoading } = useQuery({
    queryKey: ['alerts', companyId],
    queryFn: () => getAlerts(companyId as number),
    enabled: !!companyId
  })

  const rows = useMemo(() => (Array.isArray(data) ? data : []), [data])

  return (
    <div>
      <PageHeader
        title="Alertas"
        subtitle="Riesgos y avisos que requieren atención (caja, anomalías, reglas)."
        actions={<span className="badge">{(plan || 'BRONZE').toUpperCase()}</span>}
      />

      {!companyId ? (
        <Alert tone="warning" title="Falta seleccionar empresa">
          Selecciona una empresa arriba para ver las alertas.
        </Alert>
      ) : null}
      {error ? <Alert tone="danger">No se pudieron cargar las alertas.</Alert> : null}

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Últimas alertas</h3>
        {isLoading ? (
          <div className="empty">Cargando…</div>
        ) : !rows.length ? (
          <div className="empty">Sin alertas por ahora.</div>
        ) : (
          <table className="table">
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
          </table>
        )}
      </div>
    </div>
  )
}
