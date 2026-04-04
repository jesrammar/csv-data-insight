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
    <div className={`card soft ${disabled ? 'disabled' : ''}`} style={{ padding: 14 }}>
      <div className="mini-row" style={{ marginTop: 0, justifyContent: 'space-between', alignItems: 'baseline' }}>
        <strong style={{ fontSize: 14 }}>{title}</strong>
        {badge ? <span className="badge">{badge}</span> : null}
      </div>
      <div className="upload-hint" style={{ marginTop: 8 }}>
        {subtitle}
      </div>
      <div className="upload-hint" style={{ marginTop: 10 }}>
        {disabled ? 'No disponible con tu plan.' : 'Abrir →'}
      </div>
    </div>
  )

  if (disabled) return inner
  return (
    <Link to={to} style={{ textDecoration: 'none' }}>
      {inner}
    </Link>
  )
}

export default function ToolsPage() {
  const role = getUserRole()
  const isClient = role === 'CLIENTE'
  const { plan } = useCompanySelection()
  const planUp = (plan || 'BRONZE').toUpperCase()
  const hasGold = planUp === 'GOLD' || planUp === 'PLATINUM'

  return (
    <div>
      <PageHeader
        title="Herramientas"
        subtitle="Consultoría, automatización, auditoría y planes en un solo sitio."
        actions={!isClient ? <span className="pill">Plan: {planUp}</span> : null}
      />

      <div className="grid section">
        <div className="card">
          <div className="mini-row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
            <h3 style={{ margin: 0 }}>Consultoría</h3>
            <span className="upload-hint">
              <Icon name="advisor" /> módulos avanzados
            </span>
          </div>
          <div className="grid" style={{ marginTop: 12 }}>
            <Card
              title="Cumplimiento (Tribunal)"
              subtitle="KPIs y riesgos a partir de CSV del Tribunal."
              to="/tribunal"
              disabled={!hasGold}
              badge="GOLD+"
            />
            <Card
              title="Presupuesto mensual"
              subtitle="Plantilla (ENERO…DICIEMBRE): 2 gráficos + tabla de variaciones."
              to="/budget"
              disabled={!hasGold}
              badge="GOLD+"
            />
            <Card
              title="Análisis avanzado (Universal)"
              subtitle="Analiza datasets genéricos y genera insights."
              to="/universal"
              badge="BRONZE+"
            />
            <Card
              title="Mis dashboards"
              subtitle="Plantillas guardadas desde Universal (compartibles)."
              to="/universal/views"
              badge="BRONZE+"
            />
            <Card
              title="Asesor"
              subtitle="Recomendaciones y explicación de resultados."
              to="/advisor"
              badge="BRONZE+"
            />
          </div>
        </div>

        <div className="card">
          <h3 style={{ marginTop: 0 }}>Operación</h3>
          <div className="grid" style={{ marginTop: 12 }}>
            <Card
              title="Auditoría"
              subtitle="Registro de acciones (descargas, imports, etc.)."
              to="/audit"
              badge="BRONZE+"
            />
            <Card
              title="Planes"
              subtitle="Qué incluye cada plan y cómo se desbloquea."
              to="/pricing"
              badge="Info"
            />
          </div>

          <details style={{ marginTop: 14 }}>
            <summary className="upload-hint" style={{ cursor: 'pointer' }}>
              Avanzado (soporte/operación)
            </summary>
            <div className="grid" style={{ marginTop: 12 }}>
              <Card
                title="Operaciones · Automatización"
                subtitle="Forzar/reintentar jobs si algo no corre (KPIs, informes, snapshots). Normalmente no hace falta tocarlo."
                to="/automation"
                badge="Avanzado"
              />
            </div>
          </details>
        </div>
      </div>
    </div>
  )
}
