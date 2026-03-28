import { useEffect, useState } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { clearTokens, getCompanies, getUserRole, logout } from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import CompanySelector from './CompanySelector'
import Button from './ui/Button'
import Icon from './ui/Icon'

export default function Layout({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: companies, error: companiesError, isPending: companiesPending } = useQuery({
    queryKey: ['companies'],
    queryFn: getCompanies,
    retry: 1
  })

  const { plan } = useCompanySelection()
  const role = getUserRole()
  const isClient = role === 'CLIENTE'
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const [consultingOpen, setConsultingOpen] = useState<boolean>(() => {
    try {
      return localStorage.getItem('nav_consultoria_open') === '1'
    } catch {
      return false
    }
  })

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

  useEffect(() => {
    document.body.classList.toggle('body-client', isClient)
    return () => document.body.classList.remove('body-client')
  }, [isClient])

  useEffect(() => {
    try {
      localStorage.setItem('nav_consultoria_open', consultingOpen ? '1' : '0')
    } catch {
      // ignore
    }
  }, [consultingOpen])

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
    <div className={`app-shell ${isClient ? 'mode-client' : 'mode-consultant'}`}>
      <div className="ambient-orb orb-1" aria-hidden="true" />
      <div className="ambient-orb orb-2" aria-hidden="true" />
      <div className="ambient-orb orb-3" aria-hidden="true" />

      <aside className="side-nav">
        <div className="brand-stack">
          <div className="brand">EnterpriseIQ</div>
          <span className="brand-sub">ASECON Advisory Suite</span>
        </div>

        <nav className="side-links">
          {isClient ? (
            <>
              <div className="nav-section">Operativa</div>
              <NavLink to="/home" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="home" />
                Resumen
              </NavLink>
              <NavLink to="/cash" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="dashboard" />
                Caja
              </NavLink>
              <NavLink to="/alerts" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="alerts" />
                Alertas
              </NavLink>
              <NavLink to="/reports" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="reports" />
                Informes
              </NavLink>
              <NavLink to="/help" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="help" />
                Ayuda
              </NavLink>
            </>
          ) : (
            <>
              <div className="nav-section">Operativa</div>
              <NavLink to="/overview" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="overview" />
                Vista ejecutiva
              </NavLink>
              <NavLink to="/dashboard" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="dashboard" />
                Caja
              </NavLink>
              <NavLink to="/imports" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="imports" />
                Cargar datos
              </NavLink>
              <NavLink to="/reports" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="reports" />
                Entregables
              </NavLink>

              <button
                type="button"
                className="nav-section nav-section-toggle"
                style={{ marginTop: 12 }}
                onClick={() => setConsultingOpen((v) => !v)}
                aria-expanded={consultingOpen}
              >
                <span>Consultoría</span>
                <span className="nav-section-caret">{consultingOpen ? '▾' : '▸'}</span>
              </button>

              {consultingOpen ? (
                <>
                  {hasGold ? (
                    <NavLink to="/tribunal" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                      <Icon name="tribunal" />
                      Cumplimiento (Tribunal)
                    </NavLink>
                  ) : null}
                  <NavLink to="/universal" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="universal" />
                    Análisis avanzado
                  </NavLink>
                  <NavLink to="/advisor" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="advisor" />
                    Asesor
                  </NavLink>
                  <NavLink to="/automation" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="automation" />
                    Tareas automáticas
                  </NavLink>
                  <NavLink to="/pricing" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="pricing" />
                    Planes
                  </NavLink>
                </>
              ) : null}
            </>
          )}
        </nav>

        <div className="side-glow" />
      </aside>

      <div className="main-area">
        <header className="top-bar">
          <div className="top-left">
            <div className="status-dot" />
            <span className="top-title">{isClient ? 'Panel operativo' : 'Control financiero operativo'}</span>
            <span className={`pill ${isClient ? 'pill-client' : 'pill-consultant'}`}>{isClient ? 'Cliente' : 'Consultoría'}</span>
            {companiesPending ? <span className="pill" style={{ marginLeft: 12 }}>Cargando empresas...</span> : null}
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
            {!isClient ? <span className="pill">ASECON Platform</span> : null}
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
