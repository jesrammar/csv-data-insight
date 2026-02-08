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
      <div className="card" style={{ maxWidth: 420, margin: '60px auto' }}>
        <h2>Ingreso a EnterpriseIQ</h2>
        <form onSubmit={handleSubmit}>
          <div>
            <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="email" />
          </div>
          <div style={{ marginTop: 8 }}>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="password" />
          </div>
          {error && <p className="error">{error}</p>}
          <button type="submit" style={{ marginTop: 12 }}>Entrar</button>
        </form>
      </div>
    </div>
  )
}
