import { useEffect, useState } from 'react'

type CompanySelection = { id: number | null; plan: string }

const COMPANY_EVENT = 'company-change'

function readSelection(): CompanySelection {
  const rawId = localStorage.getItem('companyId')
  const rawPlan = localStorage.getItem('companyPlan')
  return {
    id: rawId ? Number(rawId) : null,
    plan: (rawPlan || 'BRONZE').toUpperCase()
  }
}

export function notifyCompanyChange() {
  window.dispatchEvent(new Event(COMPANY_EVENT))
}

export function useCompanySelection(): CompanySelection {
  const [selection, setSelection] = useState<CompanySelection>(() => readSelection())

  useEffect(() => {
    function handleChange() {
      setSelection(readSelection())
    }
    window.addEventListener(COMPANY_EVENT, handleChange)
    window.addEventListener('storage', handleChange)
    return () => {
      window.removeEventListener(COMPANY_EVENT, handleChange)
      window.removeEventListener('storage', handleChange)
    }
  }, [])

  return selection
}
