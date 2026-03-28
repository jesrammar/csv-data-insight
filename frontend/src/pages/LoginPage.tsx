import { useState } from 'react'
import { login, setTokens, setUserMeta } from '../api'
import Button from '../components/ui/Button'
import Alert from '../components/ui/Alert'

export default function LoginPage() {
  const [email, setEmail] = useState('admin@asecon.local')
  const [password, setPassword] = useState('password')
  const [error, setError] = useState('')
  const [showPassword, setShowPassword] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    try {
      const res = await login(email, password)
      setTokens(res.accessToken, res.refreshToken)
      setUserMeta(res.role, res.userId)
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
              <input
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="email"
                autoComplete="username"
                aria-label="Email"
              />
            </div>
            <div style={{ marginTop: 10 }}>
              <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="password"
                  autoComplete="current-password"
                  aria-label="Password"
                  style={{ flex: 1 }}
                />
                <Button type="button" variant="ghost" size="sm" onClick={() => setShowPassword((s) => !s)}>
                  {showPassword ? 'Ocultar' : 'Ver'}
                </Button>
              </div>
            </div>
            {error && (
              <div style={{ marginTop: 12 }}>
                <Alert tone="danger">{error}</Alert>
              </div>
            )}
            <div style={{ marginTop: 12 }}>
              <Button type="submit">Entrar</Button>
            </div>
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
