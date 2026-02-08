import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getCompanies } from '../api'
import CompanySelector from './CompanySelector'

export default function Layout({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const { data: companies } = useQuery({ queryKey: ['companies'], queryFn: getCompanies })

  function logout() {
    localStorage.removeItem('token')
    navigate('/')
    window.location.reload()
  }

  return (
    <div>
      <div className="nav">
        <strong>EnterpriseIQ</strong>
        <Link to="/dashboard">Dashboard</Link>
        <Link to="/imports">Imports</Link>
        <Link to="/reports">Reports</Link>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 12, alignItems: 'center' }}>
          <CompanySelector companies={companies || []} />
          <button onClick={logout}>Salir</button>
        </div>
      </div>
      <div className="container">{children}</div>
    </div>
  )
}
