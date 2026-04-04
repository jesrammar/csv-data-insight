import { Link } from 'react-router-dom'
import { useState } from 'react'
import PageHeader from '../components/ui/PageHeader'
import PlanCapabilitiesTable from '../components/PlanCapabilitiesTable'
import RoleCapabilitiesTable from '../components/RoleCapabilitiesTable'
import { useCompanySelection } from '../hooks/useCompany'
import Button from '../components/ui/Button'
import Alert from '../components/ui/Alert'
import { changePassword } from '../api'

export default function HelpPage() {
  const { plan } = useCompanySelection()
  const [current, setCurrent] = useState('')
  const [next1, setNext1] = useState('')
  const [next2, setNext2] = useState('')
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState('')
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

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Seguridad: cambiar contraseña</h3>
        <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
          <label style={{ display: 'grid', gap: 6 }}>
            <span className="upload-hint">Contraseña actual</span>
            <input value={current} onChange={(e) => setCurrent(e.target.value)} type="password" autoComplete="current-password" />
          </label>
          <label style={{ display: 'grid', gap: 6 }}>
            <span className="upload-hint">Nueva contraseña</span>
            <input value={next1} onChange={(e) => setNext1(e.target.value)} type="password" autoComplete="new-password" />
          </label>
          <label style={{ display: 'grid', gap: 6 }}>
            <span className="upload-hint">Confirmar</span>
            <input value={next2} onChange={(e) => setNext2(e.target.value)} type="password" autoComplete="new-password" />
          </label>
        </div>

        <div className="upload-hint" style={{ marginTop: 10 }}>
          Requisitos: 10+ caracteres · 1 mayúscula · 1 minúscula · 1 número
        </div>

        {msg ? (
          <div style={{ marginTop: 10 }}>
            <Alert tone={msg.startsWith('OK:') ? 'success' : 'danger'}>{msg.replace(/^OK:\\s*/, '')}</Alert>
          </div>
        ) : null}

        <div style={{ marginTop: 12 }}>
          <Button
            onClick={async () => {
              setMsg('')
              if (!current.trim() || !next1.trim() || !next2.trim()) {
                setMsg('Completa los tres campos.')
                return
              }
              if (next1 !== next2) {
                setMsg('Las contraseñas no coinciden.')
                return
              }
              setSaving(true)
              try {
                await changePassword(current, next1)
                setMsg('OK: Contraseña actualizada. Vuelve a iniciar sesión si es necesario.')
                setCurrent('')
                setNext1('')
                setNext2('')
              } catch (e: any) {
                setMsg(String(e?.message || e || 'No se pudo cambiar la contraseña.'))
              } finally {
                setSaving(false)
              }
            }}
            disabled={saving}
          >
            {saving ? 'Guardando…' : 'Cambiar contraseña'}
          </Button>
        </div>
      </div>
    </div>
  )
}
