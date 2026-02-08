import { useState, useEffect } from 'react'

type Company = { id: number; name: string; plan: string }

export default function CompanySelector({ companies }: { companies: Company[] }) {
  const [selected, setSelected] = useState<number | undefined>(() => {
    const value = localStorage.getItem('companyId')
    return value ? Number(value) : undefined
  })

  useEffect(() => {
    if (!selected && companies.length > 0) {
      setSelected(companies[0].id)
    }
  }, [companies, selected])

  useEffect(() => {
    if (selected) {
      localStorage.setItem('companyId', String(selected))
    }
  }, [selected])

  return (
    <select value={selected} onChange={(e) => setSelected(Number(e.target.value))}>
      {companies.map((c) => (
        <option key={c.id} value={c.id}>
          {c.name} ({c.plan})
        </option>
      ))}
    </select>
  )
}
