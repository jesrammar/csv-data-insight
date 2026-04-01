import { useQuery } from '@tanstack/react-query'
import { getStorageCleanupLast, runStorageCleanupNow, getUserRole } from '../api'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'

function fmtTime(iso?: string | null) {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString()
  } catch {
    return iso
  }
}

export default function AdminStoragePage() {
  const toast = useToast()
  const role = getUserRole()
  const isAdmin = role === 'ADMIN'

  const { data, error, refetch, isFetching } = useQuery({
    queryKey: ['storage-cleanup-last'],
    queryFn: () => getStorageCleanupLast(),
    enabled: isAdmin
  })

  async function handleRun() {
    try {
      const res = await runStorageCleanupNow()
      toast.push({ tone: 'success', title: 'Limpieza', message: 'Ejecución completada.' })
      await refetch()
      return res
    } catch (e: any) {
      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo ejecutar la limpieza.' })
    }
  }

  return (
    <div>
      <PageHeader
        title="Storage cleanup"
        subtitle="Limpieza manual de ficheros antiguos en storage/ (solo ADMIN)."
        actions={
          <div style={{ display: 'flex', gap: 10 }}>
            <Button variant="ghost" size="sm" onClick={() => refetch()} disabled={!isAdmin || isFetching}>
              {isFetching ? 'Actualizando…' : 'Recargar'}
            </Button>
            <Button onClick={handleRun} disabled={!isAdmin}>
              Ejecutar ahora
            </Button>
          </div>
        }
      />

      {!isAdmin ? (
        <Alert tone="warning" title="No autorizado">
          Solo ADMIN puede ejecutar la limpieza.
        </Alert>
      ) : error ? (
        <Alert tone="danger" title="No se pudo cargar el estado">
          {String((error as any)?.message || error)}
        </Alert>
      ) : null}

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Última ejecución</h3>
        {!data ? (
          <div className="empty">Aún no se ha ejecutado la limpieza.</div>
        ) : (
          <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
            <div className="card soft">
              <div className="upload-hint">Inicio</div>
              <strong style={{ display: 'block', marginTop: 8 }}>{fmtTime(data.startedAt)}</strong>
              <div className="upload-hint" style={{ marginTop: 10 }}>
                Fin: {fmtTime(data.finishedAt)}
              </div>
            </div>
            <div className="card soft">
              <div className="upload-hint">Imports (refs limpiadas)</div>
              <strong style={{ display: 'block', marginTop: 8 }}>{data.imports?.refsCleared ?? 0}</strong>
            </div>
            <div className="card soft">
              <div className="upload-hint">Reportes (refs limpiadas)</div>
              <strong style={{ display: 'block', marginTop: 8 }}>{data.reports?.refsCleared ?? 0}</strong>
            </div>
            <div className="card soft">
              <div className="upload-hint">Universal (refs limpiadas)</div>
              <strong style={{ display: 'block', marginTop: 8 }}>{data.universal?.refsCleared ?? 0}</strong>
              <div className="upload-hint" style={{ marginTop: 10 }}>
                Errores: {data.errors ?? 0}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

