import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  createInviteLink,
  createPasswordResetLink,
  createUser,
  getCompanies,
  getPortfolioOperations,
  getUsers,
  getUserRole,
  updateUser,
  updateUserCompanies,
  type PortfolioCompanyOperation,
  type UserActionLink,
  type UserDto
} from '../api'
import MonthlyFlowTimeline, { type MonthlyFlowItem } from '../components/MonthlyFlowTimeline'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'
import { setActiveCompanySelection } from '../hooks/useCompany'
import { setWorkPeriod } from '../utils/workPeriod'

type Company = { id: number; name: string; plan: string }

function portfolioStepAction(step?: { status?: string | null } | null) {
  if ((step as any)?.actionLabel) return String((step as any).actionLabel)
  const status = String(step?.status || '').toUpperCase()
  if (status === 'PENDING') return 'Completar cartera'
  if (status === 'STALE') return 'Actualizar cartera'
  if (status === 'LOADED') return 'Ver cartera'
  return 'Ver cierre'
}

export default function PortfolioPage() {
  const toast = useToast()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const role = getUserRole()
  const isConsultor = role === 'CONSULTOR'

  const { data: users, error: usersError, isFetching: usersFetching } = useQuery({
    queryKey: ['portfolio-users'],
    queryFn: getUsers,
    enabled: isConsultor
  })

  const { data: companies, error: companiesError, isFetching: companiesFetching } = useQuery({
    queryKey: ['portfolio-companies'],
    queryFn: getCompanies,
    enabled: isConsultor
  })

  const companiesList = (companies || []) as Company[]
  const companiesById = useMemo(() => {
    const m = new Map<number, Company>()
    for (const c of companiesList) m.set(Number(c.id), c)
    return m
  }, [companiesList])

  const clients = useMemo(() => {
    const list = (users || []) as UserDto[]
    return list.filter((u) => String(u.role || '').toUpperCase() === 'CLIENTE')
  }, [users])
  const companyOwners = useMemo(() => {
    const map = new Map<number, string[]>()
    for (const client of clients) {
      for (const companyId of client.companyIds || []) {
        if (!map.has(companyId)) map.set(companyId, [])
        map.get(companyId)!.push(client.email)
      }
    }
    return map
  }, [clients])
  const { data: portfolioOps, isFetching: portfolioOpsFetching, error: portfolioOpsError } = useQuery({
    queryKey: ['portfolio-ops', 4],
    enabled: isConsultor && companiesList.length > 0,
    queryFn: () => getPortfolioOperations(4)
  })
  const recentMonths = portfolioOps?.months || []
  const portfolioOpsList = useMemo(
    () =>
      (portfolioOps?.companies || []).map((entry: PortfolioCompanyOperation) => ({
        ...entry,
        company: {
          id: entry.companyId,
          name: entry.companyName,
          plan: entry.companyPlan
        },
        items: entry.items as MonthlyFlowItem[]
      })),
    [portfolioOps]
  )
  const [opsFilter, setOpsFilter] = useState<'all' | 'blocked' | 'pending_pdf' | 'in_progress' | 'ready'>('all')
  const filteredPortfolioOps = useMemo(() => {
    if (opsFilter === 'blocked') return portfolioOpsList.filter((item) => item.blockedCount > 0)
    if (opsFilter === 'pending_pdf') return portfolioOpsList.filter((item) => item.pendingPdfCount > 0)
    if (opsFilter === 'in_progress') return portfolioOpsList.filter((item) => item.statusLabel === 'En curso')
    if (opsFilter === 'ready') return portfolioOpsList.filter((item) => item.readyCount === recentMonths.length)
    return portfolioOpsList
  }, [opsFilter, portfolioOpsList, recentMonths.length])
  const blockedCompanies = portfolioOps?.summary?.blockedCompanies || 0
  const readyCompanies = portfolioOps?.summary?.readyCompanies || 0
  const activeCompanies = portfolioOps?.summary?.activeCompanies || 0
  const topPriorityCompany = portfolioOpsList[0] || null

  const [creating, setCreating] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [newCompanyIds, setNewCompanyIds] = useState<number[]>([])

  const [editingUserId, setEditingUserId] = useState<number | null>(null)
  const [editingCompanyIds, setEditingCompanyIds] = useState<number[]>([])
  const [editingEnabled, setEditingEnabled] = useState(true)
  const [saving, setSaving] = useState(false)
  const [actionLink, setActionLink] = useState<UserActionLink | null>(null)
  const [linkLoading, setLinkLoading] = useState(false)

  function toggleCompany(list: number[], id: number) {
    if (list.includes(id)) return list.filter((x) => x !== id)
    return [...list, id]
  }

  function generateStrongPassword() {
    const upper = 'ABCDEFGHJKLMNPQRSTUVWXYZ'
    const lower = 'abcdefghijkmnopqrstuvwxyz'
    const digits = '23456789'
    const all = upper + lower + digits
    const bytes = new Uint32Array(16)
    crypto.getRandomValues(bytes)
    const pick = (alphabet: string, i: number) => alphabet[bytes[i] % alphabet.length]
    const chars = [
      pick(upper, 0),
      pick(lower, 1),
      pick(digits, 2),
      ...Array.from({ length: 9 }).map((_, idx) => pick(all, idx + 3))
    ]
    // simple shuffle
    for (let i = chars.length - 1; i > 0; i--) {
      const j = bytes[i] % (i + 1)
      const tmp = chars[i]
      chars[i] = chars[j]
      chars[j] = tmp
    }
    return chars.join('')
  }

  function startEdit(u: UserDto) {
    setEditingUserId(u.id)
    setEditingCompanyIds(Array.isArray(u.companyIds) ? u.companyIds.map(Number) : [])
    setEditingEnabled(!!u.enabled)
    setActionLink(null)
  }

  async function reload() {
    await qc.invalidateQueries({ queryKey: ['portfolio-users'] })
    await qc.invalidateQueries({ queryKey: ['portfolio-companies'] })
    await qc.invalidateQueries({ queryKey: ['portfolio-ops'] })
  }

  async function handleCreate() {
    if (!isConsultor) return
    const e = email.trim().toLowerCase()
    if (!e || !password.trim()) {
      toast.push({ tone: 'warning', title: 'Falta información', message: 'Email y contraseña son obligatorios.' })
      return
    }
    if (!newCompanyIds.length) {
      toast.push({ tone: 'warning', title: 'Falta asignación', message: 'Asigna al menos una empresa al cliente.' })
      return
    }
    setCreating(true)
    try {
      await createUser({ email: e, password, role: 'CLIENTE', companyIds: newCompanyIds })
      toast.push({ tone: 'success', title: 'Cliente', message: 'Cliente creado.' })
      setEmail('')
      setPassword('')
      setNewCompanyIds([])
      await reload()
    } catch (e: any) {
      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo crear el cliente.' })
    } finally {
      setCreating(false)
    }
  }

  async function handleSave() {
    if (!isConsultor || editingUserId == null) return
    if (!editingCompanyIds.length) {
      toast.push({ tone: 'warning', title: 'Falta asignación', message: 'Un cliente debe tener al menos una empresa.' })
      return
    }
    setSaving(true)
    try {
      await updateUser(editingUserId, { enabled: editingEnabled })
      await updateUserCompanies(editingUserId, editingCompanyIds)
      toast.push({ tone: 'success', title: 'Cartera', message: 'Cliente actualizado.' })
      setEditingUserId(null)
      setEditingCompanyIds([])
      await reload()
    } catch (e: any) {
      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo actualizar.' })
    } finally {
      setSaving(false)
    }
  }

  async function copyLink(full: string) {
    try {
      await navigator.clipboard.writeText(full)
      toast.push({ tone: 'success', title: 'Enlace', message: 'Copiado al portapapeles.' })
    } catch {
      toast.push({ tone: 'info', title: 'Enlace', message: 'No se pudo copiar automáticamente. Copia el enlace manualmente.' })
    }
  }

  function goToCompanyContext(companyId: number, plan: string, href: string, period?: string | null) {
    setActiveCompanySelection(companyId, plan)
    if (period) setWorkPeriod(companyId, period)
    const params = new URLSearchParams()
    params.set('companyId', String(companyId))
    params.set('companyPlan', String(plan || 'BRONZE').toUpperCase())
    if (period) params.set('period', period)
    navigate(`${href}?${params.toString()}`)
  }

  return (
    <div>
      <PageHeader
        title="Cartera"
        subtitle="Clientes finales y accesos a empresas gestionadas de tu cartera."
        actions={
          <Button variant="ghost" size="sm" onClick={() => reload()} disabled={!isConsultor || usersFetching || companiesFetching}>
            {usersFetching || companiesFetching ? 'Actualizando…' : 'Recargar'}
          </Button>
        }
      />

      {!isConsultor ? (
        <Alert tone="warning" title="No autorizado">
          Esta pantalla es solo para CONSULTOR y centraliza la cartera que gestionas.
        </Alert>
      ) : usersError || companiesError ? (
        <Alert tone="danger" title="Error cargando datos">
          {String(((usersError as any)?.message || usersError || (companiesError as any)?.message || companiesError) ?? '')}
        </Alert>
      ) : portfolioOpsError ? (
        <Alert tone="danger" title="Error cargando operación de cartera">
          {String((portfolioOpsError as any)?.message || 'No se pudo resumir el estado de la cartera.')}
        </Alert>
      ) : null}

      {isConsultor ? (
        <div className="card section soft">
          <div className="mini-row row-baseline">
            <h3 className="m-0">Operación de cartera</h3>
            <span className="upload-hint">Qué empresas gestionadas van al día, cuáles siguen en curso y cuáles requieren desbloqueo.</span>
          </div>
          <div className="grid grid-autofit-220 mt-12">
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Empresas activas</div>
              <div className="fw-800 mt-1">{activeCompanies}</div>
              <div className="upload-hint mt-1">Con movimiento reciente en los últimos {recentMonths.length} meses.</div>
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Atascadas</div>
              <div className="fw-800 mt-1">{blockedCompanies}</div>
              <div className="upload-hint mt-1">Tienen al menos un mes con import en error o dead.</div>
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Al día</div>
              <div className="fw-800 mt-1">{readyCompanies}</div>
              <div className="upload-hint mt-1">Con PDF listo en todos los meses visibles.</div>
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Prioridad máxima</div>
              <div className="fw-800 mt-1">{topPriorityCompany?.company.name || 'Sin señales'}</div>
              <div className="upload-hint mt-1">
                {topPriorityCompany
                  ? `${topPriorityCompany.blockedCount} bloqueos · ${topPriorityCompany.pendingPdfCount} periodos sin PDF`
                  : 'La cartera está estable en este momento.'}
              </div>
            </div>
          </div>

          <div className="segmented mt-12" role="tablist" aria-label="Filtro operativo">
            <Button type="button" size="sm" variant={opsFilter === 'all' ? 'secondary' : 'ghost'} onClick={() => setOpsFilter('all')}>
              Todas
            </Button>
            <Button type="button" size="sm" variant={opsFilter === 'blocked' ? 'secondary' : 'ghost'} onClick={() => setOpsFilter('blocked')}>
              Atascadas
            </Button>
            <Button type="button" size="sm" variant={opsFilter === 'pending_pdf' ? 'secondary' : 'ghost'} onClick={() => setOpsFilter('pending_pdf')}>
              Sin PDF
            </Button>
            <Button type="button" size="sm" variant={opsFilter === 'in_progress' ? 'secondary' : 'ghost'} onClick={() => setOpsFilter('in_progress')}>
              En curso
            </Button>
            <Button type="button" size="sm" variant={opsFilter === 'ready' ? 'secondary' : 'ghost'} onClick={() => setOpsFilter('ready')}>
              Al día
            </Button>
          </div>

          {portfolioOpsFetching ? (
            <div className="empty mt-3">Cargando estado operativo de cartera...</div>
          ) : !portfolioOpsList.length ? (
            <div className="empty mt-3">Aún no hay empresas gestionadas suficientes para construir la vista operativa.</div>
          ) : !filteredPortfolioOps.length ? (
            <div className="empty mt-3">No hay empresas gestionadas que coincidan con este filtro.</div>
          ) : (
            <div className="stack gap-14 mt-3">
              {filteredPortfolioOps.map((entry) => {
                const blockedPeriod = entry.items.find((item) => ['ERROR', 'DEAD', 'BLOCKED'].includes(String(item.importStatus || '').toUpperCase()))?.period || null
                const portfolioIssueItem =
                  entry.items.find(
                    (item) => item.portfolioStep?.applicable && String(item.portfolioStep?.status || '').toUpperCase() !== 'LOADED'
                  ) || null
                const pendingPdfPeriod = entry.items.find((item) => item.hasReading && !item.hasReport)?.period || null
                const inProgressPeriod =
                  entry.items.find((item) => ['RUNNING', 'PENDING', 'RETRY', 'WARNING'].includes(String(item.importStatus || '').toUpperCase()) || item.hasReading)?.period || null
                const suggestedAction =
                  entry.blockedCount > 0
                    ? { href: '/monthly-close', label: 'Abrir cierre', period: blockedPeriod }
                    : portfolioIssueItem
                      ? {
                          href: '/monthly-close',
                          label: portfolioStepAction(portfolioIssueItem.portfolioStep),
                          period: portfolioIssueItem.period
                        }
                      : entry.pendingPdfCount > 0
                      ? { href: '/monthly-close', label: 'Cerrar periodo', period: pendingPdfPeriod }
                      : entry.statusLabel === 'En curso'
                        ? { href: '/monthly-close', label: 'Seguir cierre', period: inProgressPeriod }
                        : { href: '/monthly-close', label: 'Ver cierre', period: entry.items[0]?.period || null }

                return (
                <div key={entry.company.id} className="card soft card-pad-sm">
                  <div className="mini-row row-baseline row-wrap">
                    <div>
                      <div className="fw-800">{entry.company.name}</div>
                      <div className="upload-hint mt-1">
                        {companyOwners.get(entry.company.id)?.join(', ') || 'Sin cliente final asignado'} · Plan {String(entry.company.plan || '').toUpperCase()}
                      </div>
                      <div className="upload-hint mt-1">
                        Prioridad {entry.priorityScore} · {entry.blockedCount} bloqueos · {entry.pendingPdfCount} sin PDF
                      </div>
                      {portfolioIssueItem?.portfolioStep ? (
                        <div className="upload-hint mt-1">
                          Cartera {portfolioIssueItem.period}: {portfolioIssueItem.portfolioStep.title}
                        </div>
                      ) : null}
                    </div>
                    <span className="badge">{entry.statusLabel}</span>
                  </div>
                  <div className="mt-2">
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => goToCompanyContext(entry.company.id, entry.company.plan, suggestedAction.href, suggestedAction.period)}
                    >
                      {suggestedAction.label}
                      {suggestedAction.period ? ` · ${suggestedAction.period}` : ''}
                    </Button>
                  </div>
                  <MonthlyFlowTimeline items={entry.items} />
                </div>
                )
              })}
            </div>
          )}
        </div>
      ) : null}

      {isConsultor ? (
        <div className="card section">
          <h3 className="h3-reset">Crear cliente final</h3>
          <div className="grid">
            <label className="stack">
              <span className="upload-hint">Email</span>
              <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="cliente@empresa.com" inputMode="email" />
            </label>
            <label className="stack">
              <span className="upload-hint">Contraseña</span>
              <input value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" type="password" />
              <div className="row row-center row-wrap gap-10">
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    const p = generateStrongPassword()
                    setPassword(p)
                    toast.push({ tone: 'success', title: 'Contraseña', message: 'Generada (cópiala o envía un enlace de activación).' })
                  }}
                >
                  Generar fuerte
                </Button>
                <span className="upload-hint">10+ chars · mayúscula · minúscula · número</span>
              </div>
            </label>
          </div>

          <div className="mt-3">
            <div className="upload-hint mb-8">
              Empresas gestionadas asignadas (mínimo 1)
            </div>
            <div className="grid">
              {companiesList.map((c) => (
                <label key={c.id} className="card soft card-pad-sm row row-center gap-10">
                  <input
                    type="checkbox"
                    checked={newCompanyIds.includes(c.id)}
                    onChange={() => setNewCompanyIds((prev) => toggleCompany(prev, c.id))}
                  />
                  <span className="fw-700">{c.name}</span>
                  <span className="badge ml-auto">
                    {String(c.plan || '').toUpperCase()}
                  </span>
                </label>
              ))}
            </div>
          </div>

          <div className="mt-3">
            <Button onClick={handleCreate} disabled={creating}>
              {creating ? 'Creando…' : 'Crear cliente final'}
            </Button>
          </div>
        </div>
      ) : null}

      {isConsultor ? (
        <div className="card section">
          <h3 className="h3-reset">Clientes finales</h3>
          {!clients.length ? (
            <div className="empty">No hay clientes finales en tu cartera.</div>
          ) : (
            <div className="overflow-auto">
              <table className="table">
                <thead>
                  <tr>
                    <th>Email</th>
                    <th>Enabled</th>
                    <th>Empresas</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {clients.map((u) => {
                    const companyNames = (u.companyIds || [])
                      .map((id) => companiesById.get(Number(id))?.name)
                      .filter(Boolean)
                      .join(', ')
                    return (
                      <tr key={u.id}>
                        <td className="fw-700">{u.email}</td>
                        <td>{u.enabled ? 'Sí' : 'No'}</td>
                        <td className="upload-hint">{companyNames || '—'}</td>
                        <td className="text-right">
                          <Button variant="ghost" size="sm" onClick={() => startEdit(u)}>
                            Editar
                          </Button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ) : null}

      {isConsultor && editingUserId != null ? (
        <div className="card section">
          <h3 className="h3-reset">Editar cliente final</h3>
          <div className="upload-hint">Usuario id: {editingUserId}</div>

          <div className="grid mt-12">
            <label className="row row-center gap-10 mt-4">
              <input type="checkbox" checked={editingEnabled} onChange={(e) => setEditingEnabled(e.target.checked)} />
              <span className="fw-700">Enabled</span>
              <span className="upload-hint">{editingEnabled ? 'Sí' : 'No'}</span>
            </label>
          </div>

          <div className="upload-hint mt-3 mb-8">
            Empresas gestionadas asignadas
          </div>
          <div className="grid">
            {companiesList.map((c) => (
              <label key={c.id} className="card soft card-pad-sm row row-center gap-10">
                <input
                  type="checkbox"
                  checked={editingCompanyIds.includes(c.id)}
                  onChange={() => setEditingCompanyIds((prev) => toggleCompany(prev, c.id))}
                />
                <span className="fw-700">{c.name}</span>
                <span className="badge ml-auto">
                  {String(c.plan || '').toUpperCase()}
                </span>
              </label>
            ))}
          </div>

          <div className="row gap-10 mt-3">
            <Button variant="ghost" onClick={() => setEditingUserId(null)} disabled={saving}>
              Cancelar
            </Button>
            <Button onClick={handleSave} disabled={saving}>
              {saving ? 'Guardando…' : 'Guardar'}
            </Button>
          </div>

          <div className="mt-4">
            <div className="upload-hint mb-8">
              Enlaces de acceso (compártelos con el cliente final)
            </div>
            <div className="row row-wrap gap-10">
              <Button
                size="sm"
                variant="secondary"
                loading={linkLoading}
                onClick={async () => {
                  setLinkLoading(true)
                  try {
                    const l = await createInviteLink(editingUserId)
                    setActionLink(l)
                    await copyLink(`${window.location.origin}${l.path}`)
                  } catch (e: any) {
                    toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo generar el enlace.' })
                  } finally {
                    setLinkLoading(false)
                  }
                }}
              >
                Generar enlace (activación)
              </Button>
              <Button
                size="sm"
                variant="ghost"
                loading={linkLoading}
                onClick={async () => {
                  setLinkLoading(true)
                  try {
                    const l = await createPasswordResetLink(editingUserId)
                    setActionLink(l)
                    await copyLink(`${window.location.origin}${l.path}`)
                  } catch (e: any) {
                    toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo generar el enlace.' })
                  } finally {
                    setLinkLoading(false)
                  }
                }}
              >
                Generar enlace (reset)
              </Button>
            </div>

            {actionLink ? (
              <div className="card soft card-pad-sm mt-2">
                <div className="upload-hint">Enlace</div>
                <div className="mt-1 fw-800 break-all">
                  {`${window.location.origin}${actionLink.path}`}
                </div>
                <div className="upload-hint mt-1">
                  Caduca: {actionLink.expiresAt ? new Date(actionLink.expiresAt).toLocaleString() : '—'}
                </div>
                <div className="mt-2">
                  <Button size="sm" variant="ghost" onClick={() => copyLink(`${window.location.origin}${actionLink.path}`)}>
                    Copiar enlace
                  </Button>
                </div>
              </div>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  )
}

