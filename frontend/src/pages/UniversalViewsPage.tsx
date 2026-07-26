import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { listUniversalViews, type UniversalViewDto } from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import Card from '../components/ui/Card'
import Table from '../components/ui/Table'

function aggregationModeLabel(value: string | null | undefined) {
  const key = String(value || '').trim().toUpperCase()
  const labels: Record<string, string> = {
    ROW_COUNT: 'Filas',
    DISTINCT_ENTRY_COUNT: 'Asientos',
    DISTINCT_DOCUMENT_COUNT: 'Documentos',
    DISTINCT_INVOICE_COUNT: 'Facturas',
    DISTINCT_PARTY_COUNT: 'Terceros',
    SUM_DEBIT: 'Debe',
    SUM_CREDIT: 'Haber',
    NET_BALANCE: 'Saldo',
    SUM_AMOUNT: 'Importe',
    AVG_VALUE: 'Media'
  }
  return labels[key] || 'Modo pendiente'
}

function aggregationModeDetail(value: string | null | undefined) {
  const key = String(value || '').trim().toUpperCase()
  const labels: Record<string, string> = {
    ROW_COUNT: 'Cuenta filas operativas.',
    DISTINCT_ENTRY_COUNT: 'Cuenta asientos distintos.',
    DISTINCT_DOCUMENT_COUNT: 'Cuenta documentos distintos.',
    DISTINCT_INVOICE_COUNT: 'Cuenta facturas distintas.',
    DISTINCT_PARTY_COUNT: 'Cuenta terceros distintos.',
    SUM_DEBIT: 'Suma debe.',
    SUM_CREDIT: 'Suma haber.',
    NET_BALANCE: 'Calcula saldo neto.',
    SUM_AMOUNT: 'Suma importe monetario.',
    AVG_VALUE: 'Calcula media del valor.'
  }
  return labels[key] || 'Todavia no hay modo oficial visible.'
}

function viewTypeLabel(value: string | null | undefined) {
  const key = String(value || '').trim().toUpperCase()
  const labels: Record<string, string> = {
    TIME_SERIES: 'Serie temporal',
    CATEGORY_BAR: 'Ranking',
    KPI_CARDS: 'KPIs',
    SCATTER: 'Relacion X/Y',
    HEATMAP: 'Mapa cruzado',
    PIVOT_MONTHLY: 'Pivote mensual'
  }
  return labels[key] || String(value || 'Vista')
}

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
        subtitle="Vistas guardadas desde Universal para reutilizar lectura consultiva por empresa."
        actions={
          <div className="row row-center gap-2">
            <span className="badge">{plan}</span>
            <Button variant="ghost" size="sm" onClick={() => refetch()} disabled={!companyId || isPending}>
              Refrescar
            </Button>
          </div>
        }
      />

      {!companyId ? (
        <Alert tone="warning" title="Falta seleccionar empresa gestionada">
          Selecciona una empresa gestionada para ver dashboards.
        </Alert>
      ) : null}

      {error ? (
        <div className="mt-2">
          <Alert tone="danger">{String((error as any)?.message || error)}</Alert>
        </div>
      ) : null}

      <Card className="section">
        <h3 className="h3-reset">Listado</h3>
        {!views.length ? (
          <div className="empty">
            No hay dashboards todavia. Ve a <strong>Universal</strong> y guarda una vista desde las sugerencias AUTO o el modo guiado.
          </div>
        ) : (
          <Table>
            <thead>
              <tr>
                <th>Nombre</th>
                <th>Tipo</th>
                <th>Modo oficial</th>
                <th>Dataset</th>
                <th>Creado</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {views.map((view) => (
                <tr key={view.id}>
                  <td>
                    <div className="stack">
                      <span>{view.name}</span>
                      <span className="upload-hint">La plantilla deja fijado que calcula y como lo calcula.</span>
                    </div>
                  </td>
                  <td>{viewTypeLabel(view.type)}</td>
                  <td>
                    <div className="stack">
                      <span className="badge">{aggregationModeLabel(view.aggregationMode)}</span>
                      <span className="upload-hint">{aggregationModeDetail(view.aggregationMode)}</span>
                    </div>
                  </td>
                  <td>
                    <div className="stack">
                      <span>{view.sourceFilename || '-'}</span>
                      <span className="upload-hint">
                        {view.sourceImportedAt ? new Date(view.sourceImportedAt).toLocaleString() : '-'}
                      </span>
                    </div>
                  </td>
                  <td>{view.createdAt ? new Date(view.createdAt).toLocaleString() : '-'}</td>
                  <td className="text-right">
                    <div className="row row-wrap row-center row-end gap-1">
                      <Link className="badge" to={`/universal/views/${view.id}`}>
                        Abrir
                      </Link>
                      <button
                        className="badge"
                        onClick={() => {
                          const url = `${window.location.origin}/universal/views/${view.id}`
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
          </Table>
        )}
      </Card>
    </div>
  )
}
