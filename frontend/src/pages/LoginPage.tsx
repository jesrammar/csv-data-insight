import { useState } from 'react'
import { login, setToken } from '../api'

export default function LoginPage() {
  const [email, setEmail] = useState('admin@asecon.local')
  const [password, setPassword] = useState('password')
  const [error, setError] = useState('')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    try {
      const res = await login(email, password)
      setToken(res.accessToken)
      window.location.reload()
    } catch (err: any) {
      setError(err.message)
    }
  }

  return (
    <div className="container">
      <div className="hero">
        <div>
          <h1 className="hero-title">EnterpriseIQ</h1>
          <p className="hero-sub">
            Inteligencia financiera operativa para consultoras. Accede a KPIs, alertas e informes mensuales con control multiempresa.
          </p>
        </div>
        <div className="card fade-up">
          <h2 style={{ marginTop: 0 }}>Ingreso seguro</h2>
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
