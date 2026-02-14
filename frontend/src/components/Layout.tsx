import { NavLink, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { clearTokens, getCompanies, logout } from '../api'
import CompanySelector from './CompanySelector'

export default function Layout({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const { data: companies } = useQuery({ queryKey: ['companies'], queryFn: getCompanies })

  async function handleLogout() {
    try {
      await logout()
    } catch {
      // ignore network errors on logout
    } finally {
      clearTokens()
      navigate('/')
      window.location.reload()
    }
  }

  return (
    <div className="app-shell">
      <aside className="side-nav">
        <div className="brand-stack">
          <div className="brand">EnterpriseIQ</div>
          <span className="brand-sub">ASECON Advisory Suite</span>
        </div>
        <nav className="side-links">
          <NavLink to="/dashboard" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
            Dashboard
          </NavLink>
          <NavLink to="/imports" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
            Imports
          </NavLink>
          <NavLink to="/reports" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
            Reports
          </NavLink>
        </nav>
        <div className="side-glow" />
      </aside>
      <div className="main-area">
        <header className="top-bar">
          <div className="top-left">
            <div className="status-dot" />
            <span className="top-title">Control financiero operativo</span>
          </div>
          <div className="nav-actions">
            <span className="pill">ASECON Platform</span>
            <CompanySelector companies={companies || []} />
            <button onClick={handleLogout}>Salir</button>
          </div>
        </header>
        <main className="container">{children}</main>
      </div>
    </div>
  )
}
