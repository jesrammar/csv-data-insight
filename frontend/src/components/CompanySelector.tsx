import { useState, useEffect } from 'react'
import { notifyCompanyChange } from '../hooks/useCompany'

type Company = { id: number; name: string; plan: string }

export default function CompanySelector({ companies }: { companies: Company[] }) {
  const [selected, setSelected] = useState<number | undefined>(() => {
    const value = localStorage.getItem('companyId')
    return value ? Number(value) : undefined
  })

  useEffect(() => {
    if (companies.length === 0) return
    const hasSelected = selected ? companies.some((c) => c.id === selected) : false
    if (!selected || !hasSelected) setSelected(companies[0].id)
  }, [companies, selected])

  useEffect(() => {
    if (selected) {
      const company = companies.find((c) => c.id === selected)
      if (!company) return
      localStorage.setItem('companyId', String(selected))
      if (company) {
        localStorage.setItem('companyPlan', company.plan)
        notifyCompanyChange()
      }
    }
  }, [selected, companies])

  const selectedCompany = companies.find((c) => c.id === selected)
  const plan = (selectedCompany?.plan || localStorage.getItem('companyPlan') || 'BRONZE').toUpperCase()
  const planTone = plan === 'PLATINUM' ? 'ok' : plan === 'GOLD' ? 'warn' : ''

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <span className={`badge ${planTone}`}>{plan}</span>
      <select
        aria-label="Seleccionar empresa"
        value={selected ?? ''}
        disabled={companies.length === 0}
        onChange={(e) => setSelected(Number(e.target.value))}
      >
        {companies.length === 0 ? <option value="">Sin empresas</option> : null}
        {companies.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
      </select>
    </div>
  )
}
