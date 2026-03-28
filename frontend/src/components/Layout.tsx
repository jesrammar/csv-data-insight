import { NavLink, useNavigate } from 'react-router-dom'
import { useEffect } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { clearTokens, getCompanies, getUserRole, logout } from '../api'
import CompanySelector from './CompanySelector'
import { useCompanySelection } from '../hooks/useCompany'
import Icon from './ui/Icon'
import Button from './ui/Button'

export default function Layout({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: companies, error: companiesError, isPending: companiesPending } = useQuery({
    queryKey: ['companies'],
    queryFn: getCompanies,
    retry: 1
  })
  const { plan } = useCompanySelection()
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const role = getUserRole()
  const isClient = role === 'CLIENTE'

  useEffect(() => {
    const list = (companies || []) as any[]
    if (!list.length) return
    const rawId = localStorage.getItem('companyId')
    const currentId = rawId ? Number(rawId) : null
    const exists = currentId ? list.some((c) => c.id === currentId) : false
    if (!currentId || !exists) {
      localStorage.setItem('companyId', String(list[0].id))
      localStorage.setItem('companyPlan', String(list[0].plan || 'BRONZE'))
      window.dispatchEvent(new Event('company-change'))
    }
  }, [companies])

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
            {companiesPending ? <span className="pill" style={{ marginLeft: 12 }}>Cargando empresas…</span> : null}
            {companiesError ? (
              <span
                className="pill"
                style={{ marginLeft: 12, borderColor: 'rgba(255,90,90,.35)', color: 'rgba(255,190,190,.95)' }}
              >
                Error cargando empresas
              </span>
            ) : null}
          </div>
          <div className="nav-actions">
            <span className="pill">ASECON Platform</span>
            <CompanySelector companies={companies || []} />
            {companiesError ? (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => queryClient.invalidateQueries({ queryKey: ['companies'] })}
                title="Reintentar cargar empresas"
              >
                Reintentar
              </Button>
            ) : null}
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
