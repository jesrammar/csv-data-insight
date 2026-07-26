import { Link } from 'react-router-dom'
import { getUserRole } from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Icon from '../components/ui/Icon'

function Card({
  title,
  subtitle,
  to,
  disabled,
  badge
}: {
  title: string
  subtitle: string
  to: string
  disabled?: boolean
  badge?: string
}) {
  const inner = (
    <div className={`card soft card-pad-14 tools-card ${disabled ? 'disabled' : ''}`.trim()}>
      <div className="row row-between row-baseline">
        <strong className="fs-14">{title}</strong>
        {badge ? <span className="badge">{badge}</span> : null}
      </div>
      <div className="upload-hint mt-8">{subtitle}</div>
      <div className="upload-hint mt-2">{disabled ? 'No disponible en tu plan.' : 'Abrir'}</div>
    </div>
  )

  if (disabled) return inner
  return <Link to={to}>{inner}</Link>
}

export default function ToolsPage() {
  const role = getUserRole()
  const isClient = role === 'CLIENTE'
  const isAdmin = role === 'ADMIN'
  const { plan } = useCompanySelection()
  const planUp = (plan || 'BRONZE').toUpperCase()
  const hasGold = planUp === 'GOLD' || planUp === 'PLATINUM'

  return (
    <div className="tools-page">
      <PageHeader
        title="Centro de trabajo"
        subtitle="Diagnosticar, cerrar y convertir en servicio."
        actions={!isClient ? <span className="pill">{planUp}</span> : null}
      />

      <div className="card section soft tools-intro-shell">
        <div className="mini-row row-baseline">
          <h3 className="m-0">Ruta corta</h3>
          <span className="upload-hint">Menos ruido. Más siguiente paso.</span>
        </div>
        <div className="grid grid-autofit-220 mt-12 tools-intro-grid">
          <div className="card soft card-pad-sm tools-intro-card">
            <div className="upload-hint">1. Leer el negocio</div>
            <div className="fw-800 mt-1">Caja, Universal y plan anual</div>
            <div className="upload-hint mt-1">Detecta señales reales.</div>
          </div>
          <div className="card soft card-pad-sm tools-intro-card">
            <div className="upload-hint">2. Convertir en servicio</div>
            <div className="fw-800 mt-1">Workflow, asesor y entregable</div>
            <div className="upload-hint mt-1">Pasa de dato a cierre y recomendación.</div>
          </div>
        </div>
      </div>

      <div className="grid section tools-sections-grid">
        <div className="card tools-column">
          <div className="row row-between row-baseline">
            <h3 className="m-0">Núcleo</h3>
            <span className="upload-hint">
              <Icon name="advisor" /> uso principal
            </span>
          </div>
          <div className="grid mt-12 tools-card-grid">
            <Card
              title="Universal"
              subtitle="Explora datasets y guarda lecturas."
              to="/universal"
              badge="Base"
            />
            <Card
              title="Cumplimiento (Tribunal)"
              subtitle="Riesgos y cartera."
              to="/tribunal"
              disabled={!hasGold}
              badge="GOLD+"
            />
            <Card
              title="Plan anual y presupuesto"
              subtitle="Plan, comparativa e informe anual."
              to="/budget"
              disabled={!hasGold}
              badge="GOLD+"
            />
            <Card
              title="Asesor"
              subtitle="Recomendaciones y siguiente paso."
              to="/advisor"
              badge="Acción"
            />
          </div>
        </div>

        <div className="card tools-column">
          <h3 className="h3-reset">Soporte</h3>
          <div className="upload-hint mt-8">
            Lo secundario queda aquí.
          </div>
          <details className="mt-3">
            <summary className="upload-hint cursor-pointer">Ver más</summary>
            <div className="grid mt-12 tools-card-grid">
              <Card
                title="Mis dashboards"
                subtitle="Plantillas guardadas."
                to="/universal/views"
                badge="Soporte"
              />
              <Card
                title="Auditoría"
                subtitle="Registro de acciones sensibles."
                to="/audit"
                badge="Control"
              />
              <Card
                title="Planes"
                subtitle="Valor por plan."
                to="/pricing"
                badge="Info"
              />
              {isAdmin ? (
                <Card
                  title="Control de automatización"
                  subtitle="Supervisa procesos."
                  to="/automation"
                  badge="Admin"
                />
              ) : null}
            </div>
          </details>
        </div>
      </div>
    </div>
  )
}
