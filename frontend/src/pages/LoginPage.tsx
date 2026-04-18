import { useEffect, useMemo, useState } from 'react'
import { confirmPasswordFromToken, login } from '../api'
import Button from '../components/ui/Button'
import Alert from '../components/ui/Alert'
import { useNavigate, useSearchParams } from 'react-router-dom'

function parseHashParams(hash: string) {
  const raw = String(hash || '').trim()
  const normalized = raw.startsWith('#') ? raw.slice(1) : raw
  return new URLSearchParams(normalized)
}

export default function LoginPage() {
  const navigate = useNavigate()
  const [sp] = useSearchParams()
  const hashParams = useMemo(() => parseHashParams(window.location.hash), [])
  const token = (hashParams.get('token') || sp.get('token') || '').trim()
  const actionRaw = (hashParams.get('action') || sp.get('action') || '').trim().toLowerCase()
  const action = actionRaw === 'invite' ? ('invite' as const) : ('reset' as const)
  const hasTokenFlow = !!token

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
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

  const canSetPassword =
    !!token &&
    newPassword.trim().length > 0 &&
    newPassword2.trim().length > 0 &&
    newPassword === newPassword2 &&
    passwordHint.len >= 10 &&
    passwordHint.hasUpper &&
    passwordHint.hasLower &&
    passwordHint.hasDigit

  useEffect(() => {
    document.body.classList.add('body-login')
    // UI density modes removed (always use default spacing).
    document.body.classList.remove('density-compact')
    localStorage.removeItem('uiDensity')
    return () => {
      document.body.classList.remove('body-login')
    }
  }, [])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    const e1 = email.trim()
    const p1 = password.trim()
    if (!e1) {
      setError('Introduce tu email.')
      return
    }
    if (!p1) {
      setError('Introduce tu contraseña.')
      return
    }
    try {
      await login(e1, p1)
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
    if (!canSetPassword) {
      setError('La contraseña no cumple los requisitos de seguridad.')
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
      <div className="login-stage card">
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
        <div className="login-right">
          <div className="card login-panel fade-up">
            <div className="login-panel-head">
              <div className="login-badge">Acceso seguro</div>
            </div>
            <h2 className="login-title">{hasTokenFlow ? (action === 'invite' ? 'Activar cuenta' : 'Restablecer contraseña') : 'Ingreso'}</h2>
            <p className="hero-sub">
              {hasTokenFlow
                ? 'Elige una nueva contraseña segura para continuar.'
                : 'Acceso por invitación para consultores y clientes.'}
            </p>

            {hasTokenFlow ? (
              done ? (
                <Alert tone="success" title="Contraseña actualizada">
                  Ya puedes iniciar sesión con tu email y tu nueva contraseña.
                  <div className="row row-wrap gap-10 mt-12">
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => {
                        setDone(false)
                        setNewPassword('')
                        setNewPassword2('')
                        navigate('/', { replace: true })
                      }}
                    >
                      Ir a iniciar sesión
                    </Button>
                  </div>
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
                <div className="mt-2">
                  <div className="password-field">
                    <input
                      type={showPassword ? 'text' : 'password'}
                      value={newPassword2}
                      onChange={(e) => setNewPassword2(e.target.value)}
                      placeholder="Confirmar contraseña"
                      autoComplete="new-password"
                      aria-label="Confirmar contraseña"
                    />
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      className="password-toggle"
                      onClick={() => setShowPassword((s) => !s)}
                      aria-label={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                    >
                      <svg className="password-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                        <path
                          d="M2.5 12s3.5-7 9.5-7 9.5 7 9.5 7-3.5 7-9.5 7-9.5-7-9.5-7Z"
                          stroke="currentColor"
                          strokeWidth="1.8"
                          strokeLinejoin="round"
                        />
                        <path
                          d="M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4Z"
                          stroke="currentColor"
                          strokeWidth="1.8"
                        />
                      </svg>
                      <span className="sr-only">{showPassword ? 'Ocultar' : 'Ver'}</span>
                    </Button>
                  </div>
                </div>

                  <div className="upload-hint mt-2">
                    Requisitos: 10+ caracteres · 1 mayúscula · 1 minúscula · 1 número
                    <div className="row row-wrap gap-8 mt-8">
                      <span className={`badge ${passwordHint.len >= 10 ? 'ok' : ''}`}>Longitud</span>
                      <span className={`badge ${passwordHint.hasUpper ? 'ok' : ''}`}>Mayúscula</span>
                      <span className={`badge ${passwordHint.hasLower ? 'ok' : ''}`}>Minúscula</span>
                      <span className={`badge ${passwordHint.hasDigit ? 'ok' : ''}`}>Número</span>
                    </div>
                  </div>

                  {error && (
                    <div className="mt-12">
                      <Alert tone="danger">{error}</Alert>
                    </div>
                  )}
                  <div className="mt-12">
                    <Button type="submit" disabled={settingPassword || !canSetPassword}>
                      {settingPassword ? 'Guardando…' : 'Guardar contraseña'}
                    </Button>
                  </div>
                </form>
              )
            ) : (
              <form onSubmit={handleSubmit}>
                <div>
                  <input
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="Email"
                    autoComplete="username"
                    aria-label="Email"
                    className="w-full"
                  />
                </div>
                <div className="mt-2">
                  <div className="password-field">
                    <input
                      type={showPassword ? 'text' : 'password'}
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="Contraseña"
                      autoComplete="current-password"
                      aria-label="Password"
                    />
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      className="password-toggle"
                      onClick={() => setShowPassword((s) => !s)}
                      aria-label={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                    >
                      <svg className="password-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                        <path
                          d="M2.5 12s3.5-7 9.5-7 9.5 7 9.5 7-3.5 7-9.5 7-9.5-7-9.5-7Z"
                          stroke="currentColor"
                          strokeWidth="1.8"
                          strokeLinejoin="round"
                        />
                        <path
                          d="M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4Z"
                          stroke="currentColor"
                          strokeWidth="1.8"
                        />
                      </svg>
                      <span className="sr-only">{showPassword ? 'Ocultar' : 'Ver'}</span>
                    </Button>
                  </div>
                </div>
                {error && (
                  <div className="mt-12">
                    <Alert tone="danger">{error}</Alert>
                  </div>
                )}
                <div className="mt-12">
                  <Button type="submit" className="w-full">
                    Entrar
                  </Button>
                </div>
                <div className="upload-hint mt-2">
                  ¿No puedes entrar? Pide a tu consultor un enlace de activación o reset.
                </div>
              </form>
            )}
          </div>

          <div className="login-trust">
            <div className="trust-item">
              <div className="trust-dot" aria-hidden="true" />
              <div>
                <strong>Datos aislados por empresa</strong>
                <span>Multiempresa con permisos por cartera.</span>
              </div>
            </div>
            <div className="trust-item">
              <div className="trust-dot" aria-hidden="true" />
              <div>
                <strong>Auditoría y trazabilidad</strong>
                <span>Acciones sensibles con registro y motivo.</span>
              </div>
            </div>
            <div className="trust-item">
              <div className="trust-dot" aria-hidden="true" />
              <div>
                <strong>Operación en VPS</strong>
                <span>Métricas, alertas y límites por plan.</span>
              </div>
            </div>
          </div>
        </div>
      </div>
      </div>
      <div className="grid section login-features" hidden>
        <div className="card soft login-feature">
          <div className="login-feature-icon" aria-hidden="true" />
          <h3>Dashboards</h3>
          <p className="hero-sub">KPIs mensuales, tendencias y alertas accionables.</p>
        </div>
        <div className="card soft login-feature">
          <div className="login-feature-icon" aria-hidden="true" />
          <h3>Importaciones</h3>
          <p className="hero-sub">CSV/XLSX validado, staging y normalización automática.</p>
        </div>
        <div className="card soft login-feature">
          <div className="login-feature-icon" aria-hidden="true" />
          <h3>Reportes</h3>
          <p className="hero-sub">Informes HTML listos para exportar a PDF.</p>
        </div>
      </div>
    </div>
  )
}
