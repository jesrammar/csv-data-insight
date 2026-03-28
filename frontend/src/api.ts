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
const USER_ROLE_KEY = 'user_role'
const USER_ID_KEY = 'user_id'
const COMPANY_ID_KEY = 'companyId'
const COMPANY_PLAN_KEY = 'companyPlan'

export function setTokens(accessToken: string, refreshToken: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function setToken(accessToken: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
}

export function setUserMeta(role: string, userId: number) {
  localStorage.setItem(USER_ROLE_KEY, String(role || ''))
  localStorage.setItem(USER_ID_KEY, String(userId ?? ''))
}

export function getToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function getUserRole() {
  return (localStorage.getItem(USER_ROLE_KEY) || '').toUpperCase()
}

export function getUserId() {
  const raw = localStorage.getItem(USER_ID_KEY)
  return raw ? Number(raw) : null
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(USER_ROLE_KEY)
  localStorage.removeItem(USER_ID_KEY)
  localStorage.removeItem(COMPANY_ID_KEY)
  localStorage.removeItem(COMPANY_PLAN_KEY)
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
    if (data.role && typeof data.userId === 'number') {
      setUserMeta(data.role, data.userId)
    }
    return data.accessToken
  })()

  const token = await refreshPromise
  refreshPromise = null
  return token
}

async function request<T = any>(path: string, options: RequestInit = {}, config: RequestConfig = {}): Promise<T> {
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
  return request<ImportJob[]>(`/api/companies/${companyId}/imports`)
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

export type AlertDto = {
  id: number
  companyId: number
  period: string
  type: string
  message: string
  createdAt: string
}

export async function getAlerts(companyId: number, period?: string) {
  const q = period ? `?period=${encodeURIComponent(period)}` : ''
  return request<AlertDto[]>(`/api/companies/${companyId}/alerts${q}`)
}

export async function getTribunalSummary(companyId: number) {
  return request(`/api/companies/${companyId}/tribunal/summary`)
}

export type TransactionDto = {
  id: number
  period: string
  txnDate: string
  description: string
  amount: number
  counterparty?: string | null
}

export type TransactionPage = {
  items: TransactionDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

export type TransactionDailyAgg = {
  date: string
  inflows: number
  outflows: number
  net: number
  count: number
}

export type TransactionCounterpartyAgg = {
  counterparty: string
  total: number
  count: number
}

export type TransactionTotals = {
  inflows: number
  outflows: number
  net: number
  count: number
}

export type TransactionAnalytics = {
  totals: TransactionTotals
  daily: TransactionDailyAgg[]
  topCounterparties: TransactionCounterpartyAgg[]
  categories: TransactionCategoryAgg[]
  anomalies: TransactionAnomaly[]
}

export type TransactionCategoryAgg = {
  category: string
  total: number
  inflows: number
  outflows: number
  count: number
}

export type TransactionAnomaly = {
  date: string
  net: number
  score: number
  reason: string
}

export async function getTransactions(
  companyId: number,
  params: {
    period?: string
    fromDate?: string
    toDate?: string
    q?: string
    minAmount?: number
    maxAmount?: number
    direction?: 'in' | 'out'
    page?: number
    size?: number
  } = {}
) {
  const qs = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    qs.set(k, String(v))
  })
  const query = qs.toString()
  return request<TransactionPage>(`/api/companies/${companyId}/transactions${query ? `?${query}` : ''}`)
}

export async function getTransactionAnalytics(
  companyId: number,
  params: {
    period?: string
    fromDate?: string
    toDate?: string
    q?: string
    minAmount?: number
    maxAmount?: number
    direction?: 'in' | 'out'
    topN?: number
  } = {}
) {
  const qs = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    qs.set(k, String(v))
  })
  const query = qs.toString()
  return request<TransactionAnalytics>(`/api/companies/${companyId}/transactions/analytics${query ? `?${query}` : ''}`)
}

export async function downloadTransactionsCsv(
  companyId: number,
  params: {
    period?: string
    fromDate?: string
    toDate?: string
    q?: string
    minAmount?: number
    maxAmount?: number
    direction?: 'in' | 'out'
  } = {}
) {
  const qs = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    qs.set(k, String(v))
  })
  const query = qs.toString()

  const token = getToken()
  let res = await fetch(`${API_URL}/api/companies/${companyId}/transactions/export.csv${query ? `?${query}` : ''}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/transactions/export.csv${query ? `?${query}` : ''}`, {
        headers: { Authorization: `Bearer ${newToken}` }
      })
    }
  }
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.text()
}

export async function uploadTribunalImport(companyId: number, file: File) {
  const token = getToken()
  const form = new FormData()
  form.append('file', file)

  let res = await fetch(`${API_URL}/api/companies/${companyId}/tribunal/imports`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/tribunal/imports`, {
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

export type ImportJob = {
  id: number
  companyId: number
  period: string
  status: 'PENDING' | 'RUNNING' | 'RETRY' | 'OK' | 'WARNING' | 'ERROR' | 'DEAD'
  createdAt: string
  updatedAt?: string | null
  runAfter?: string | null
  processedAt?: string | null
  errorSummary?: string | null
  warningCount?: number | null
  errorCount?: number | null
  attempts?: number | null
  maxAttempts?: number | null
  lastError?: string | null
  storageRef?: string | null
  originalFilename?: string | null
}

export async function retryImport(companyId: number, importId: number) {
  return request<ImportJob>(`/api/companies/${companyId}/imports/${importId}/retry`, { method: 'POST' })
}

export async function uploadTribunalImportWithProgress(
  companyId: number,
  file: File,
  onProgress: (percent: number) => void
) {
  const form = new FormData()
  form.append('file', file)

  return new Promise<any>((resolve, reject) => {
    const send = async (token?: string | null) => {
      const xhr = new XMLHttpRequest()
      xhr.open('POST', `${API_URL}/api/companies/${companyId}/tribunal/imports`)
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
      xhr.upload.onprogress = (evt) => {
        if (!evt.lengthComputable) return
        const pct = Math.round((evt.loaded / evt.total) * 100)
        onProgress(pct)
      }
      xhr.onload = async () => {
        if (xhr.status === 401) {
          const newToken = await refreshAccessToken()
          if (newToken) {
            send(newToken)
            return
          }
        }
        if (xhr.status < 200 || xhr.status >= 300) {
          reject(new Error(xhr.responseText || `HTTP ${xhr.status}`))
          return
        }
        try {
          resolve(JSON.parse(xhr.responseText || '{}'))
        } catch {
          resolve({})
        }
      }
      xhr.onerror = () => reject(new Error('Network error'))
      onProgress(0)
      xhr.send(form)
    }

    send(getToken())
  })
}

export async function downloadTribunalCsv(companyId: number) {
  const token = getToken()
  let res = await fetch(`${API_URL}/api/companies/${companyId}/tribunal/exports.csv`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/tribunal/exports.csv`, {
        headers: { Authorization: `Bearer ${newToken}` }
      })
    }
  }
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.text()
}

export async function getUniversalSummary(companyId: number) {
  return request(`/api/companies/${companyId}/universal/summary`)
}

export type UniversalRows = { filename: string; headers: string[]; rows: string[][] }

export async function getUniversalLatestRows(companyId: number, limit = 50) {
  return request<UniversalRows>(`/api/companies/${companyId}/universal/imports/latest/rows?limit=${limit}`)
}

export async function downloadUniversalNormalizedCsv(companyId: number) {
  const token = getToken()
  let res = await fetch(`${API_URL}/api/companies/${companyId}/universal/imports/latest/normalized.csv`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/universal/imports/latest/normalized.csv`, {
        headers: { Authorization: `Bearer ${newToken}` }
      })
    }
  }
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.text()
}

export type UniversalXlsxPreview = {
  sheets: string[]
  sheetIndex: number | null
  headerRow: number | null
  headers: string[]
  sampleRows: string[][]
}

export type AssistantMessage = { role: 'user' | 'assistant'; content: string }
export type AdvisorEvidence = {
  type: string
  title: string
  subtitle?: string
  detail?: string
  metric?: string
}
export type AdvisorAction = {
  horizon: string
  priority: string
  title: string
  detail: string
  kpi: string
  evidence?: AdvisorEvidence[]
}
export type AssistantChatResponse = {
  reply: string
  questions: string[]
  actions: AdvisorAction[]
  suggestedPrompts: string[]
}

export type AdvisorRecommendationSnapshot = {
  id: number | null
  companyId: number | null
  period: string | null
  source: string
  createdAt: string | null
  summary: string | null
  actions: AdvisorAction[]
}

export async function getLatestRecommendations(companyId: number) {
  return request<AdvisorRecommendationSnapshot>(`/api/companies/${companyId}/recommendations/latest`)
}

export type AutomationJob = {
  id: number
  companyId: number
  type: string
  status: string
  attempts: number
  maxAttempts: number
  runAfter: string
  createdAt: string
  updatedAt: string
  traceId: string
  lastError?: string | null
}

export async function listAutomationJobs(companyId: number) {
  return request<AutomationJob[] | { value: AutomationJob[] }>(`/api/companies/${companyId}/automation/jobs`)
}

export async function runRecomputeKpis(companyId: number, monthsBack = 2) {
  return request(`/api/companies/${companyId}/automation/kpis/recompute?monthsBack=${monthsBack}`, { method: 'POST' })
}

export async function runMonthlyReport(companyId: number, period?: string) {
  const q = period ? `?period=${encodeURIComponent(period)}` : ''
  return request(`/api/companies/${companyId}/automation/reports/monthly${q}`, { method: 'POST' })
}

export async function runSnapshotRecommendations(companyId: number, period?: string) {
  const q = period ? `?period=${encodeURIComponent(period)}` : ''
  return request(`/api/companies/${companyId}/automation/recommendations/snapshot${q}`, { method: 'POST' })
}

export async function assistantChat(companyId: number, messages: AssistantMessage[]) {
  return request<AssistantChatResponse>(`/api/companies/${companyId}/assistant/chat`, {
    method: 'POST',
    body: JSON.stringify({ messages })
  })
}

export async function previewUniversalXlsx(companyId: number, file: File, opts: { sheetIndex?: number; headerRow?: number } = {}) {
  const token = getToken()
  const form = new FormData()
  form.append('file', file)
  if (opts.sheetIndex != null) form.append('sheetIndex', String(opts.sheetIndex))
  if (opts.headerRow != null) form.append('headerRow', String(opts.headerRow))

  let res = await fetch(`${API_URL}/api/companies/${companyId}/universal/xlsx/preview`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/universal/xlsx/preview`, {
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
  return res.json() as Promise<UniversalXlsxPreview>
}

export async function uploadUniversalImport(
  companyId: number,
  file: File,
  opts: { sheetIndex?: number; headerRow?: number } = {}
) {
  const token = getToken()
  const form = new FormData()
  form.append('file', file)
  if (opts.sheetIndex != null) form.append('sheetIndex', String(opts.sheetIndex))
  if (opts.headerRow != null) form.append('headerRow', String(opts.headerRow))

  let res = await fetch(`${API_URL}/api/companies/${companyId}/universal/imports`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/universal/imports`, {
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

export async function generateReport(companyId: number, period: string) {
  return request(`/api/companies/${companyId}/reports`, {
    method: 'POST',
    body: JSON.stringify({ period })
  })
}

export async function getReportContent(companyId: number, reportId: number) {
  return request<string>(`/api/companies/${companyId}/reports/${reportId}/content`)
}

export type ReportDto = {
  id: number
  companyId: number
  period: string
  format: string
  status: string
  createdAt: string
}

export async function generateAdvisorReport(companyId: number) {
  return request<ReportDto>(`/api/companies/${companyId}/assistant/report`, { method: 'POST' })
}
