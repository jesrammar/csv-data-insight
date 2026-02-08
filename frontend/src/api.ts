const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080'

export type LoginResponse = { accessToken: string; role: string; userId: number }

export function setToken(token: string) {
  localStorage.setItem('token', token)
}

export function getToken() {
  return localStorage.getItem('token')
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>)
  }
  if (token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(`${API_URL}${path}`, { ...options, headers })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  const contentType = res.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    return res.json()
  }
  return res.text() as unknown as T
}

export async function login(email: string, password: string) {
  return request<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  })
}

export async function getCompanies() {
  return request('/api/companies/mine')
}

export async function getDashboard(companyId: number, from: string, to: string) {
  return request(`/api/companies/${companyId}/dashboard?from=${from}&to=${to}`)
}

export async function getImports(companyId: number) {
  return request(`/api/companies/${companyId}/imports`)
}

export async function uploadImport(companyId: number, period: string, file: File) {
  const token = getToken()
  const form = new FormData()
  form.append('period', period)
  form.append('file', file)

  const res = await fetch(`${API_URL}/api/companies/${companyId}/imports`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form
  })

  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.json()
}

export async function getReports(companyId: number) {
  return request(`/api/companies/${companyId}/reports`)
}

export async function generateReport(companyId: number, period: string) {
  return request(`/api/companies/${companyId}/reports`, {
    method: 'POST',
    body: JSON.stringify({ period })
  })
}

export async function getReportContent(companyId: number, reportId: number) {
  return request<string>(`/api/companies/${companyId}/reports/${reportId}/content`)
}
