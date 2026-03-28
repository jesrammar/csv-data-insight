import { Route, Routes, Navigate } from 'react-router-dom'
import { lazy, Suspense } from 'react'
import Layout from './components/Layout'
import { getToken, getUserRole } from './api'
import Skeleton from './components/ui/Skeleton'

const LoginPage = lazy(() => import('./pages/LoginPage'))
const OverviewPage = lazy(() => import('./pages/OverviewPage'))
const DashboardPage = lazy(() => import('./pages/DashboardPage'))
const ImportsPage = lazy(() => import('./pages/ImportsPage'))
const ReportsPage = lazy(() => import('./pages/ReportsPage'))
const TribunalDashboardPage = lazy(() => import('./pages/TribunalDashboardPage'))
const UniversalDashboardPage = lazy(() => import('./pages/UniversalDashboardPage'))
const PricingPage = lazy(() => import('./pages/PricingPage'))
const AutomationPage = lazy(() => import('./pages/AutomationPage'))
const AdvisorPage = lazy(() => import('./pages/AdvisorPage'))

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
  const token = getToken()
  const role = getUserRole()
  const isClient = role === 'CLIENTE'

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
          <Route path="/" element={<Navigate to="/overview" />} />
          <Route path="/overview" element={<OverviewPage />} />
          <Route path="/dashboard" element={<DashboardPage />} />
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
              <Guard allow={!isClient}>
                <AutomationPage />
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
        </Routes>
      </Suspense>
    </Layout>
  )
}
