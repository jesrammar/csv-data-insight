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
    <div className={`card soft card-pad-14 ${disabled ? 'disabled' : ''}`.trim()}>
      <div className="row row-between row-baseline">
        <strong className="fs-14">{title}</strong>
        {badge ? <span className="badge">{badge}</span> : null}
      </div>
      <div className="upload-hint mt-8">
        {subtitle}
      </div>
      <div className="upload-hint mt-2">
        {disabled ? 'No disponible con tu plan.' : 'Abrir →'}
      </div>
    </div>
  )

  if (disabled) return inner
  return (
    <Link to={to}>{inner}</Link>
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
          <div className="row row-between row-baseline">
            <h3 className="m-0">Consultoría</h3>
            <span className="upload-hint">
              <Icon name="advisor" /> módulos avanzados
            </span>
          </div>
          <div className="grid mt-12">
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
          <h3 className="h3-reset">Operación</h3>
          <div className="grid mt-12">
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

          <details className="mt-3">
            <summary className="upload-hint cursor-pointer">
              Avanzado (soporte/operación)
            </summary>
            <div className="grid mt-12">
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
