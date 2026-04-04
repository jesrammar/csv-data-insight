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
const BudgetDashboardPage = lazy(() => import('./pages/BudgetDashboardPage'))
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

function RouteFallback() {
  return (
    <div className="card section">
      <Skeleton style={{ width: '42%', height: 14 }} />
      <Skeleton style={{ width: '78%', height: 12, marginTop: 12 }} />
      <Skeleton style={{ width: '66%', height: 12, marginTop: 10 }} />
    </div>
  )
}

function Guard({ allow, children }: { allow: boolean; children: React.ReactNode }) {
  if (!allow) return <Navigate to="/overview" replace />
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
          <Route path="/" element={<Navigate to={isClient ? '/home' : '/overview'} />} />
          <Route
            path="/home"
            element={
              <Guard allow={isClient}>
                <ClientHomePage />
              </Guard>
            }
          />
          <Route path="/overview" element={isClient ? <Navigate to="/home" replace /> : <OverviewPage />} />
          <Route path="/dashboard" element={isClient ? <Navigate to="/cash" replace /> : <DashboardPage />} />
          <Route path="/cash" element={<DashboardPage />} />
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
          <Route path="/reports" element={<ReportsPage />} />
          <Route
            path="/tribunal"
            element={
              <Guard allow={!isClient}>
                <TribunalDashboardPage />
              </Guard>
            }
          />
          <Route
            path="/universal"
            element={
              <Guard allow={!isClient}>
                <UniversalDashboardPage />
              </Guard>
            }
          />
          <Route
            path="/universal/views"
            element={
              <Guard allow={!isClient}>
                <UniversalViewsPage />
              </Guard>
            }
          />
          <Route
            path="/universal/views/:viewId"
            element={
              <Guard allow={!isClient}>
                <UniversalViewPage />
              </Guard>
            }
          />
          <Route
            path="/budget"
            element={
              <Guard allow={!isClient}>
                <BudgetDashboardPage />
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
          <Route
            path="/automation"
            element={
              <Guard allow={isAdmin || isConsultor}>
                <AutomationPage />
              </Guard>
            }
          />
          <Route
            path="/tools"
            element={
              <Guard allow={!isClient}>
                <ToolsPage />
              </Guard>
            }
          />
          <Route
            path="/advisor"
            element={
              <Guard allow={!isClient}>
                <AdvisorPage />
              </Guard>
            }
          />
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
          <Route
            path="/portfolio"
            element={
              <Guard allow={isConsultor}>
                <PortfolioPage />
              </Guard>
            }
          />
          <Route
            path="/guides"
            element={
              <Guard allow={!isClient}>
                <GuidesPage />
              </Guard>
            }
          />
        </Routes>
      </Suspense>
    </Layout>
  )
}
