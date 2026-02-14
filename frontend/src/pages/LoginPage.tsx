import { useState } from 'react'
import { login, setTokens } from '../api'

export default function LoginPage() {
  const [email, setEmail] = useState('admin@asecon.local')
  const [password, setPassword] = useState('password')
  const [error, setError] = useState('')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    try {
      const res = await login(email, password)
      setTokens(res.accessToken, res.refreshToken)
      window.location.reload()
    } catch (err: any) {
      setError(err.message)
    }
  }

  return (
    <div className="login-shell">
      <div className="login-hero">
        <div>
          <h1 className="hero-title">EnterpriseIQ</h1>
          <p className="hero-sub">
            Inteligencia financiera operativa para consultoras. KPIs, alertas e informes mensuales con control multiempresa.
          </p>
          <div className="login-stats">
            <div className="stat-card">
              <span>+2.3M</span>
              <small>movimientos analizados</small>
            </div>
            <div className="stat-card">
              <span>98%</span>
              <small>automatización de reporting</small>
            </div>
          </div>
        </div>
        <div className="card login-panel fade-up">
          <div className="login-badge">Acceso seguro</div>
          <h2 style={{ marginTop: 6 }}>Ingreso</h2>
          <p className="hero-sub">Usuarios demo: admin, consultor y cliente.</p>
          <form onSubmit={handleSubmit}>
            <div>
              <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="email" />
            </div>
            <div style={{ marginTop: 10 }}>
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="password" />
            </div>
            {error && <p className="error">{error}</p>}
            <button type="submit" style={{ marginTop: 12 }}>Entrar</button>
          </form>
        </div>
      </div>
      <div className="grid section">
        <div className="card soft">
          <h3>Dashboards</h3>
          <p className="hero-sub">KPIs mensuales, tendencias y alertas accionables.</p>
        </div>
        <div className="card soft">
          <h3>Importaciones</h3>
          <p className="hero-sub">CSV validado, staging y normalización automática.</p>
        </div>
        <div className="card soft">
          <h3>Reportes</h3>
          <p className="hero-sub">Informes HTML listos para exportar a PDF.</p>
        </div>
      </div>
    </div>
  )
}