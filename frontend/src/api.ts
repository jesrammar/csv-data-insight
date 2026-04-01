const API_URL =
  (import.meta.env.VITE_API_URL ?? '').trim() ||
  (import.meta.env.DEV ? 'http://localhost:8081' : '')

export type LoginResponse = {
  accessToken: string
  refreshToken?: string | null
  role: string
  userId: number
  accessTokenExpiresInSeconds: number
}

const USER_ROLE_KEY = 'user_role'
const USER_ID_KEY = 'user_id'
const COMPANY_ID_KEY = 'companyId'
const COMPANY_PLAN_KEY = 'companyPlan'

const AUTH_EVENT = 'auth-change'
let accessToken: string | null = null
let roleCache: string = ''
let userIdCache: number | null = null

function notifyAuthChange() {
  window.dispatchEvent(new Event(AUTH_EVENT))
}

export function getAccessToken() {
  return accessToken
}

export function getUserRole() {
  if (roleCache) return roleCache.toUpperCase()
  try {
    return (sessionStorage.getItem(USER_ROLE_KEY) || '').toUpperCase()
  } catch {
    return ''
  }
}

export function getUserId() {
  if (userIdCache != null) return userIdCache
  try {
    const raw = sessionStorage.getItem(USER_ID_KEY)
    return raw ? Number(raw) : null
  } catch {
    return null
  }
}

export function clearAuthSession() {
  accessToken = null
  roleCache = ''
  userIdCache = null
  try {
    sessionStorage.removeItem(USER_ROLE_KEY)
    sessionStorage.removeItem(USER_ID_KEY)
  } catch {
    // ignore
  }
  // Migration cleanup: remove legacy token storage
  try {
    localStorage.removeItem('token')
    localStorage.removeItem('refresh_token')
    localStorage.removeItem(USER_ROLE_KEY)
    localStorage.removeItem(USER_ID_KEY)
  } catch {
    // ignore
  }
  localStorage.removeItem(COMPANY_ID_KEY)
  localStorage.removeItem(COMPANY_PLAN_KEY)
  notifyAuthChange()
}

function applyLogin(data: LoginResponse) {
  accessToken = data.accessToken
  roleCache = String(data.role || '')
  userIdCache = typeof data.userId === 'number' ? data.userId : null
  try {
    sessionStorage.setItem(USER_ROLE_KEY, roleCache)
    sessionStorage.setItem(USER_ID_KEY, String(userIdCache ?? ''))
  } catch {
    // ignore
  }
  notifyAuthChange()
}

export function onAuthChange(handler: () => void) {
  window.addEventListener(AUTH_EVENT, handler)
  return () => window.removeEventListener(AUTH_EVENT, handler)
}

export async function bootstrapAuth() {
  if (getAccessToken()) return
  await refreshAccessToken()
}

type RequestConfig = { auth?: boolean; retry?: boolean }

let refreshPromise: Promise<string | null> | null = null

async function refreshAccessToken(): Promise<string | null> {
  if (refreshPromise) return refreshPromise

  refreshPromise = (async () => {
    const res = await fetch(`${API_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: '{}'
    })
    if (!res.ok) {
      clearAuthSession()
      return null
    }
    const data = (await res.json()) as LoginResponse
    applyLogin(data)
    return data.accessToken
  })()

  const token = await refreshPromise
  refreshPromise = null
  return token
}

async function request<T = any>(path: string, options: RequestInit = {}, config: RequestConfig = {}): Promise<T> {
  const { auth = true, retry = true } = config
  const token = getAccessToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>)
  }
  if (auth && token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(`${API_URL}${path}`, { ...options, headers, credentials: 'include' })
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
  const data = await request<LoginResponse>(
    '/api/auth/login',
    {
      method: 'POST',
      body: JSON.stringify({ email, password })
    },
    { auth: false, retry: false }
  )
  applyLogin(data)
  return data
}

export async function logout() {
  try {
    await request<void>('/api/auth/logout', { method: 'POST' }, { auth: true, retry: false })
  } finally {
    clearAuthSession()
  }
}

export async function getCompanies() {
  return request('/api/companies/mine')
}

export async function getDashboard(companyId: number, from: string, to: string) {
  return request(`/api/companies/${companyId}/dashboard?from=${from}&to=${to}`)
}

export async function downloadPowerBiExportZip(companyId: number, from: string, to: string) {
  const token = getAccessToken()
  const url = `${API_URL}/api/companies/${companyId}/powerbi/export.zip?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
  let res = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(url, {
        headers: { Authorization: `Bearer ${newToken}` },
        credentials: 'include'
      })
    }
  }
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.blob()
}

export async function getImports(companyId: number) {
  return request<ImportJob[]>(`/api/companies/${companyId}/imports`)
}

export async function uploadImport(companyId: number, period: string, file: File) {
  const token = getAccessToken()
  const form = new FormData()
  form.append('period', period)
  form.append('file', file)

  let res = await fetch(`${API_URL}/api/companies/${companyId}/imports`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/imports`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${newToken}` },
        body: form,
        credentials: 'include'
      })
    }
  }

  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.json()
}

export type ImportPreviewDto = {
  headers: string[]
  sampleRows: string[][]
  suggestedMapping: Record<string, string>
  confidence: number
}

export async function previewImport(companyId: number, file: File, opts: { sheetIndex?: number; headerRow?: number } = {}) {
  const token = getAccessToken()
  const form = new FormData()
  form.append('file', file)
  if (opts.sheetIndex != null) form.append('sheetIndex', String(opts.sheetIndex))
  if (opts.headerRow != null) form.append('headerRow', String(opts.headerRow))

  let res = await fetch(`${API_URL}/api/companies/${companyId}/imports/preview`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/imports/preview`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${newToken}` },
        body: form,
        credentials: 'include'
      })
    }
  }
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.json() as Promise<ImportPreviewDto>
}

export async function uploadImportSmart(
  companyId: number,
  period: string,
  file: File,
  mapping: {
    txnDateCol: string
    amountCol: string
    descriptionCol?: string
    counterpartyCol?: string
    balanceEndCol?: string
    sheetIndex?: number
    headerRow?: number
  }
) {
  const token = getAccessToken()
  const form = new FormData()
  form.append('period', period)
  form.append('file', file)
  form.append('txnDateCol', mapping.txnDateCol)
  form.append('amountCol', mapping.amountCol)
  if (mapping.descriptionCol) form.append('descriptionCol', mapping.descriptionCol)
  if (mapping.counterpartyCol) form.append('counterpartyCol', mapping.counterpartyCol)
  if (mapping.balanceEndCol) form.append('balanceEndCol', mapping.balanceEndCol)
  if (mapping.sheetIndex != null) form.append('sheetIndex', String(mapping.sheetIndex))
  if (mapping.headerRow != null) form.append('headerRow', String(mapping.headerRow))

  let res = await fetch(`${API_URL}/api/companies/${companyId}/imports/smart`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/imports/smart`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${newToken}` },
        body: form,
        credentials: 'include'
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

  const token = getAccessToken()
  let res = await fetch(`${API_URL}/api/companies/${companyId}/transactions/export.csv${query ? `?${query}` : ''}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/transactions/export.csv${query ? `?${query}` : ''}`, {
        headers: { Authorization: `Bearer ${newToken}` },
        credentials: 'include'
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
  const token = getAccessToken()
  const form = new FormData()
  form.append('file', file)

  let res = await fetch(`${API_URL}/api/companies/${companyId}/tribunal/imports`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/tribunal/imports`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${newToken}` },
        body: form,
        credentials: 'include'
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

    send(getAccessToken())
  })
}

export async function downloadTribunalCsv(companyId: number) {
  const token = getAccessToken()
  let res = await fetch(`${API_URL}/api/companies/${companyId}/tribunal/exports.csv`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/tribunal/exports.csv`, {
        headers: { Authorization: `Bearer ${newToken}` },
        credentials: 'include'
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
  const token = getAccessToken()
  let res = await fetch(`${API_URL}/api/companies/${companyId}/universal/imports/latest/normalized.csv`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/universal/imports/latest/normalized.csv`, {
        headers: { Authorization: `Bearer ${newToken}` },
        credentials: 'include'
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

export type MacroMetric = {
  label: string
  value: number | null
  unit: string | null
  source: string | null
}

export type MacroContext = {
  period: string
  updatedAt: string | null
  inflationYoyPct: MacroMetric
  euribor1yPct: MacroMetric
  usdPerEur: MacroMetric
}

export async function getMacroContext(companyId: number, period?: string) {
  const q = period ? `?period=${encodeURIComponent(period)}` : ''
  return request<MacroContext>(`/api/companies/${companyId}/macro/context${q}`)
}

export async function getLatestRecommendations(companyId: number) {
  return request<AdvisorRecommendationSnapshot>(`/api/companies/${companyId}/recommendations/latest`)
}

export async function getLatestRecommendationsByObjective(companyId: number, objective?: string) {
  const q = objective ? `?objective=${encodeURIComponent(objective)}` : ''
  return request<AdvisorRecommendationSnapshot>(`/api/companies/${companyId}/recommendations/latest${q}`)
}

export async function previewRecommendations(companyId: number, opts: { period?: string; objective?: string } = {}) {
  const params = new URLSearchParams()
  if (opts.period) params.set('period', opts.period)
  if (opts.objective) params.set('objective', opts.objective)
  const q = params.toString() ? `?${params.toString()}` : ''
  return request<AdvisorRecommendationSnapshot>(`/api/companies/${companyId}/recommendations/preview${q}`)
}

export async function snapshotRecommendations(companyId: number, period: string, objective?: string) {
  const q = objective ? `?objective=${encodeURIComponent(objective)}` : ''
  return request<AdvisorRecommendationSnapshot>(
    `/api/companies/${companyId}/recommendations/snapshot/${encodeURIComponent(period)}${q}`,
    { method: 'POST' }
  )
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

export async function runSnapshotRecommendations(companyId: number, period?: string, objective?: string) {
  const params = new URLSearchParams()
  if (period) params.set('period', period)
  if (objective) params.set('objective', objective)
  const q = params.toString() ? `?${params.toString()}` : ''
  return request(`/api/companies/${companyId}/automation/recommendations/snapshot${q}`, { method: 'POST' })
}

export async function assistantChat(companyId: number, messages: AssistantMessage[]) {
  return request<AssistantChatResponse>(`/api/companies/${companyId}/assistant/chat`, {
    method: 'POST',
    body: JSON.stringify({ messages })
  })
}

export async function previewUniversalXlsx(companyId: number, file: File, opts: { sheetIndex?: number; headerRow?: number } = {}) {
  const token = getAccessToken()
  const form = new FormData()
  form.append('file', file)
  if (opts.sheetIndex != null) form.append('sheetIndex', String(opts.sheetIndex))
  if (opts.headerRow != null) form.append('headerRow', String(opts.headerRow))

  let res = await fetch(`${API_URL}/api/companies/${companyId}/universal/xlsx/preview`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/universal/xlsx/preview`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${newToken}` },
        body: form,
        credentials: 'include'
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
  const token = getAccessToken()
  const form = new FormData()
  form.append('file', file)
  if (opts.sheetIndex != null) form.append('sheetIndex', String(opts.sheetIndex))
  if (opts.headerRow != null) form.append('headerRow', String(opts.headerRow))

  let res = await fetch(`${API_URL}/api/companies/${companyId}/universal/imports`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(`${API_URL}/api/companies/${companyId}/universal/imports`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${newToken}` },
        body: form,
        credentials: 'include'
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

export async function downloadReportPdf(companyId: number, reportId: number) {
  const token = getAccessToken()
  const url = `${API_URL}/api/companies/${companyId}/reports/${reportId}/content.pdf`
  let res = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    credentials: 'include'
  })
  if (res.status === 401) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      res = await fetch(url, {
        headers: { Authorization: `Bearer ${newToken}` },
        credentials: 'include'
      })
    }
  }
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.blob()
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

export type AuditEventDto = {
  id: number
  at: string
  userId: number | null
  companyId: number | null
  action: string
  method: string | null
  path: string | null
  status: number | null
  durationMs: number | null
  resourceType: string | null
  resourceId: string | null
}

export async function getAuditEvents(companyId: number, limit = 50) {
  return request<AuditEventDto[]>(`/api/companies/${companyId}/audit?limit=${limit}`)
}

export type StorageCleanupResult = {
  startedAt: string
  finishedAt: string
  enabled: boolean
  errors: number
  imports: { refsCleared: number }
  reports: { refsCleared: number }
  universal: { refsCleared: number }
}

export async function runStorageCleanupNow() {
  return request<StorageCleanupResult>(`/api/admin/storage/cleanup/run`, { method: 'POST' })
}

export async function getStorageCleanupLast() {
  return request<StorageCleanupResult | null>(`/api/admin/storage/cleanup/last`)
}
