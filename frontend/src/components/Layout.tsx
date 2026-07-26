import { useEffect, useMemo, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getCompanies, getCompanySettings, getUserRole, logout } from '../api'
import CompanySelector from './CompanySelector'
import Button from './ui/Button'
import Icon from './ui/Icon'
import { setActiveCompanySelection, useCompanySelection } from '../hooks/useCompany'
import { getWorkPeriod, nowYm, setWorkPeriod } from '../utils/workPeriod'

function routeMatches(pathname: string, prefixes: string[]) {
  return prefixes.some((prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`))
}

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
  const isConsultor = role === 'CONSULTOR'
  const activePeriod = companyId ? getWorkPeriod(companyId) || nowYm() : nowYm()
  const monthlyCloseHref = `/monthly-close?period=${encodeURIComponent(activePeriod)}`

  const pathname = location.pathname
  const analysisRouteActive = useMemo(
    () =>
      routeMatches(pathname, [
        '/imports',
        '/dashboard',
        '/cash',
        '/tribunal',
        '/universal',
        '/pipeline',
        '/advisor',
        '/automation',
        '/tools',
        '/guides',
        '/portfolio',
        '/settings/company'
      ]),
    [pathname]
  )
  const adminRouteActive = useMemo(() => routeMatches(pathname, ['/admin']), [pathname])
  const [analysisOpen, setAnalysisOpen] = useState(() => analysisRouteActive)
  const [adminOpen, setAdminOpen] = useState(() => adminRouteActive)

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
    if (analysisRouteActive) setAnalysisOpen(true)
  }, [analysisRouteActive])

  useEffect(() => {
    if (adminRouteActive) setAdminOpen(true)
  }, [adminRouteActive])

  useEffect(() => {
    document.body.classList.remove('density-compact')
    localStorage.removeItem('uiDensity')
  }, [])

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
              <NavLink to="/overview" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="overview" />
                Vista ejecutiva
              </NavLink>
              <NavLink to={monthlyCloseHref} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="overview" />
                Cierre mensual
              </NavLink>
              <NavLink to="/reports" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="reports" />
                Entregables
              </NavLink>
              <NavLink to="/budget" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Icon name="overview" />
                Plan anual
              </NavLink>

              <details className="nav-group mt-12" open={analysisOpen} onToggle={(event) => setAnalysisOpen(event.currentTarget.open)}>
                <summary className="nav-section nav-section-toggle">
                  <span>Analisis</span>
                  <span className="nav-section-caret">{analysisOpen ? '-' : '+'}</span>
                </summary>
                <div className="nav-group-body">
                  <NavLink to="/imports" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="imports" />
                    Cargar datos
                  </NavLink>
                  <NavLink to="/dashboard" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="dashboard" />
                    Caja
                  </NavLink>
                  <NavLink to="/universal" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="dashboard" />
                    Universal
                  </NavLink>
                  <NavLink to="/tribunal" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="dashboard" />
                    Tribunal
                  </NavLink>
                  <NavLink to="/pipeline" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="overview" />
                    Pipeline
                  </NavLink>
                </div>
              </details>

              <details className="nav-group mt-3">
                <summary className="nav-section nav-section-toggle">
                  <span>Mas</span>
                  <span className="nav-section-caret">+</span>
                </summary>
                <div className="nav-group-body">
                  <NavLink to="/advisor" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="overview" />
                    Seguimiento
                  </NavLink>
                  {isConsultor ? (
                    <NavLink to="/portfolio" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                      <Icon name="overview" />
                      Cartera
                    </NavLink>
                  ) : null}
                  <NavLink to="/settings/company" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="settings" />
                    Ajustes empresa
                  </NavLink>
                  <NavLink to="/guides" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="help" />
                    Guias
                  </NavLink>
                  <NavLink to="/tools" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="overview" />
                    Herramientas
                  </NavLink>
                  <NavLink to="/automation" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <Icon name="overview" />
                    Automatizacion
                  </NavLink>
                </div>
              </details>

              {isAdmin ? (
                <details className="nav-group mt-3" open={adminOpen} onToggle={(event) => setAdminOpen(event.currentTarget.open)}>
                  <summary className="nav-section nav-section-toggle">
                    <span>Admin</span>
                    <span className="nav-section-caret">{adminOpen ? '-' : '+'}</span>
                  </summary>
                  <div className="nav-group-body">
                    <NavLink to="/admin/users" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                      <Icon name="admin" />
                      Usuarios y cartera
                    </NavLink>
                    <NavLink to="/admin/storage" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                      <Icon name="admin" />
                      Storage cleanup
                    </NavLink>
                  </div>
                </details>
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
