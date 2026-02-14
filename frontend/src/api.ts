const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080'

export type LoginResponse = {
  accessToken: string
  refreshToken: string
  role: string
  userId: number
  accessTokenExpiresInSeconds: number
}

const ACCESS_TOKEN_KEY = 'token'
const REFRESH_TOKEN_KEY = 'refresh_token'

export function setTokens(accessToken: string, refreshToken: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function setToken(accessToken: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
}

export function getToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

type RequestConfig = { auth?: boolean; retry?: boolean }

let refreshPromise: Promise<string | null> | null = null

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return null
  if (refreshPromise) return refreshPromise

  refreshPromise = (async () => {
    const res = await fetch(`${API_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken })
    })
    if (!res.ok) {
      clearTokens()
      return null
    }
    const data = (await res.json()) as LoginResponse
    setTokens(data.accessToken, data.refreshToken)
    return data.accessToken
  })()

  const token = await refreshPromise
  refreshPromise = null
  return token
}

async function request<T>(path: string, options: RequestInit = {}, config: RequestConfig = {}): Promise<T> {
  const { auth = true, retry = true } = config
  const token = getToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>)
  }
  if (auth && token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(`${API_URL}${path}`, { ...options, headers })
  if (res.status === 401 && retry && path !== '/api/auth/login' && path !== '/api/auth/refresh') {
    const newToken = await refreshAccessToken()
    if (newToken) {
      return request<T>(path, options, { auth: true, retry: false })
    }
  }
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

export async function logout(refreshToken?: string) {
  return request<void>(
    '/api/auth/logout',
    {
      method: 'POST',
      body: JSON.stringify({ refreshToken: refreshToken || getRefreshToken() })
    },
    { auth: true, retry: false }
  )
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

  let res = await fetch(`${API_URL}/api/companies/${companyId}/imports`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/imports`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${newToken}` },
        body: form
      })
    }
  }

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
