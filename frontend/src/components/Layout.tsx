import { NavLink, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { clearTokens, getCompanies, getUserRole, logout } from '../api'
import CompanySelector from './CompanySelector'
import { useCompanySelection } from '../hooks/useCompany'
import Icon from './ui/Icon'
import Button from './ui/Button'

export default function Layout({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const { data: companies } = useQuery({ queryKey: ['companies'], queryFn: getCompanies })
  const { plan } = useCompanySelection()
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const role = getUserRole()
  const isClient = role === 'CLIENTE'

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
      <div className="ambient-orb orb-1" aria-hidden="true" />
      <div className="ambient-orb orb-2" aria-hidden="true" />
      <div className="ambient-orb orb-3" aria-hidden="true" />
      <aside className="side-nav">
        <div className="brand-stack">
          <div className="brand">EnterpriseIQ</div>
          <span className="brand-sub">ASECON Advisory Suite</span>
        </div>
        <nav className="side-links">
          <NavLink to="/overview" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
            <Icon name="overview" />
            Vista ejecutiva
          </NavLink>
          <NavLink to="/dashboard" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
            <Icon name="dashboard" />
            Dashboard
          </NavLink>
          {!isClient ? (
            <NavLink to="/imports" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon name="imports" />
              Imports
            </NavLink>
          ) : null}
          {!isClient && hasGold ? (
            <NavLink to="/tribunal" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon name="tribunal" />
              Tribunal
            </NavLink>
          ) : null}
          {!isClient ? (
            <NavLink to="/universal" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon name="universal" />
              Universal
            </NavLink>
          ) : null}
          {!isClient ? (
            <NavLink to="/advisor" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon name="advisor" />
              Asesor
            </NavLink>
          ) : null}
          <NavLink to="/reports" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
            <Icon name="reports" />
            Reports
          </NavLink>
          {!isClient ? (
            <NavLink to="/automation" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon name="automation" />
              Automatización
            </NavLink>
          ) : null}
          {!isClient ? (
            <NavLink to="/pricing" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon name="pricing" />
              Pricing
            </NavLink>
          ) : null}
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
            <Button variant="ghost" size="sm" onClick={handleLogout} title="Cerrar sesión">
              <Icon name="logout" /> Salir
            </Button>
          </div>
        </header>
        <main className="container">{children}</main>
      </div>
    </div>
  )
}
