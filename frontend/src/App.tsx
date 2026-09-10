import { Route, Routes, Navigate } from 'react-router-dom'
import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import Layout from './components/Layout'
import { bootstrapAuth, getAccessToken, getUserRole, onAuthChange } from './api'
import Skeleton from './components/ui/Skeleton'

const LoginPage = lazy(() => import('./pages/LoginPage'))
const OverviewPage = lazy(() => import('./pages/OverviewPage'))
const DashboardPage = lazy(() => import('./pages/DashboardPage'))
const ImportsPage = lazy(() => import('./pages/ImportsPage'))
const ReportsPage = lazy(() => import('./pages/ReportsPage'))
const TribunalDashboardPage = lazy(() => import('./pages/TribunalDashboardPage'))
const UniversalDashboardPage = lazy(() => import('./pages/UniversalDashboardPage'))
const UniversalViewsPage = lazy(() => import('./pages/UniversalViewsPage'))
const UniversalViewPage = lazy(() => import('./pages/UniversalViewPage'))
const AnnualPlanningPage = lazy(() => import('./pages/AnnualPlanningPage'))
const BudgetDashboardPage = lazy(() => import('./pages/BudgetDashboardPage'))
const WorkforcePage = lazy(() => import('./pages/WorkforcePage'))
const PricingPage = lazy(() => import('./pages/PricingPage'))
const AutomationPage = lazy(() => import('./pages/AutomationPage'))
const AdvisorPage = lazy(() => import('./pages/AdvisorPage'))
const ToolsPage = lazy(() => import('./pages/ToolsPage'))
const ClientHomePage = lazy(() => import('./pages/ClientHomePage'))
const AlertsPage = lazy(() => import('./pages/AlertsPage'))
const HelpPage = lazy(() => import('./pages/HelpPage'))
const AuditPage = lazy(() => import('./pages/AuditPage'))
const AdminStoragePage = lazy(() => import('./pages/AdminStoragePage'))
const AdminUsersPage = lazy(() => import('./pages/AdminUsersPage'))
const PortfolioPage = lazy(() => import('./pages/PortfolioPage'))
const GuidesPage = lazy(() => import('./pages/GuidesPage'))
const CompanySettingsPage = lazy(() => import('./pages/CompanySettingsPage'))
const PipelineCenterPage = lazy(() => import('./pages/PipelineCenterPage'))
const MonthlyClosePage = lazy(() => import('./pages/MonthlyClosePage'))

function RouteFallback() {
  return (
    <div className="route-fallback" role="status" aria-live="polite">
      <div className="route-fallback-heading">
        <Skeleton className="route-fallback-kicker" />
        <Skeleton className="route-fallback-title" />
        <Skeleton className="route-fallback-copy" />
      </div>
      <div className="route-fallback-grid" aria-hidden="true">
        {[0, 1, 2].map((item) => (
          <div key={item} className="route-fallback-card">
            <Skeleton className="route-fallback-card-label" />
            <Skeleton className="route-fallback-card-value" />
            <Skeleton className="route-fallback-card-copy" />
          </div>
        ))}
      </div>
      <span className="sr-only">Cargando espacio de trabajo…</span>
    </div>
  )
}

function Guard({ allow, children }: { allow: boolean; children: React.ReactNode }) {
  if (!allow) return <Navigate to={getUserRole() === 'CLIENTE' ? '/home' : '/budget'} replace />
  return <>{children}</>
}

export default function App() {
  const [ready, setReady] = useState(false)
  const [token, setToken] = useState<string | null>(() => getAccessToken())
  const role = getUserRole()
  const isClient = useMemo(() => role === 'CLIENTE', [role])
  const isAdmin = useMemo(() => role === 'ADMIN', [role])
  const isConsultor = useMemo(() => role === 'CONSULTOR', [role])

  useEffect(() => {
    return onAuthChange(() => {
      setToken(getAccessToken())
    })
  }, [])

  useEffect(() => {
    let cancelled = false
    bootstrapAuth().finally(() => {
      if (!cancelled) setReady(true)
    })
    return () => {
      cancelled = true
    }
  }, [])

  if (!ready) {
    return <RouteFallback />
  }

  if (!token) {
    return (
      <Suspense fallback={<RouteFallback />}>
        <LoginPage />
      </Suspense>
    )
  }

  return (
    <Layout>
      <Suspense fallback={<RouteFallback />}>
        <Routes>
          <Route path="/" element={<Navigate to={isClient ? '/home' : '/budget'} />} />
          <Route
            path="/home"
            element={
              <Guard allow={isClient}>
                <ClientHomePage />
              </Guard>
            }
          />
          <Route path="/overview" element={<Navigate to={isClient ? '/home' : '/budget'} replace />} />
          <Route path="/dashboard" element={<Navigate to={isClient ? '/cash' : '/budget'} replace />} />
          <Route path="/cash" element={isClient ? <DashboardPage /> : <Navigate to="/budget" replace />} />
          <Route
            path="/alerts"
            element={
              <Guard allow={isClient}>
                <AlertsPage />
              </Guard>
            }
          />
          <Route
            path="/help"
            element={
              <Guard allow={isClient}>
                <HelpPage />
              </Guard>
            }
          />
          <Route
            path="/imports"
            element={
              <Guard allow={!isClient}>
                <ImportsPage />
              </Guard>
            }
          />
          <Route path="/reports" element={isClient ? <ReportsPage /> : <Navigate to="/budget" replace />} />
          <Route path="/tribunal" element={<Navigate to={isClient ? '/home' : '/imports'} replace />} />
          <Route path="/universal" element={<Navigate to={isClient ? '/home' : '/imports'} replace />} />
          <Route path="/universal/views" element={<Navigate to={isClient ? '/home' : '/imports'} replace />} />
          <Route path="/universal/views/:viewId" element={<Navigate to={isClient ? '/home' : '/imports'} replace />} />
          <Route
            path="/budget"
            element={
              <Guard allow={!isClient}>
                <AnnualPlanningPage />
              </Guard>
            }
          />
          <Route
            path="/budget/dashboard"
            element={
              <Guard allow={!isClient}>
                <BudgetDashboardPage />
              </Guard>
            }
          />
          <Route
            path="/workforce"
            element={
              <Guard allow={!isClient}>
                <WorkforcePage />
              </Guard>
            }
          />
          <Route
            path="/pricing"
            element={
              <Guard allow={!isClient}>
                <PricingPage />
              </Guard>
            }
          />
          <Route path="/automation" element={<Navigate to={isClient ? '/home' : '/budget'} replace />} />
          <Route path="/tools" element={<Navigate to={isClient ? '/home' : '/budget'} replace />} />
          <Route path="/advisor" element={<Navigate to={isClient ? '/home' : '/budget'} replace />} />
          <Route
            path="/audit"
            element={
              <Guard allow={!isClient}>
                <AuditPage />
              </Guard>
            }
          />
          <Route
            path="/admin/storage"
            element={
              <Guard allow={isAdmin}>
                <AdminStoragePage />
              </Guard>
            }
          />
          <Route
            path="/admin/users"
            element={
              <Guard allow={isAdmin}>
                <AdminUsersPage />
              </Guard>
            }
          />
          <Route path="/portfolio" element={<Navigate to={isClient ? '/home' : '/budget'} replace />} />
          <Route path="/guides" element={<Navigate to={isClient ? '/home' : '/imports'} replace />} />
          <Route
            path="/settings/company"
            element={
              <Guard allow={!isClient}>
                <CompanySettingsPage />
              </Guard>
            }
          />
          <Route path="/pipeline" element={<Navigate to={isClient ? '/home' : '/imports'} replace />} />
          <Route path="/monthly-close" element={<Navigate to={isClient ? '/home' : '/budget'} replace />} />
        </Routes>
      </Suspense>
    </Layout>
  )
}
