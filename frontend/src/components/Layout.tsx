import { useEffect } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getCompanies, getCompanySettings, getUserRole, logout } from '../api'
import CompanySelector from './CompanySelector'
import Button from './ui/Button'
import Icon from './ui/Icon'
import { setActiveCompanySelection, useCompanySelection } from '../hooks/useCompany'
import { getWorkPeriod, nowYm, setWorkPeriod } from '../utils/workPeriod'

export default function Layout({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const { id: companyId } = useCompanySelection()
  const { data: companies, error: companiesError, isPending: companiesPending } = useQuery({
    queryKey: ['companies'],
    queryFn: getCompanies,
    retry: 1
  })

  const { data: settings } = useQuery({
    queryKey: ['company-settings', companyId],
    queryFn: () => getCompanySettings(companyId as number),
    enabled: !!companyId
  })

  useEffect(() => {
    if (!companyId) return
    const local = getWorkPeriod(companyId)
    const server = (settings as any)?.workingPeriod ? String((settings as any).workingPeriod) : null
    const next = (local || server || nowYm()).trim()
    if (!local) setWorkPeriod(companyId, next)
  }, [companyId, settings])

  const role = getUserRole()
  const isClient = role === 'CLIENTE'
  const isAdmin = role === 'ADMIN'

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
    const list = (companies || []) as any[]
    if (!list.length) return

    const params = new URLSearchParams(location.search)
    const routeCompanyId = Number(params.get('companyId') || '')
    const routePeriod = String(params.get('period') || '').trim()
    if (!routeCompanyId || Number.isNaN(routeCompanyId)) return

    const targetCompany = list.find((company) => Number(company.id) === routeCompanyId)
    if (!targetCompany) return

    const currentRawId = localStorage.getItem('companyId')
    const currentId = currentRawId ? Number(currentRawId) : null
    if (currentId !== routeCompanyId) {
      setActiveCompanySelection(routeCompanyId, String(targetCompany.plan || 'BRONZE'))
    }

    if (routePeriod) {
      const currentPeriod = getWorkPeriod(routeCompanyId)
      if (currentPeriod !== routePeriod) setWorkPeriod(routeCompanyId, routePeriod)
    }
  }, [companies, location.search])

  useEffect(() => {
    document.body.classList.toggle('body-client', isClient)
    return () => document.body.classList.remove('body-client')
  }, [isClient])

  useEffect(() => {
    document.body.classList.remove('density-compact')
    localStorage.removeItem('uiDensity')
  }, [])

  useEffect(() => {
    window.scrollTo(0, 0)
    const mainArea = document.querySelector('.main-area')
    if (mainArea instanceof HTMLElement) mainArea.scrollTop = 0
  }, [location.pathname])

  async function handleLogout() {
    try {
      await logout()
    } catch {
      // ignore network errors on logout
    } finally {
      queryClient.clear()
      navigate('/')
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
          <span className="brand-sub">ASECON CONSULTING FLOW</span>
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
              <div className="nav-section">Ruta consultora</div>
              <NavLink to="/budget" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="overview" />
                Plan anual
              </NavLink>
              <NavLink to="/imports" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="imports" />
                Cargar datos
              </NavLink>
              <NavLink to="/workforce" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="dashboard" />
                Trabajadores
              </NavLink>
              <NavLink to="/settings/company" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="settings" />
                Ajustes
              </NavLink>
            </>
          )}
        </nav>

        <div className="side-glow" />
      </aside>

      <div className="main-area">
        <header className="top-bar">
          <div className="top-left">
            <div className="status-dot" />
            <span className="top-title">{isClient ? 'Panel del cliente' : 'Ruta consultora'}</span>
            <span className={`pill ${isClient ? 'pill-client' : 'pill-consultant'}`}>{isClient ? 'Cliente' : 'Consultoria'}</span>
            {companiesPending ? <span className="pill">Cargando empresas...</span> : null}
            {companiesError ? <span className="pill pill-danger">Error cargando empresas</span> : null}
          </div>
          <div className="nav-actions">
            <CompanySelector companies={companies || []} />
            {!isClient ? (
              <Button variant="ghost" size="sm" onClick={() => navigate('/settings/company')} disabled={!companyId} title="Ajustes por empresa gestionada">
                <Icon name="settings" /> Ajustes
              </Button>
            ) : null}
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
            <Button variant="ghost" size="sm" onClick={handleLogout} title="Cerrar sesion">
              <Icon name="logout" /> Salir
            </Button>
          </div>
        </header>
        <main className="container">
          <div key={location.key} className="route-stage">
            {children}
          </div>
        </main>
      </div>
    </div>
  )
}
