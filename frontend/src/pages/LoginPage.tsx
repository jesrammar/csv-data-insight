import { useMemo, useState } from 'react'
import { confirmPasswordFromToken, login } from '../api'
import Button from '../components/ui/Button'
import Alert from '../components/ui/Alert'
import { useSearchParams } from 'react-router-dom'

export default function LoginPage() {
  const [sp] = useSearchParams()
  const token = (sp.get('token') || '').trim()
  const action = (sp.get('action') || '').trim().toLowerCase() === 'invite' ? ('invite' as const) : ('reset' as const)
  const hasTokenFlow = !!token

  const [email, setEmail] = useState('admin@asecon.local')
  const [password, setPassword] = useState('password')
  const [error, setError] = useState('')
  const [showPassword, setShowPassword] = useState(false)

  const [newPassword, setNewPassword] = useState('')
  const [newPassword2, setNewPassword2] = useState('')
  const [settingPassword, setSettingPassword] = useState(false)
  const [done, setDone] = useState(false)

  const passwordHint = useMemo(() => {
    const p = String(newPassword || '')
    const hasUpper = /[A-Z]/.test(p)
    const hasLower = /[a-z]/.test(p)
    const hasDigit = /[0-9]/.test(p)
    const len = p.length
    return { hasUpper, hasLower, hasDigit, len }
  }, [newPassword])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    try {
      await login(email, password)
    } catch (err: any) {
      setError(err.message)
    }
  }

  async function handleSetPassword(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    if (!token) {
      setError('Falta token. Pide a tu consultor un enlace válido.')
      return
    }
    if (!newPassword.trim() || !newPassword2.trim()) {
      setError('Introduce y confirma tu nueva contraseña.')
      return
    }
    if (newPassword !== newPassword2) {
      setError('Las contraseñas no coinciden.')
      return
    }
    setSettingPassword(true)
    try {
      await confirmPasswordFromToken(token, newPassword, action)
      setDone(true)
    } catch (err: any) {
      setError(String(err?.message || err || 'No se pudo actualizar la contraseña.'))
    } finally {
      setSettingPassword(false)
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
          <h2 style={{ marginTop: 6 }}>{hasTokenFlow ? (action === 'invite' ? 'Activar cuenta' : 'Restablecer contraseña') : 'Ingreso'}</h2>
          <p className="hero-sub">
            {hasTokenFlow
              ? 'Elige una nueva contraseña segura para continuar.'
              : 'Usuarios demo: admin, consultor y cliente.'}
          </p>

          {hasTokenFlow ? (
            done ? (
              <Alert tone="success" title="Contraseña actualizada">
                Ya puedes iniciar sesión con tu email y tu nueva contraseña.
              </Alert>
            ) : (
              <form onSubmit={handleSetPassword}>
                <div>
                  <input
                    type={showPassword ? 'text' : 'password'}
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    placeholder="Nueva contraseña"
                    autoComplete="new-password"
                    aria-label="Nueva contraseña"
                  />
                </div>
                <div style={{ marginTop: 10 }}>
                  <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
                    <input
                      type={showPassword ? 'text' : 'password'}
                      value={newPassword2}
                      onChange={(e) => setNewPassword2(e.target.value)}
                      placeholder="Confirmar contraseña"
                      autoComplete="new-password"
                      aria-label="Confirmar contraseña"
                      style={{ flex: 1 }}
                    />
                    <Button type="button" variant="ghost" size="sm" onClick={() => setShowPassword((s) => !s)}>
                      {showPassword ? 'Ocultar' : 'Ver'}
                    </Button>
                  </div>
                </div>

                <div className="upload-hint" style={{ marginTop: 10 }}>
                  Requisitos: 10+ caracteres · 1 mayúscula · 1 minúscula · 1 número
                  <div style={{ marginTop: 8, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    <span className={`badge ${passwordHint.len >= 10 ? 'ok' : ''}`}>Longitud</span>
                    <span className={`badge ${passwordHint.hasUpper ? 'ok' : ''}`}>Mayúscula</span>
                    <span className={`badge ${passwordHint.hasLower ? 'ok' : ''}`}>Minúscula</span>
                    <span className={`badge ${passwordHint.hasDigit ? 'ok' : ''}`}>Número</span>
                  </div>
                </div>

                {error && (
                  <div style={{ marginTop: 12 }}>
                    <Alert tone="danger">{error}</Alert>
                  </div>
                )}
                <div style={{ marginTop: 12 }}>
                  <Button type="submit" disabled={settingPassword}>
                    {settingPassword ? 'Guardando…' : 'Guardar contraseña'}
                  </Button>
                </div>
              </form>
            )
          ) : (
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
              <div className="upload-hint" style={{ marginTop: 10 }}>
                ¿No puedes entrar? Pide a tu consultor un enlace de activación o reset.
              </div>
            </form>
          )}
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
