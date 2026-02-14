import { Link, NavLink, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { clearTokens, getCompanies, logout } from '../api'
import CompanySelector from './CompanySelector'

export default function Layout({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const { data: companies } = useQuery({ queryKey: ['companies'], queryFn: getCompanies })

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
    <div>
      <div className="nav">
        <span className="brand">EnterpriseIQ</span>
        <NavLink to="/dashboard" className={({ isActive }) => (isActive ? 'active' : undefined)}>
          Dashboard
        </NavLink>
        <NavLink to="/imports" className={({ isActive }) => (isActive ? 'active' : undefined)}>
          Imports
        </NavLink>
        <NavLink to="/reports" className={({ isActive }) => (isActive ? 'active' : undefined)}>
          Reports
        </NavLink>
        <div className="nav-actions">
          <span className="pill">ASECON Platform</span>
          <CompanySelector companies={companies || []} />
          <button onClick={handleLogout}>Salir</button>
        </div>
      </div>
      <div className="container">{children}</div>
    </div>
  )
}
