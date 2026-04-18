import { Link } from 'react-router-dom'

function Status({ ok, label }: { ok: boolean; label: string }) {
  return (
    <span className={`badge quickstart-status ${ok ? 'quickstart-status-ok' : 'quickstart-status-pending'}`.trim()}>
      {label}
    </span>
  )
}

export default function QuickStartClient({
  companySelected,
  hasCashData,
  alertsCount,
  reportsCount
}: {
  companySelected: boolean
  hasCashData: boolean
  alertsCount: number
  reportsCount: number
}) {
  const hasAlerts = alertsCount > 0
  const hasReports = reportsCount > 0

  return (
    <div className="card soft card-pad-14 mt-12">
      <div className="mini-row row-between row-baseline">
        <div>
          <div className="upload-hint">En 30 segundos</div>
          <strong>Cómo usar este panel sin perderte</strong>
        </div>
        <Link className="badge" to="/help">
          Guía
        </Link>
      </div>

      <div className="grid mt-12">
        <div className="kpi pad-sm">
          <div className="mini-row row-between">
            <strong>1) Elige empresa</strong>
            <Status ok={companySelected} label={companySelected ? 'OK' : 'Pendiente'} />
          </div>
          <div className="upload-hint mt-8">
            Se selecciona arriba (desplegable). Todo el panel cambia con esa empresa.
          </div>
        </div>

        <div className="kpi pad-sm">
          <div className="mini-row row-between">
            <strong>2) Mira la caja</strong>
            <Status ok={hasCashData} label={hasCashData ? 'Lista' : 'Sin datos'} />
          </div>
          <div className="upload-hint mt-8">
            Entradas = cobros · Salidas = pagos · Neto = entradas - salidas · Saldo fin = lo que queda.
          </div>
          <div className="mini-row mt-10">
            <Link className="badge" to="/cash">
              Abrir caja
            </Link>
            {!hasCashData ? <span className="upload-hint">Tu consultora debe cargar el fichero del mes.</span> : null}
          </div>
        </div>

        <div className="kpi pad-sm">
          <div className="mini-row row-between">
            <strong>3) Decide con alertas e informes</strong>
            <Status ok={hasAlerts || hasReports} label={hasAlerts || hasReports ? 'Acción' : 'Sin novedades'} />
          </div>
          <div className="upload-hint mt-8">
            Si hay alertas, empieza por ahí. Si no, revisa el informe del mes y compártelo.
          </div>
          <div className="mini-row mt-10">
            <Link className="badge" to="/alerts">
              Alertas ({alertsCount})
            </Link>
            <Link className="badge" to="/reports">
              Informes ({reportsCount})
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}

