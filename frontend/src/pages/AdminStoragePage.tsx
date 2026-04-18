import { useQuery } from '@tanstack/react-query'
import { getStorageCleanupLast, runStorageCleanupNow, getUserRole } from '../api'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import Card from '../components/ui/Card'
import Grid from '../components/ui/Grid'
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
          <div className="row gap-2">
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

      <Card className="section">
        <h3 className="h3-reset">Última ejecución</h3>
        {!data ? (
          <div className="empty">Aún no se ha ejecutado la limpieza.</div>
        ) : (
          <Grid>
            <Card variant="soft" className="card-pad-sm">
              <div className="upload-hint">Inicio</div>
              <strong className="block mt-2">{fmtTime(data.startedAt)}</strong>
              <div className="upload-hint mt-2">
                Fin: {fmtTime(data.finishedAt)}
              </div>
            </Card>
            <Card variant="soft" className="card-pad-sm">
              <div className="upload-hint">Imports (refs limpiadas)</div>
              <strong className="block mt-2">{data.imports?.refsCleared ?? 0}</strong>
            </Card>
            <Card variant="soft" className="card-pad-sm">
              <div className="upload-hint">Reportes (refs limpiadas)</div>
              <strong className="block mt-2">{data.reports?.refsCleared ?? 0}</strong>
            </Card>
            <Card variant="soft" className="card-pad-sm">
              <div className="upload-hint">Universal (refs limpiadas)</div>
              <strong className="block mt-2">{data.universal?.refsCleared ?? 0}</strong>
              <div className="upload-hint mt-2">
                Errores: {data.errors ?? 0}
              </div>
            </Card>
          </Grid>
        )}
      </Card>
    </div>
  )
}

