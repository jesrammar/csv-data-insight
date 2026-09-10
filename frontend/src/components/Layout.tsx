import { useEffect, useRef, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getCompanies, getCompanySettings, getUserRole, logout } from '../api'
import CompanySelector from './CompanySelector'
import Button from './ui/Button'
import Icon, { type IconName } from './ui/Icon'
import { setActiveCompanySelection, useCompanySelection } from '../hooks/useCompany'
import { getWorkPeriod, nowYm, setWorkPeriod } from '../utils/workPeriod'

type WorkspaceNavItem = {
  to: string
  label: string
  description: string
  icon: IconName
}

export default function Layout({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [commandOpen, setCommandOpen] = useState(false)
  const [commandQuery, setCommandQuery] = useState('')
  const [activeCommand, setActiveCommand] = useState(0)
  const commandTriggerRef = useRef<HTMLButtonElement>(null)
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
  const primaryNavItems: WorkspaceNavItem[] = isClient
    ? [
        { to: '/home', label: 'Resumen', description: 'Situación y próximos pasos', icon: 'home' },
        { to: '/cash', label: 'Caja', description: 'Entradas, salidas y saldo', icon: 'dashboard' },
        { to: '/alerts', label: 'Alertas', description: 'Señales que requieren atención', icon: 'alerts' },
        { to: '/reports', label: 'Informes', description: 'Entregables del periodo', icon: 'reports' },
        { to: '/help', label: 'Ayuda', description: 'Guía de uso del panel', icon: 'help' }
      ]
    : [
        { to: '/budget', label: 'Plan anual', description: 'Presupuesto y lectura ejecutiva', icon: 'overview' },
        { to: '/imports', label: 'Cargar datos', description: 'Ingesta y validación de ficheros', icon: 'imports' },
        { to: '/workforce', label: 'Trabajadores', description: 'Cartera, actividad y costes', icon: 'dashboard' },
        { to: '/settings/company', label: 'Ajustes', description: 'Configuración por empresa', icon: 'settings' },
        { to: '/pricing', label: 'Planes', description: 'Capacidades disponibles', icon: 'pricing' }
      ]
  const commandItems = primaryNavItems
  const normalizedCommandQuery = commandQuery.trim().toLocaleLowerCase('es')
  const filteredCommandItems = normalizedCommandQuery
    ? commandItems.filter((item) => `${item.label} ${item.description}`.toLocaleLowerCase('es').includes(normalizedCommandQuery))
    : commandItems
  const routeLabel = (() => {
    const path = location.pathname
    if (path === '/home') return 'Resumen'
    if (path === '/cash') return 'Caja'
    if (path === '/alerts') return 'Alertas'
    if (path === '/reports') return 'Informes'
    if (path === '/help') return 'Ayuda'
    if (path === '/budget/dashboard') return 'Análisis anual'
    if (path === '/budget') return 'Plan anual'
    if (path === '/imports') return 'Cargar datos'
    if (path === '/workforce') return 'Trabajadores'
    if (path === '/settings/company') return 'Ajustes'
    if (path === '/audit') return 'Auditoría'
    if (path === '/pricing') return 'Planes'
    if (path === '/admin/users') return 'Usuarios'
    if (path === '/admin/storage') return 'Almacenamiento'
    if (path.startsWith('/admin')) return 'Administración'
    return isClient ? 'Panel del cliente' : 'Ruta consultora'
  })()

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setCommandOpen((open) => {
          if (open) window.requestAnimationFrame(() => commandTriggerRef.current?.focus())
          return !open
        })
      } else if (event.key === 'Escape') {
        closeCommand()
        setMobileMenuOpen(false)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  useEffect(() => {
    setMobileMenuOpen(false)
    setCommandOpen(false)
  }, [location.pathname])

  useEffect(() => {
    if (!commandOpen) {
      setCommandQuery('')
      setActiveCommand(0)
    }
    document.body.classList.toggle('command-open', commandOpen)
    return () => document.body.classList.remove('command-open')
  }, [commandOpen])

  useEffect(() => {
    setActiveCommand(0)
  }, [commandQuery])

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

  function openCommand() {
    setCommandOpen(true)
    setActiveCommand(0)
  }

  function closeCommand() {
    setCommandOpen((open) => {
      if (open) window.requestAnimationFrame(() => commandTriggerRef.current?.focus())
      return false
    })
  }

  function runCommand(path: string) {
    setCommandOpen(false)
    navigate(path)
  }

  return (
    <div className={`app-shell ${isClient ? 'mode-client' : 'mode-consultant'}`}>
      <a className="skip-link" href="#workspace-main">Saltar al contenido</a>
      <div className="ambient-orb orb-1" aria-hidden="true" />
      <div className="ambient-orb orb-2" aria-hidden="true" />
      <div className="ambient-orb orb-3" aria-hidden="true" />

      <aside className={`side-nav ${mobileMenuOpen ? 'mobile-open' : ''}`}>
        <div className="brand-stack">
          <div className="brand-mark" aria-hidden="true">
            <span>EI</span>
          </div>
          <div className="brand-copy">
            <div className="brand">EnterpriseIQ</div>
            <span className="brand-sub">ASECON · Intelligence</span>
          </div>
          <button
            className="mobile-menu-button"
            type="button"
            aria-label={mobileMenuOpen ? 'Cerrar navegación' : 'Abrir navegación'}
            aria-expanded={mobileMenuOpen}
            aria-controls="workspace-navigation"
            onClick={() => setMobileMenuOpen((open) => !open)}
          >
            <Icon name={mobileMenuOpen ? 'close' : 'menu'} size={20} />
          </button>
        </div>

        <nav id="workspace-navigation" className="side-links" aria-label="Navegación principal">
          <div className="nav-section">{isClient ? 'Operativa' : 'Ruta consultora'}</div>
          {primaryNavItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              onClick={() => setMobileMenuOpen(false)}
              className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
            >
              <Icon name={item.icon} />
              <span className="nav-link-copy">
                <strong>{item.label}</strong>
                <small>{item.description}</small>
              </span>
            </NavLink>
          ))}
        </nav>

        <div className="side-footer">
          <div className="side-footer-icon" aria-hidden="true"><Icon name="check" size={15} /></div>
          <div>
            <strong>Workspace seguro</strong>
            <span>Datos aislados por empresa</span>
          </div>
        </div>
        <div className="side-glow" aria-hidden="true" />
      </aside>

      <div className="main-area">
        <header className="top-bar">
          <div className="top-left">
            <div
              className={`status-dot ${companiesError ? 'status-error' : companiesPending ? 'status-pending' : 'status-online'}`}
              title={companiesError ? 'Conexión con datos interrumpida' : companiesPending ? 'Sincronizando datos' : 'Datos conectados'}
              aria-hidden="true"
            />
            <span className="top-title">{isClient ? 'Panel cliente' : 'Consultoría'}</span>
            <span className="top-divider" aria-hidden="true">/</span>
            <span className="top-current">{routeLabel}</span>
            <span className={`pill ${isClient ? 'pill-client' : 'pill-consultant'}`}>{isClient ? 'Cliente' : 'Consultoria'}</span>
            {companiesPending ? <span className="pill">Cargando empresas...</span> : null}
            {companiesError ? <span className="pill pill-danger">Error cargando empresas</span> : null}
          </div>
          <div className="nav-actions">
            <button
              ref={commandTriggerRef}
              className="command-trigger"
              type="button"
              onClick={openCommand}
              aria-label="Buscar una sección"
              aria-haspopup="dialog"
              aria-expanded={commandOpen}
            >
              <Icon name="search" size={17} />
              <span>Buscar</span>
              <kbd>Ctrl K</kbd>
            </button>
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
        <main id="workspace-main" className="container" tabIndex={-1}>
          <div key={location.key} className="route-stage">
            {children}
          </div>
        </main>
      </div>

      {commandOpen ? (
        <div
          className="command-overlay"
          role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) closeCommand()
          }}
        >
          <section
            className="command-palette"
            role="dialog"
            aria-modal="true"
            aria-label="Navegación rápida"
            onKeyDown={(event) => {
              if (event.key !== 'Tab') return
              const focusable = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('input, button, [tabindex]:not([tabindex="-1"])'))
                .filter((element) => !element.hasAttribute('disabled'))
              const first = focusable[0]
              const last = focusable[focusable.length - 1]
              if (event.shiftKey && document.activeElement === first) {
                event.preventDefault()
                last?.focus()
              } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault()
                first?.focus()
              }
            }}
          >
            <div className="command-search">
              <Icon name="search" size={21} />
              <input
                autoFocus
                value={commandQuery}
                onChange={(event) => setCommandQuery(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'ArrowDown') {
                    event.preventDefault()
                    setActiveCommand((index) => Math.max(0, Math.min(index + 1, filteredCommandItems.length - 1)))
                  } else if (event.key === 'ArrowUp') {
                    event.preventDefault()
                    setActiveCommand((index) => Math.max(index - 1, 0))
                  } else if (event.key === 'Enter' && filteredCommandItems[activeCommand]) {
                    event.preventDefault()
                    runCommand(filteredCommandItems[activeCommand].to)
                  }
                }}
                placeholder="Buscar sección o función..."
                aria-label="Buscar sección o función"
              />
              <button className="command-close" type="button" onClick={closeCommand} aria-label="Cerrar búsqueda">
                <kbd>Esc</kbd>
                <Icon name="close" size={15} />
              </button>
            </div>
            <div className="command-context">
              <span>Navegación rápida</span>
              <small>{filteredCommandItems.length} {filteredCommandItems.length === 1 ? 'resultado' : 'resultados'} · {isClient ? 'Espacio cliente' : 'Consultoría'}</small>
            </div>
            <div className="command-results" role="listbox">
              {filteredCommandItems.length ? filteredCommandItems.map((item, index) => (
                <button
                  key={item.to}
                  type="button"
                  className={`command-result ${index === activeCommand ? 'is-active' : ''}`}
                  onMouseEnter={() => setActiveCommand(index)}
                  onClick={() => runCommand(item.to)}
                  role="option"
                  aria-selected={index === activeCommand}
                >
                  <span className="command-result-icon"><Icon name={item.icon} size={19} /></span>
                  <span className="command-result-copy">
                    <strong>{item.label}</strong>
                    <small>{item.description}</small>
                  </span>
                  <span className="command-result-arrow" aria-hidden="true">→</span>
                </button>
              )) : (
                <div className="command-empty">
                  <Icon name="search" size={22} />
                  <strong>Sin coincidencias</strong>
                  <span>Prueba con otro término.</span>
                </div>
              )}
            </div>
            <footer className="command-footer">
              <span><kbd>↑</kbd><kbd>↓</kbd> para moverte</span>
              <span><kbd>Enter</kbd> para abrir</span>
            </footer>
          </section>
        </div>
      ) : null}
    </div>
  )
}
