import { Link } from 'react-router-dom'
import PageHeader from '../components/ui/PageHeader'
import PlanCapabilitiesTable from '../components/PlanCapabilitiesTable'
import RoleCapabilitiesTable from '../components/RoleCapabilitiesTable'
import { useCompanySelection } from '../hooks/useCompany'

export default function HelpPage() {
  const { plan } = useCompanySelection()
  return (
    <div>
      <PageHeader
        title="Ayuda"
        subtitle="Guía rápida para usar EnterpriseIQ sin perderte."
        actions={<span className="badge">{(plan || 'BRONZE').toUpperCase()}</span>}
      />

      <div className="grid section">
        <div className="card">
          <h3 style={{ marginTop: 0 }}>1) Datos</h3>
          <div className="hero-sub">
            Si no ves KPIs, es que faltan datos del periodo. Tu consultora puede cargar el CSV/XLSX y recalcular
            automáticamente.
          </div>
        </div>
        <div className="card">
          <h3 style={{ marginTop: 0 }}>2) Caja</h3>
          <div className="hero-sub">
            En <strong>Caja</strong> verás entradas/salidas, neto y evolución. Empieza por el último mes y busca
            cambios bruscos.
          </div>
          <div style={{ marginTop: 12 }}>
            <Link className="badge" to="/cash">
              Ir a Caja
            </Link>
          </div>
        </div>
        <div className="card">
          <h3 style={{ marginTop: 0 }}>3) Alertas</h3>
          <div className="hero-sub">
            Las alertas resumen “qué mirar ya”. Si aparece una alerta de caja negativa o anomalías, revísalo antes
            de tomar decisiones.
          </div>
          <div style={{ marginTop: 12 }}>
            <Link className="badge" to="/alerts">
              Ver Alertas
            </Link>
          </div>
        </div>
        <div className="card">
          <h3 style={{ marginTop: 0 }}>4) Informe</h3>
          <div className="hero-sub">
            En <strong>Informes</strong> tendrás entregables mensuales para compartir. Si no hay, tu consultora puede
            generarlos con un clic.
          </div>
          <div style={{ marginTop: 12 }}>
            <Link className="badge" to="/reports">
              Ir a Informes
            </Link>
          </div>
        </div>
      </div>

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Atajos</h3>
        <div className="bar-stack">
          <div className="kpi" style={{ padding: 12 }}>
            <div className="upload-hint">Objetivo típico</div>
            <strong>Mejorar caja (30 días)</strong>
            <div className="upload-hint" style={{ marginTop: 6 }}>
              Reduce salidas fijas, renegocia pagos, prioriza cobros y controla inventario/consumos.
            </div>
          </div>
          <div className="kpi" style={{ padding: 12 }}>
            <div className="upload-hint">Objetivo típico</div>
            <strong>Subir margen (60 días)</strong>
            <div className="upload-hint" style={{ marginTop: 6 }}>
              Revisa precios, costes por proveedor y mix de productos/servicios.
            </div>
          </div>
        </div>
      </div>

      <RoleCapabilitiesTable compact />

      <PlanCapabilitiesTable
        compact
        title="Qué incluye cada plan"
        subtitle="Tabla rápida para entender qué capacidades se habilitan por empresa (exportaciones, asistente, etc.)."
      />
    </div>
  )
}
