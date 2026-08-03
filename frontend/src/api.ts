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

export type UserDto = {
  id: number
  email: string
  role: string
  enabled: boolean
  companyIds: number[]
}

export type CreateUserRequest = {
  email: string
  password: string
  role: 'ADMIN' | 'CONSULTOR' | 'CLIENTE'
  companyIds?: number[]
}

export type UpdateUserRequest = {
  role?: 'ADMIN' | 'CONSULTOR' | 'CLIENTE'
  enabled?: boolean
}

export type UserActionLink = {
  path: string
  expiresAt: string
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
  try {
    await refreshAccessToken()
  } catch {
    clearAuthSession()
  }
}

type RequestConfig = { auth?: boolean; retry?: boolean }

let refreshPromise: Promise<string | null> | null = null

const DEFAULT_TIMEOUT_MS = 30_000

function decodeUnicodeEscapes(text: string) {
  return text.replace(/\\u([0-9a-fA-F]{4})/g, (_, hex) =>
    String.fromCharCode(Number.parseInt(hex, 16))
  )
}

function normalizeErrorText(raw: string) {
  const trimmed = String(raw || '').trim()
  if (!trimmed) return ''

  let candidate = trimmed
  try {
    const parsed = JSON.parse(trimmed)
    if (typeof parsed === 'string') {
      candidate = parsed
    } else if (parsed && typeof parsed === 'object') {
      const message =
        (parsed as any).message ||
        (parsed as any).error ||
        (parsed as any).detail ||
        (parsed as any).title
      if (typeof message === 'string' && message.trim()) candidate = message
    }
  } catch {
    // keep raw text
  }

  return decodeUnicodeEscapes(candidate)
}

function backendUnavailableMessage() {
  const api = API_URL || '(sin configurar)'
  if (!import.meta.env.DEV) return `No se pudo conectar con el servidor (${api}).`

  return (
    `No se pudo conectar con el backend (${api}). ` +
    `¿Está levantado? Con Docker: http://localhost:8081. ` +
    `Con Maven suele ser http://localhost:8080 (configura VITE_API_URL).`
  )
}

function normalizeFetchError(err: any, timeoutMs: number) {
  const msg = String(err?.message || err || '')
  if (err?.name === 'AbortError') {
    return new Error(
      `Tiempo de espera agotado (${Math.round(timeoutMs / 1000)}s). Si el fichero es grande, súbelo por periodos o usa Universal.`
    )
  }

  const isNetwork =
    err instanceof TypeError ||
    err?.name === 'TypeError' ||
    msg.toLowerCase().includes('failed to fetch') ||
    msg.toLowerCase().includes('err_connection_refused')

  if (isNetwork) return new Error(backendUnavailableMessage())
  return err
}

function toHeaderRecord(headers?: HeadersInit): Record<string, string> {
  if (!headers) return {}
  if (headers instanceof Headers) {
    const out: Record<string, string> = {}
    headers.forEach((v, k) => (out[k] = v))
    return out
  }
  if (Array.isArray(headers)) {
    const out: Record<string, string> = {}
    for (const [k, v] of headers) out[k] = v
    return out
  }
  return { ...(headers as Record<string, string>) }
}

async function fetchWithAuth(
  url: string,
  init: RequestInit = {},
  config: { auth?: boolean; retry?: boolean; timeoutMs?: number } = {}
) {
  const { auth = true, retry = true, timeoutMs = DEFAULT_TIMEOUT_MS } = config
  const controller = timeoutMs > 0 ? new AbortController() : null
  const baseSignal = init.signal
  const timer = controller ? window.setTimeout(() => controller.abort(), timeoutMs) : null

  const doFetch = async (tokenOverride?: string | null) => {
    const headers = toHeaderRecord(init.headers)
    if (auth) {
      const token = tokenOverride ?? getAccessToken()
      if (token && !headers.Authorization) headers.Authorization = `Bearer ${token}`
    }
    return fetch(url, {
      ...init,
      headers,
      credentials: init.credentials ?? 'include',
      signal: controller?.signal ?? baseSignal
    })
  }

  try {
    let res = await doFetch(null)
    if (res.status === 401 && retry && url.indexOf('/api/auth/login') === -1 && url.indexOf('/api/auth/refresh') === -1) {
      const newToken = await refreshAccessToken()
      if (newToken) res = await doFetch(newToken)
    }
    return res
  } catch (e: any) {
    throw normalizeFetchError(e, timeoutMs)
  } finally {
    if (timer != null) window.clearTimeout(timer)
  }
}

async function refreshAccessToken(): Promise<string | null> {
  if (refreshPromise) return refreshPromise

  refreshPromise = (async () => {
    try {
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
    } catch {
      return null
    }
  })()

  const token = await refreshPromise
  refreshPromise = null
  return token
}

async function request<T = any>(path: string, options: RequestInit = {}, config: RequestConfig & { timeoutMs?: number } = {}): Promise<T> {
  const { auth = true, retry = true, timeoutMs = DEFAULT_TIMEOUT_MS } = config
  const token = getAccessToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>)
  }
  if (auth && token) headers.Authorization = `Bearer ${token}`

  const res = await fetchWithAuth(`${API_URL}${path}`, { ...options, headers }, { auth, retry, timeoutMs })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(normalizeErrorText(text) || `HTTP ${res.status}`)
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

export type PortfolioMonthFlow = {
  period: string
  importStatus: string | null
  hasReading: boolean
  hasReport: boolean
  portfolioStep?: PortfolioWorkflowStepDto | null
}

export type PortfolioCompanyOperation = {
  companyId: number
  companyName: string
  companyPlan: 'BRONZE' | 'GOLD' | 'PLATINUM' | string
  items: PortfolioMonthFlow[]
  blockedCount: number
  warningCount: number
  readyCount: number
  pendingPdfCount: number
  missingDataCount: number
  priorityScore: number
  statusLabel: string
}

export type PortfolioOperationsSummary = {
  activeCompanies: number
  blockedCompanies: number
  readyCompanies: number
  topPriorityCompanyId: number | null
  topPriorityCompanyName: string | null
  topPriorityBlockedCount: number | null
  topPriorityPendingPdfCount: number | null
}

export type PortfolioOperationsDto = {
  months: string[]
  summary: PortfolioOperationsSummary
  companies: PortfolioCompanyOperation[]
}

export type PeriodWorkflowStatus =
  | 'PENDING_DATA'
  | 'INGESTING'
  | 'EXCEPTIONS'
  | 'READY_FOR_REVIEW'
  | 'REVIEWED'
  | 'REPORT_GENERATING'
  | 'REPORT_READY'
  | 'CLOSED'
  | string

export type PortfolioWorkflowStepStatus = 'NOT_APPLICABLE' | 'PENDING' | 'LOADED' | 'STALE' | string

export type PortfolioWorkflowStepDto = {
  applicable: boolean
  status: PortfolioWorkflowStepStatus
  title: string
  detail: string
  updatedAt?: string | null
  badgeTone?: 'ok' | 'warn' | 'err' | '' | string
  actionLabel?: string | null
  shortLabel?: string | null
}

export type PeriodWorkflowDto = {
  id: number
  companyId: number
  period: string
  status: PeriodWorkflowStatus
  statusTitle?: string | null
  statusDetail?: string | null
  statusBadgeTone?: 'ok' | 'warn' | 'err' | '' | string
  primaryActionLabel?: string | null
  orchestrationStatus?: 'BLOCKED' | 'RUNNING' | 'AVAILABLE' | 'WAITING_PORTFOLIO' | 'AUTO_CLOSE_READY' | 'DONE' | string
  orchestrationRunnable?: boolean
  autoCloseReady?: boolean
  orchestrationTitle?: string | null
  orchestrationDetail?: string | null
  orchestrationActionLabel?: string | null
  priority: number
  blockingCode?: string | null
  blockingReason?: string | null
  exceptionCount: number
  sourceImportId?: number | null
  sourceImportStatus?: string | null
  reportId?: number | null
  reportStatus?: string | null
  recommendationSnapshotId?: number | null
  recommendationSummary?: string | null
  recommendationCreatedAt?: string | null
  portfolioStep?: PortfolioWorkflowStepDto | null
  ownerUserId?: number | null
  startedAt: string
  updatedAt?: string | null
  reviewedAt?: string | null
  closedAt?: string | null
  notes?: string | null
}

export async function getPortfolioOperations(months = 4) {
  return request<PortfolioOperationsDto>(`/api/companies/mine/operations?months=${encodeURIComponent(String(months))}`)
}

export async function getPeriodWorkflows(companyId: number) {
  return request<PeriodWorkflowDto[]>(`/api/companies/${companyId}/period-workflows`)
}

export async function getPeriodWorkflow(companyId: number, period: string) {
  return request<PeriodWorkflowDto>(`/api/companies/${companyId}/period-workflows/${encodeURIComponent(period)}`)
}

export async function reviewPeriodWorkflow(companyId: number, period: string) {
  return request<PeriodWorkflowDto>(`/api/companies/${companyId}/period-workflows/${encodeURIComponent(period)}/review`, { method: 'POST' })
}

export async function closePeriodWorkflow(companyId: number, period: string) {
  return request<PeriodWorkflowDto>(`/api/companies/${companyId}/period-workflows/${encodeURIComponent(period)}/close`, { method: 'POST' })
}

export async function getUsers() {
  return request<UserDto[]>('/api/users')
}

export async function createUser(payload: CreateUserRequest) {
  return request<UserDto>('/api/users', { method: 'POST', body: JSON.stringify(payload) })
}

export async function updateUserCompanies(userId: number, companyIds: number[]) {
  return request<UserDto>(`/api/users/${userId}/companies`, { method: 'PUT', body: JSON.stringify(companyIds) })
}

export async function updateUser(userId: number, payload: UpdateUserRequest) {
  return request<UserDto>(`/api/users/${userId}`, { method: 'PATCH', body: JSON.stringify(payload || {}) })
}

export async function createInviteLink(userId: number) {
  return request<UserActionLink>(`/api/users/${userId}/invite-link`, { method: 'POST', body: '{}' })
}

export async function createPasswordResetLink(userId: number) {
  return request<UserActionLink>(`/api/users/${userId}/password-reset-link`, { method: 'POST', body: '{}' })
}

export async function confirmPasswordFromToken(token: string, newPassword: string, action: 'invite' | 'reset') {
  await request<void>(
    `/api/auth/password/confirm`,
    { method: 'POST', body: JSON.stringify({ token, newPassword, action }) },
    { auth: false, retry: false }
  )
}

export async function changePassword(currentPassword: string, newPassword: string) {
  await request<void>(`/api/auth/password/change`, { method: 'POST', body: JSON.stringify({ currentPassword, newPassword }) })
}

export async function getDashboard(companyId: number, from: string, to: string) {
  return request(`/api/companies/${companyId}/dashboard?from=${from}&to=${to}`)
}

export async function downloadPowerBiExportZip(companyId: number, from: string, to: string) {
  const url = `${API_URL}/api/companies/${companyId}/powerbi/export.zip?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
  const res = await fetchWithAuth(url, {}, { auth: true, retry: true, timeoutMs: 120_000 })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.blob()
}

export async function getImports(companyId: number) {
  return request<ImportJob[]>(`/api/companies/${companyId}/imports`)
}

export type PipelineFileStatus = 'PENDING' | 'PROCESSING' | 'DONE' | 'ERROR' | 'SKIPPED' | string
export type PipelineFileKind = 'TRANSACTIONS' | 'TRIBUNAL' | 'UNIVERSAL' | 'UNKNOWN' | string

export type PipelineFileDto = {
  id: number
  companyId: number
  sourceType: string
  detectedKind: PipelineFileKind
  filename: string
  inboxPath: string
  archivedPath?: string | null
  period?: string | null
  status: PipelineFileStatus
  statusTitle?: string | null
  statusDetail?: string | null
  badgeTone?: 'ok' | 'warn' | 'err' | '' | string
  actionLabel?: string | null
  message?: string | null
  importJobId?: number | null
  detectedAt: string
  processedAt?: string | null
  updatedAt: string
}

export type PipelineSummaryDto = {
  generatedAt: string
  totalFiles: number
  doneFiles: number
  errorFiles: number
  pendingFiles: number
  processingFiles: number
  skippedFiles: number
  lastDetectedAt?: string | null
  headline?: string | null
  detail?: string | null
  badgeTone?: 'ok' | 'warn' | 'err' | '' | string
  actionLabel?: string | null
}

export type PipelineScanResultDto = {
  scannedAt: string
  discovered: number
  imported: number
  failed: number
  skipped: number
}

export async function getPipelineFiles(companyId: number) {
  return request<PipelineFileDto[]>(`/api/companies/${companyId}/pipeline/files`)
}

export async function getPipelineSummary(companyId: number) {
  return request<PipelineSummaryDto>(`/api/companies/${companyId}/pipeline/summary`)
}

export async function scanPipeline(companyId: number) {
  return request<PipelineScanResultDto>(`/api/companies/${companyId}/pipeline/scan`, { method: 'POST' })
}

export async function retryPipelineFile(companyId: number, fileId: number) {
  return request<PipelineFileDto>(`/api/companies/${companyId}/pipeline/files/${fileId}/retry`, { method: 'POST' })
}

export async function updatePipelineFilePeriod(companyId: number, fileId: number, period: string) {
  return request<PipelineFileDto>(`/api/companies/${companyId}/pipeline/files/${fileId}/period`, {
    method: 'POST',
    body: JSON.stringify({ period })
  })
}

export async function updatePipelineFileKind(companyId: number, fileId: number, kind: string) {
  return request<PipelineFileDto>(`/api/companies/${companyId}/pipeline/files/${fileId}/kind`, {
    method: 'POST',
    body: JSON.stringify({ kind })
  })
}

export async function uploadImport(companyId: number, period: string, file: File) {
  const form = new FormData()
  form.append('period', period)
  form.append('file', file)

  const res = await fetchWithAuth(
    `${API_URL}/api/companies/${companyId}/imports`,
    { method: 'POST', body: form },
    { auth: true, retry: true, timeoutMs: 120_000 }
  )

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
  const form = new FormData()
  form.append('file', file)
  if (opts.sheetIndex != null) form.append('sheetIndex', String(opts.sheetIndex))
  if (opts.headerRow != null) form.append('headerRow', String(opts.headerRow))

  const res = await fetchWithAuth(
    `${API_URL}/api/companies/${companyId}/imports/preview`,
    { method: 'POST', body: form },
    { auth: true, retry: true, timeoutMs: 45_000 }
  )
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

  const res = await fetchWithAuth(
    `${API_URL}/api/companies/${companyId}/imports/smart`,
    { method: 'POST', body: form },
    { auth: true, retry: true, timeoutMs: 120_000 }
  )

  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.json()
}

export async function getReports(companyId: number) {
  return request(`/api/companies/${companyId}/reports`)
}

export type CompanySettingsDto = {
  companyId: number
  workingPeriod?: string | null
  autoMonthlyReport?: boolean | null
  reportConsultancyName?: string | null
  reportLogoUrl?: string | null
  reportPrimaryColor?: string | null
  reportFooterText?: string | null
}

export async function getCompanySettings(companyId: number) {
  return request<CompanySettingsDto>(`/api/companies/${companyId}/settings`)
}

export async function updateCompanySettings(companyId: number, patch: Partial<CompanySettingsDto>) {
  return request<CompanySettingsDto>(`/api/companies/${companyId}/settings`, { method: 'PUT', body: JSON.stringify(patch) })
}

export async function getCompanyMapping(companyId: number, key: string) {
  return request<any>(`/api/companies/${companyId}/mappings/${encodeURIComponent(key)}`)
}

export async function saveCompanyMapping(companyId: number, key: string, payload: any) {
  return request<void>(`/api/companies/${companyId}/mappings/${encodeURIComponent(key)}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export type ChecklistItemDto = {
  id: string
  label: string
  done: boolean
  hint?: string
  actionHref?: string
}

export type ChecklistDto = {
  companyId: number
  period: string
  items: ChecklistItemDto[]
}

export async function getChecklist(companyId: number, period?: string) {
  const q = period ? `?period=${encodeURIComponent(period)}` : ''
  return request<ChecklistDto>(`/api/companies/${companyId}/checklist${q}`)
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

export type TribunalImportDto = {
  id: number
  companyId: number
  filename: string
  createdAt: string
  rowCount: number
  warningCount: number
  errorCount: number
  errorSummary?: string | null
}

export async function getTribunalStatus(companyId: number) {
  return request<TribunalImportDto | null>(`/api/companies/${companyId}/tribunal/status`)
}

export type BudgetMonth = {
  monthKey: string
  label: string
  income: number
  expense: number
  margin: number
  deltaMargin?: number | null
  deltaMarginPct?: number | null
}

export type BudgetSummary = {
  sourceFilename?: string | null
  sourceCreatedAt?: string | null
  sourceImportId?: number | null
  analysisVersion?: string | null
  months: BudgetMonth[]
  totalIncome: number
  totalExpense: number
  totalMargin: number
  totalCapex?: number | null
  totalDepreciation?: number | null
  totalEbit?: number | null
  financialResult?: number | null
  netResult?: number | null
  bestMonth?: string | null
  worstMonth?: string | null
}

export async function getBudgetSummary(companyId: number) {
  return request<BudgetSummary>(`/api/companies/${companyId}/budget/summary`)
}

export type CashflowMonth = {
  monthKey: string
  label: string
  inflow: number
  outflow: number
  net: number
  declaredNet?: number | null
  derivedNet?: number | null
  reconciliationStatus?: string | null
  reconciliationWarning?: string | null
  endingBalance: number
  deltaNet?: number | null
  deltaNetPct?: number | null
}

export type CashflowSummary = {
  sourceFilename?: string | null
  sourceCreatedAt?: string | null
  sourceImportId?: number | null
  analysisVersion?: string | null
  openingBalance: number
  months: CashflowMonth[]
  totalInflow: number
  totalOutflow: number
  totalNet: number
  endingBalance: number
  bestMonth?: string | null
  worstMonth?: string | null
}

export async function getCashflowSummary(companyId: number) {
  return request<CashflowSummary>(`/api/companies/${companyId}/budget/cashflow`)
}

export type BudgetComparisonMonth = {
  monthKey: string
  monthLabel: string
  actualPeriod?: string | null
  hasActual: boolean
  actualStatus?: string | null
  plannedInflow?: number | null
  actualInflow?: number | null
  inflowVariance?: number | null
  plannedOutflow?: number | null
  actualOutflow?: number | null
  outflowVariance?: number | null
  plannedNet?: number | null
  actualNet?: number | null
  netVariance?: number | null
  plannedEndingBalance?: number | null
  actualEndingBalance?: number | null
  endingBalanceVariance?: number | null
}

export type BudgetComparisonSummary = {
  comparisonYear?: number | null
  plannedMonths?: number | null
  actualMonths?: number | null
  commonMonths?: number | null
  actualDataStatus?: string | null
  assumption?: string | null
  latestComparedPeriod?: string | null
  plannedInflowYtd?: number | null
  actualInflowYtd?: number | null
  inflowVarianceYtd?: number | null
  plannedOutflowYtd?: number | null
  actualOutflowYtd?: number | null
  outflowVarianceYtd?: number | null
  plannedNetYtd?: number | null
  actualNetYtd?: number | null
  netVarianceYtd?: number | null
  plannedEndingBalanceLatest?: number | null
  actualEndingBalanceLatest?: number | null
  endingBalanceVarianceLatest?: number | null
  strongestPositiveMonth?: string | null
  strongestPositiveVariance?: number | null
  strongestNegativeMonth?: string | null
  strongestNegativeVariance?: number | null
}

export type BudgetWorkflowDto = {
  companyId: number
  status: string
  statusTitle: string
  statusDetail: string
  statusBadgeTone?: 'ok' | 'warn' | 'err' | '' | string
  nextActionLabel?: string | null
  sourceFilename?: string | null
  sourceCreatedAt?: string | null
  sourceSheetIndex?: number | null
  sourceHeaderRow?: number | null
  sourceAttemptTrend?: string | null
  sourceAttemptTrendTitle?: string | null
  sourceAttemptTrendDetail?: string | null
  sourcePresent: boolean
  structureValidated: boolean
  annualInsightsReady: boolean
  plannedCashflowReady: boolean
  comparisonReady: boolean
  plannedMonthsAvailable?: number | null
  actualMonthsAvailable?: number | null
  comparisonSummary?: BudgetComparisonSummary | null
  comparisonMonths: BudgetComparisonMonth[]
}

export async function getBudgetWorkflow(companyId: number) {
  return request<BudgetWorkflowDto>(`/api/companies/${companyId}/budget/workflow`)
}

export type BudgetAnalysisBundle = {
  companyId: number
  planImportId?: number | null
  actualImportId?: number | null
  sourceImportId?: number | null
  analysisVersion?: string | null
  generatedAt?: string | null
  comparisonStatus?: string | null
  sourceFilename?: string | null
  sourceType?: string | null
  sourceCreatedAt?: string | null
  sourceSheetIndex?: number | null
  sourceSheetName?: string | null
  sourceHeaderRow?: number | null
  sourceHeaderLabel?: string | null
  workflow: BudgetWorkflowDto
  summary?: BudgetSummary | null
  cashflow?: CashflowSummary | null
  insights?: BudgetLongInsights | null
}

export async function getBudgetAnalysis(companyId: number) {
  return request<BudgetAnalysisBundle>(`/api/companies/${companyId}/budget/analysis`, {}, { timeoutMs: 120_000 })
}

export type BudgetItemInsight = {
  code?: string | null
  label?: string | null
  normalizedLabel?: string | null
  semanticKind?: string | null
  financialNature?: string | null
  cashflowNature?: string | null
  annualTotal: number
  zeroMonths: number
  shareAbsPct: number
  zeroInterpretation?: string | null
  rowType?: string | null
  sectionKind?: string | null
  mappingStatus?: string | null
  sourceRow?: number | null
  blockId?: string | null
  exclusionReason?: string | null
  canonicalIdentity?: string | null
  canonicalRowId?: string | null
}

export type BudgetMonthTotal = {
  monthKey: string
  monthLabel: string
  total: number
}

export type BudgetLongInsights = {
  filename?: string | null
  createdAt?: string | null
  sourceImportId?: number | null
  analysisVersion?: string | null
  itemCount: number
  totalAbsAnnual: number
  bestMonth?: string | null
  worstMonth?: string | null
  concentrationTop3AbsPct: number
  monthTotals: BudgetMonthTotal[]
  topDrivers: BudgetItemInsight[]
  zeroHeavyItems: BudgetItemInsight[]
  accountingAdjustments: BudgetItemInsight[]
}

export async function getBudgetLongInsights(companyId: number) {
  return request<BudgetLongInsights>(`/api/companies/${companyId}/budget/long/insights`)
}

export type BudgetItemDetailMonth = {
  monthKey: string
  monthLabel: string
  amount: number
}

export type BudgetItemDetail = {
  sourceImportId?: number | null
  analysisVersion?: string | null
  lookupStrategy?: string | null
  canonicalRowId?: string | null
  canonicalIdentity?: string | null
  code?: string | null
  label?: string | null
  normalizedLabel?: string | null
  rowType?: string | null
  semanticKind?: string | null
  financialNature?: string | null
  cashflowNature?: string | null
  sectionKind?: string | null
  mappingStatus?: string | null
  blockId?: string | null
  aggregationPolicy?: string | null
  declaredAnnualTotal?: number | null
  computedAnnualTotal?: number | null
  sourceRowCount: number
  sourceRows: number[]
  reconciliationWarnings: string[]
  months: BudgetItemDetailMonth[]
}

export async function getBudgetItemDetail(companyId: number, canonicalRowId: string) {
  const params = new URLSearchParams({ canonicalRowId })
  return request<BudgetItemDetail>(`/api/companies/${companyId}/budget/long/detail?${params.toString()}`)
}

export type BudgetLongRow = {
  rowType: 'DETAIL' | 'SUBTOTAL' | 'TOTAL' | 'DERIVED_KPI' | 'ASSUMPTION' | 'TEXT'
  code?: string | null
  label?: string | null
  semanticKind?: string | null
  sectionKind?: string | null
  monthKey: string
  monthLabel: string
  amount: number
  confidence?: string | null
  mappingStatus?: string | null
}

export type BudgetLongPreview = {
  filename?: string | null
  createdAt?: string | null
  monthKeys: string[]
  labelHeader: string
  totalRowsProduced: number
  sampleRows: BudgetLongRow[]
  requiresConfirmation?: boolean
  mappingNotes?: string[]
}

export async function getBudgetLongPreview(companyId: number) {
  return request<BudgetLongPreview>(`/api/companies/${companyId}/budget/long/preview`)
}

export async function downloadBudgetLongCsv(companyId: number) {
  const url = `${API_URL}/api/companies/${companyId}/budget/long.csv`
  const res = await fetchWithAuth(url, {}, { auth: true, retry: true, timeoutMs: 120_000 })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.blob()
}

export async function downloadBudgetReportPdf(companyId: number) {
  const url = `${API_URL}/api/companies/${companyId}/budget/report.pdf`
  const res = await fetchWithAuth(url, {}, { auth: true, retry: true, timeoutMs: 120_000 })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.blob()
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

  const res = await fetchWithAuth(
    `${API_URL}/api/companies/${companyId}/transactions/export.csv${query ? `?${query}` : ''}`,
    {},
    { auth: true, retry: true, timeoutMs: 120_000 }
  )
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.text()
}

export async function uploadTribunalImport(companyId: number, file: File) {
  const form = new FormData()
  form.append('file', file)

  const res = await fetchWithAuth(
    `${API_URL}/api/companies/${companyId}/tribunal/imports`,
    { method: 'POST', body: form },
    { auth: true, retry: true, timeoutMs: 120_000 }
  )
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
  status: 'PENDING' | 'RUNNING' | 'RETRY' | 'BLOCKED' | 'OK' | 'WARNING' | 'ERROR' | 'DEAD'
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
  versionNo?: number | null
  contentHash?: string | null
  normalizedHash?: string | null
  supersedesImportId?: number | null
  duplicateOfImportId?: number | null
  blockingCode?: string | null
  blockingReason?: string | null
  rowsReceived?: number | null
  rowsValid?: number | null
  appliedAt?: string | null
}

export type ImportQualityIssue = { severity: 'HIGH' | 'MEDIUM' | 'LOW' | string; code: string; title: string; detail: string }
export type ImportQualityDto = {
  period: string
  rowsNonEmpty: number
  rowsEmpty: number
  rowsParsed: number
  missingTxnDate: number
  missingAmount: number
  dateParseErrors: number
  amountParseErrors: number
  minDate?: string | null
  maxDate?: string | null
  outsidePeriodRows: number
  duplicateRows: number
  missingCounterpartyRows: number
  balanceEndMismatchRows: number
  issues: ImportQualityIssue[]
  examples: string[]
}

export type IngestionStatus = {
  now: string
  lastImport?: ImportJob | null
  lastProcessedImport?: ImportJob | null
  nextScheduledImport?: ImportJob | null
}

export async function getIngestionStatus(companyId: number) {
  return request<IngestionStatus>(`/api/companies/${companyId}/ingestion/status`)
}

export async function retryImport(companyId: number, importId: number) {
  return request<ImportJob>(`/api/companies/${companyId}/imports/${importId}/retry`, { method: 'POST' })
}

export async function getImportQuality(companyId: number, importId: number) {
  return request<ImportQualityDto>(`/api/companies/${companyId}/imports/${importId}/quality`)
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
  const res = await fetchWithAuth(`${API_URL}/api/companies/${companyId}/tribunal/exports.csv`, {}, { auth: true, retry: true, timeoutMs: 120_000 })
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
  const res = await fetchWithAuth(
    `${API_URL}/api/companies/${companyId}/universal/imports/latest/normalized.csv`,
    {},
    { auth: true, retry: true, timeoutMs: 120_000 }
  )
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.text()
}

export type UniversalImportDto = {
  id: number
  filename: string
  createdAt: string
  rowCount: number
  columnCount: number
}

export type UniversalIntakeCandidate = {
  kind: string
  label: string
  confidence?: number | null
  confidenceLabel?: string | null
  recommendedRoute?: string | null
  primaryActionLabel?: string | null
  reasons?: string[]
}

export type UniversalIntakeDiagnosis = {
  kind: string
  label: string
  confidence?: number | null
  confidenceLabel?: string | null
  needsConfirmation?: boolean
  structureRecognized?: boolean
  headline?: string | null
  detail?: string | null
  recommendedModule?: string | null
  recommendedRoute?: string | null
  primaryActionLabel?: string | null
  canonicalStatus?: string | null
  canonicalDetail?: string | null
  reasons?: string[]
  warnings?: string[]
  candidates?: UniversalIntakeCandidate[]
}

export type UniversalSummaryDto = {
  importId?: number | null
  filename?: string | null
  createdAt?: string | null
  rowCount?: number | null
  columnCount?: number | null
  columns?: any[]
  correlations?: any[]
  rowGranularity?: string | null
  detectedEntities?: any[]
  relationships?: any[]
  semanticWarnings?: string[]
  insights?: any[]
  intakeDiagnosis?: UniversalIntakeDiagnosis | null
}

export async function listUniversalImports(companyId: number) {
  return request<UniversalImportDto[]>(`/api/companies/${companyId}/universal/imports`)
}

export async function getUniversalSummaryForImport(companyId: number, importId?: number | null) {
  const qs = importId ? `?importId=${encodeURIComponent(String(importId))}` : ''
  return request<UniversalSummaryDto | null>(`/api/companies/${companyId}/universal/summary${qs}`)
}

export async function getUniversalSuggestionsForImport(companyId: number, importId?: number | null) {
  const qs = importId ? `?importId=${encodeURIComponent(String(importId))}` : ''
  return request<UniversalAutoSuggestion[]>(`/api/companies/${companyId}/universal/suggestions${qs}`)
}

export async function getUniversalRows(companyId: number, limit = 50, importId?: number | null) {
  if (importId) {
    return request<UniversalRows>(`/api/companies/${companyId}/universal/imports/${importId}/rows?limit=${limit}`)
  }
  return request<UniversalRows>(`/api/companies/${companyId}/universal/imports/latest/rows?limit=${limit}`)
}

export async function downloadUniversalNormalizedCsvForImport(companyId: number, importId?: number | null) {
  const url = importId
    ? `${API_URL}/api/companies/${companyId}/universal/imports/${importId}/normalized.csv`
    : `${API_URL}/api/companies/${companyId}/universal/imports/latest/normalized.csv`
  const res = await fetchWithAuth(url, {}, { auth: true, retry: true, timeoutMs: 120_000 })
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
  engine?: 'RULES' | 'AI' | string
  disclosure?: string
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

export type AdvisorActionFollowUpStatus = 'PENDING' | 'IN_PROGRESS' | 'RESOLVED' | string

export type AdvisorActionFollowUp = {
  id: number
  companyId: number | null
  recommendationSnapshotId: number | null
  period: string
  source: string
  actionIndex: number | null
  actionKey: string
  horizon: string | null
  priority: string | null
  title: string
  detail: string | null
  kpi: string | null
  status: AdvisorActionFollowUpStatus
  carriedOver: boolean
  originFollowUpId: number | null
  originPeriod: string | null
  createdAt: string | null
  updatedAt: string | null
  resolvedAt: string | null
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

export async function getRecommendationSnapshotByPeriod(companyId: number, period: string, objective?: string) {
  const q = objective ? `?objective=${encodeURIComponent(objective)}` : ''
  return request<AdvisorRecommendationSnapshot>(`/api/companies/${companyId}/recommendations/period/${encodeURIComponent(period)}${q}`)
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

export async function getRecommendationFollowUps(companyId: number, period: string, objective?: string) {
  const q = objective ? `?objective=${encodeURIComponent(objective)}` : ''
  return request<AdvisorActionFollowUp[]>(`/api/companies/${companyId}/recommendations/period/${encodeURIComponent(period)}/follow-ups${q}`)
}

export async function updateRecommendationFollowUpStatus(companyId: number, followUpId: number, status: AdvisorActionFollowUpStatus) {
  return request<AdvisorActionFollowUp>(`/api/companies/${companyId}/recommendations/follow-ups/${encodeURIComponent(String(followUpId))}/status`, {
    method: 'POST',
    body: JSON.stringify({ status })
  })
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

export async function runPeriodCloseFlow(companyId: number, period?: string) {
  const resolved = (period || '').trim() || new Date().toISOString().slice(0, 7)
  return request(`/api/companies/${companyId}/automation/periods/${encodeURIComponent(resolved)}/close-flow`, { method: 'POST' })
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
  const form = new FormData()
  form.append('file', file)
  if (opts.sheetIndex != null) form.append('sheetIndex', String(opts.sheetIndex))
  if (opts.headerRow != null) form.append('headerRow', String(opts.headerRow))

  const res = await fetchWithAuth(
    `${API_URL}/api/companies/${companyId}/universal/xlsx/preview`,
    { method: 'POST', body: form },
    { auth: true, retry: true, timeoutMs: 60_000 }
  )
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
  const form = new FormData()
  form.append('file', file)
  if (opts.sheetIndex != null) form.append('sheetIndex', String(opts.sheetIndex))
  if (opts.headerRow != null) form.append('headerRow', String(opts.headerRow))

  const res = await fetchWithAuth(
    `${API_URL}/api/companies/${companyId}/universal/imports`,
    { method: 'POST', body: form },
    { auth: true, retry: true, timeoutMs: 120_000 }
  )
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.json() as Promise<UniversalSummaryDto>
}

export type UniversalAutoSuggestion = {
  title: string
  description: string
  request: UniversalViewRequest
}

export async function getUniversalSuggestions(companyId: number) {
  return request<UniversalAutoSuggestion[]>(`/api/companies/${companyId}/universal/suggestions`)
}

export async function downloadUniversalBuilderProblemsCsv(companyId: number, body: UniversalViewRequest, limit = 100) {
  const url = `${API_URL}/api/companies/${companyId}/universal/builder/problems.csv?limit=${encodeURIComponent(String(limit))}`
  const res = await fetchWithAuth(
    url,
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) },
    { auth: true, retry: true, timeoutMs: 120_000 }
  )
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.blob()
}

export async function downloadUniversalBuilderProblemsCsvForImport(companyId: number, body: UniversalViewRequest, limit = 100, importId?: number | null) {
  const qs = importId ? `&importId=${encodeURIComponent(String(importId))}` : ''
  const url = `${API_URL}/api/companies/${companyId}/universal/builder/problems.csv?limit=${encodeURIComponent(String(limit))}${qs}`
  const res = await fetchWithAuth(
    url,
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) },
    { auth: true, retry: true, timeoutMs: 120_000 }
  )
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  return res.blob()
}

export type UniversalViewType =
  | 'TIME_SERIES'
  | 'CATEGORY_BAR'
  | 'KPI_CARDS'
  | 'SCATTER'
  | 'HEATMAP'
  | 'PIVOT_MONTHLY'

export type UniversalViewDto = {
  id: number
  companyId: number
  name: string
  type: UniversalViewType | string
  createdAt: string
  sourceImportId?: number | null
  sourceFilename?: string | null
  sourceImportedAt?: string | null
  aggregationMode?:
    | 'ROW_COUNT'
    | 'DISTINCT_ENTRY_COUNT'
    | 'DISTINCT_DOCUMENT_COUNT'
    | 'DISTINCT_INVOICE_COUNT'
    | 'DISTINCT_PARTY_COUNT'
    | 'SUM_DEBIT'
    | 'SUM_CREDIT'
    | 'NET_BALANCE'
    | 'SUM_AMOUNT'
    | 'AVG_VALUE'
    | string
    | null
}

export type UniversalViewRequest = {
  name?: string
  type: UniversalViewType
  dateColumn?: string | null
  valueColumn?: string | null
  categoryColumn?: string | null
  xColumn?: string | null
  yColumn?: string | null
  aggregationMode?:
    | 'ROW_COUNT'
    | 'DISTINCT_ENTRY_COUNT'
    | 'DISTINCT_DOCUMENT_COUNT'
    | 'DISTINCT_INVOICE_COUNT'
    | 'DISTINCT_PARTY_COUNT'
    | 'SUM_DEBIT'
    | 'SUM_CREDIT'
    | 'NET_BALANCE'
    | 'SUM_AMOUNT'
    | 'AVG_VALUE'
  aggregation?: 'sum' | 'avg'
  filterColumn?: string | null
  filterValue?: string | null
  filters?: Array<{ column: string; op: 'eq' | 'contains' | 'year_eq' | 'gt' | 'gte' | 'lt' | 'lte'; value: string }>
  topN?: number | null
  maxPoints?: number | null
}

export type UniversalChartSeries = { name: string; data: any[] }
export type UniversalChartData = {
  type: UniversalViewType | string
  labels: string[]
  series: UniversalChartSeries[]
  meta?: Record<string, any>
}

export type UniversalEvidenceDto = {
  filename?: string | null
  headers: string[]
  rows: string[][]
  rowNumbers: number[]
  meta?: Record<string, any>
}

export type UniversalImportQualityDto = {
  importId: number
  filename: string
  rowsScanned: number
  columns: number
  irregularRows: number
  nullCells: number
  totalCells: number
  dateParseErrors: number
  numberParseErrors: number
  minDate?: string | null
  maxDate?: string | null
  score: number
  level: 'GREEN' | 'YELLOW' | 'RED' | string
  issues?: Array<{ severity: string; code: string; title: string; detail: string }>
  examples?: string[]
}

export type UniversalXlsxOptionsDto = { sheetIndex?: number | null; headerRow1Based?: number | null }

export type UniversalImportAnalysisDto = {
  bytes?: number | null
  durationMs?: number | null
  charsetName?: string | null
  delimiter?: string | null
  sampled?: boolean | null
  totalRowsRead?: number | null
  goodRows?: number | null
  badRows?: number | null
  observedRows?: number | null
  removedEmptyColumns?: number | null
  convertedFromXlsx?: boolean | null
  xlsx?: UniversalXlsxOptionsDto | null
  intakeDiagnosis?: UniversalIntakeDiagnosis | null
}

export type UniversalImportLineageDto = {
  importId: number
  filename: string
  createdAt: string
  rowCount?: number | null
  columnCount?: number | null
  analysis?: UniversalImportAnalysisDto | null
}

export async function previewUniversalView(companyId: number, body: UniversalViewRequest) {
  return request<UniversalChartData>(`/api/companies/${companyId}/universal/builder/preview`, {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export async function previewUniversalViewForImport(companyId: number, body: UniversalViewRequest, importId?: number | null) {
  const qs = importId ? `?importId=${encodeURIComponent(String(importId))}` : ''
  return request<UniversalChartData>(`/api/companies/${companyId}/universal/builder/preview${qs}`, {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export async function getUniversalEvidenceForImport(
  companyId: number,
  body: UniversalViewRequest,
  focusLabel: string,
  limit = 40,
  importId?: number | null
) {
  const qs = new URLSearchParams()
  if (importId) qs.set('importId', String(importId))
  if (focusLabel) qs.set('focusLabel', focusLabel)
  qs.set('limit', String(limit))
  const suffix = qs.toString() ? `?${qs.toString()}` : ''
  return request<UniversalEvidenceDto>(`/api/companies/${companyId}/universal/builder/evidence${suffix}`, {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export async function listUniversalViews(companyId: number) {
  return request<UniversalViewDto[]>(`/api/companies/${companyId}/universal/views`)
}

export async function getUniversalQuality(companyId: number, importId?: number | null) {
  const qs = importId ? `?importId=${encodeURIComponent(String(importId))}` : ''
  return request<UniversalImportQualityDto>(`/api/companies/${companyId}/universal/quality${qs}`)
}

export async function getUniversalLineage(companyId: number, importId?: number | null) {
  const qs = importId ? `?importId=${encodeURIComponent(String(importId))}` : ''
  return request<UniversalImportLineageDto | null>(`/api/companies/${companyId}/universal/lineage${qs}`)
}

export async function createUniversalView(companyId: number, body: UniversalViewRequest) {
  return request<UniversalViewDto>(`/api/companies/${companyId}/universal/views`, {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export async function createUniversalViewForImport(companyId: number, body: UniversalViewRequest, importId?: number | null) {
  const qs = importId ? `?importId=${encodeURIComponent(String(importId))}` : ''
  return request<UniversalViewDto>(`/api/companies/${companyId}/universal/views${qs}`, {
    method: 'POST',
    body: JSON.stringify(body)
  })
}

export async function getUniversalViewData(companyId: number, viewId: number) {
  const data = await request<any>(`/api/companies/${companyId}/universal/views/${viewId}/data`, { method: 'POST' })
  // Defensive: avoid blank screens if backend returns `null`/empty due to missing dataset/storage.
  if (!data || typeof data !== 'object') {
    throw new Error('Respuesta vacía al cargar el dashboard. Sube/re-sube un dataset en Universal y reintenta.')
  }
  return data as UniversalChartData
}

export async function getUniversalViewDataForImport(companyId: number, viewId: number, importId?: number | null) {
  const qs = importId ? `?importId=${encodeURIComponent(String(importId))}` : ''
  const data = await request<any>(`/api/companies/${companyId}/universal/views/${viewId}/data${qs}`, { method: 'POST' })
  if (!data || typeof data !== 'object') {
    throw new Error('Respuesta vacía al cargar el dashboard. Sube/re-sube un dataset en Universal y reintenta.')
  }
  return data as UniversalChartData
}

export async function getUniversalViewEvidenceForImport(
  companyId: number,
  viewId: number,
  focusLabel: string,
  limit = 40,
  importId?: number | null
) {
  const qs = new URLSearchParams()
  if (importId) qs.set('importId', String(importId))
  if (focusLabel) qs.set('focusLabel', focusLabel)
  qs.set('limit', String(limit))
  const suffix = qs.toString() ? `?${qs.toString()}` : ''
  return request<UniversalEvidenceDto>(`/api/companies/${companyId}/universal/views/${viewId}/evidence${suffix}`, { method: 'POST' })
}

export async function generateReport(companyId: number, period: string, universalViewId?: number | null) {
  return request<ReportDto>(`/api/companies/${companyId}/reports`, {
    method: 'POST',
    body: JSON.stringify({ period, universalViewId: universalViewId ?? null })
  })
}

export async function getReportContent(companyId: number, reportId: number) {
  return request<string>(`/api/companies/${companyId}/reports/${reportId}/content`)
}

export async function downloadReportPdf(companyId: number, reportId: number) {
  const url = `${API_URL}/api/companies/${companyId}/reports/${reportId}/content.pdf`
  const res = await fetchWithAuth(url, {}, { auth: true, retry: true, timeoutMs: 120_000 })
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
  versionNo?: number | null
  selectedUniversalViewId?: number | null
  selectedUniversalViewName?: string | null
  selectedUniversalAggregationMode?: string | null
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
  metaJson?: string | null
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
