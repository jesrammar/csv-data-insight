import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { listUniversalViews, type UniversalViewDto } from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'

export default function UniversalViewsPage() {
  const { id: companyId, plan } = useCompanySelection()

  const { data, error, refetch, isPending } = useQuery({
    queryKey: ['universal-views', companyId],
    queryFn: () => listUniversalViews(companyId as number),
    enabled: !!companyId
  })

  const views = (data || []) as UniversalViewDto[]

  return (
    <div>
      <PageHeader
        title="Mis dashboards"
        subtitle="Dashboards guardados a partir de Universal (compartibles por enlace)."
        actions={
          <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
            <span className="badge">{plan}</span>
            <Button variant="ghost" size="sm" onClick={() => refetch()} disabled={!companyId || isPending}>
              Refrescar
            </Button>
          </div>
        }
      />

      {!companyId ? (
        <Alert tone="warning" title="Falta seleccionar empresa">
          Selecciona una empresa para ver dashboards.
        </Alert>
      ) : null}

      {error ? (
        <div style={{ marginTop: 12 }}>
          <Alert tone="danger">{String((error as any)?.message || error)}</Alert>
        </div>
      ) : null}

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Listado</h3>
        {!views.length ? (
          <div className="empty">
            No hay dashboards todavía. Ve a <strong>Universal</strong> y usa “Crear dashboard (Auto → Guiado)”.
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Nombre</th>
                <th>Tipo</th>
                <th>Dataset</th>
                <th>Creado</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {views.map((v) => (
                <tr key={v.id}>
                  <td>{v.name}</td>
                  <td>{v.type}</td>
                  <td>
                    <div style={{ display: 'grid' }}>
                      <span>{v.sourceFilename || '—'}</span>
                      <span className="upload-hint">
                        {v.sourceImportedAt ? new Date(v.sourceImportedAt).toLocaleString() : '—'}
                      </span>
                    </div>
                  </td>
                  <td>{v.createdAt ? new Date(v.createdAt).toLocaleString() : '—'}</td>
                  <td style={{ textAlign: 'right' }}>
                    <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
                      <Link className="badge" to={`/universal/views/${v.id}`}>
                        Abrir
                      </Link>
                      <button
                        className="badge"
                        onClick={() => {
                          const url = `${window.location.origin}/universal/views/${v.id}`
                          try {
                            navigator.clipboard.writeText(url)
                          } catch {}
                        }}
                        title="Copiar enlace"
                        type="button"
                      >
                        Copiar
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
