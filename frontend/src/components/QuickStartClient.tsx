import { Link } from 'react-router-dom'

function Status({ ok, label }: { ok: boolean; label: string }) {
  return (
    <span
      className="badge"
      style={{
        borderColor: ok ? 'rgba(34, 197, 94, 0.35)' : 'rgba(148, 163, 184, 0.25)',
        color: ok ? 'rgba(187, 247, 208, 0.95)' : 'rgba(226, 232, 240, 0.78)'
      }}
    >
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
    <div className="card soft" style={{ padding: 14, marginTop: 12 }}>
      <div className="mini-row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
        <div>
          <div className="upload-hint">En 30 segundos</div>
          <strong>Cómo usar este panel sin perderte</strong>
        </div>
        <Link className="badge" to="/help">
          Guía
        </Link>
      </div>

      <div className="grid" style={{ marginTop: 12 }}>
        <div className="kpi" style={{ padding: 12 }}>
          <div className="mini-row" style={{ justifyContent: 'space-between' }}>
            <strong>1) Elige empresa</strong>
            <Status ok={companySelected} label={companySelected ? 'OK' : 'Pendiente'} />
          </div>
          <div className="upload-hint" style={{ marginTop: 8 }}>
            Se selecciona arriba (desplegable). Todo el panel cambia con esa empresa.
          </div>
        </div>

        <div className="kpi" style={{ padding: 12 }}>
          <div className="mini-row" style={{ justifyContent: 'space-between' }}>
            <strong>2) Mira la caja</strong>
            <Status ok={hasCashData} label={hasCashData ? 'Lista' : 'Sin datos'} />
          </div>
          <div className="upload-hint" style={{ marginTop: 8 }}>
            Entradas = cobros · Salidas = pagos · Neto = entradas - salidas · Saldo fin = lo que queda.
          </div>
          <div className="mini-row" style={{ marginTop: 10 }}>
            <Link className="badge" to="/cash">
              Abrir caja
            </Link>
            {!hasCashData ? <span className="upload-hint">Tu consultora debe cargar el fichero del mes.</span> : null}
          </div>
        </div>

        <div className="kpi" style={{ padding: 12 }}>
          <div className="mini-row" style={{ justifyContent: 'space-between' }}>
            <strong>3) Decide con alertas e informes</strong>
            <Status ok={hasAlerts || hasReports} label={hasAlerts || hasReports ? 'Acción' : 'Sin novedades'} />
          </div>
          <div className="upload-hint" style={{ marginTop: 8 }}>
            Si hay alertas, empieza por ahí. Si no, revisa el informe del mes y compártelo.
          </div>
          <div className="mini-row" style={{ marginTop: 10 }}>
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

