import { Route, Routes, Navigate } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import ImportsPage from './pages/ImportsPage'
import ReportsPage from './pages/ReportsPage'
import Layout from './components/Layout'
import { getToken } from './api'

export default function App() {
  const token = getToken()

  if (!token) {
    return <LoginPage />
  }

  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Navigate to="/dashboard" />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/imports" element={<ImportsPage />} />
        <Route path="/reports" element={<ReportsPage />} />
      </Routes>
    </Layout>
  )
}
