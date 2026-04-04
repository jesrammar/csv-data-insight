import { useQuery } from '@tanstack/react-query'
import { getAuditEvents, getUserRole } from '../api'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useCompanySelection } from '../hooks/useCompany'

function fmtTime(iso: string) {
  try {
    return new Date(iso).toLocaleString()
  } catch {
    return iso
  }
}

export default function AuditPage() {
  const { id: companyId } = useCompanySelection()
  const role = getUserRole()

  const { data, error, isFetching, refetch } = useQuery({
    queryKey: ['audit', companyId],
    queryFn: () => getAuditEvents(companyId as number, 80),
    enabled: !!companyId && (role === 'ADMIN' || role === 'CONSULTOR')
  })

  return (
    <div>
      <PageHeader
        title="Auditoría"
        subtitle="Registro de acciones relevantes (imports, exportaciones, reportes, limpieza)."
        actions={
          <Button variant="ghost" size="sm" onClick={() => refetch()} disabled={!companyId || isFetching}>
            {isFetching ? 'Actualizando…' : 'Refrescar'}
          </Button>
        }
      />

      {!companyId ? (
        <Alert tone="warning" title="Falta seleccionar empresa">
          Selecciona una empresa arriba para ver eventos.
        </Alert>
      ) : error ? (
        <Alert tone="danger" title="No se pudo cargar la auditoría">
          {String((error as any)?.message || error)}
        </Alert>
      ) : null}

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Eventos</h3>
        {!data?.length ? (
          <div className="empty">No hay eventos todavía.</div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Fecha</th>
                <th>Acción</th>
                <th>HTTP</th>
                <th>Status</th>
                <th>Duración</th>
              </tr>
            </thead>
            <tbody>
              {(data || []).map((e) => (
                <tr key={e.id}>
                  <td style={{ whiteSpace: 'nowrap' }}>{fmtTime(e.at)}</td>
                  <td>
                    <strong>{e.action}</strong>
                    {e.path ? <div className="upload-hint">{e.path}</div> : null}
                    {e.metaJson ? (
                      <div className="upload-hint" title={String(e.metaJson)}>
                        {(() => {
                          try {
                            const m = JSON.parse(String(e.metaJson))
                            const added = Array.isArray(m?.addedCompanyIds) ? m.addedCompanyIds.length : 0
                            const removed = Array.isArray(m?.removedCompanyIds) ? m.removedCompanyIds.length : 0
                            const pieces: string[] = []
                            if (m?.targetEmail) pieces.push(String(m.targetEmail))
                            if (m?.roleFrom || m?.roleTo) pieces.push(`role ${m.roleFrom ?? '—'} → ${m.roleTo ?? '—'}`)
                            if (typeof m?.enabledFrom === 'boolean' || typeof m?.enabledTo === 'boolean') {
                              pieces.push(`enabled ${String(m.enabledFrom)} → ${String(m.enabledTo)}`)
                            }
                            if (added || removed) pieces.push(`empresas +${added} -${removed}`)
                            return pieces.length ? pieces.join(' · ') : String(e.metaJson).slice(0, 120)
                          } catch {
                            return String(e.metaJson).slice(0, 120)
                          }
                        })()}
                      </div>
                    ) : null}
                    {e.resourceType || e.resourceId ? (
                      <div className="upload-hint">
                        {(e.resourceType || 'resource').toUpperCase()}
                        {e.resourceId ? ` #${e.resourceId}` : ''}
                      </div>
                    ) : null}
                  </td>
                  <td>{e.method || '—'}</td>
                  <td>{e.status ?? '—'}</td>
                  <td>{e.durationMs != null ? `${e.durationMs}ms` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
