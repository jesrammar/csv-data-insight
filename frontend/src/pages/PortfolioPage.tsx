import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createInviteLink,
  createPasswordResetLink,
  createUser,
  getCompanies,
  getUsers,
  getUserRole,
  updateUser,
  updateUserCompanies,
  type UserActionLink,
  type UserDto
} from '../api'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'

type Company = { id: number; name: string; plan: string }

export default function PortfolioPage() {
  const toast = useToast()
  const qc = useQueryClient()
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

  return (
    <div>
      <PageHeader
        title="Cartera"
        subtitle="Clientes y accesos a empresas (solo tu cartera)."
        actions={
          <Button variant="ghost" size="sm" onClick={() => reload()} disabled={!isConsultor || usersFetching || companiesFetching}>
            {usersFetching || companiesFetching ? 'Actualizando…' : 'Recargar'}
          </Button>
        }
      />

      {!isConsultor ? (
        <Alert tone="warning" title="No autorizado">
          Esta pantalla es solo para CONSULTOR.
        </Alert>
      ) : usersError || companiesError ? (
        <Alert tone="danger" title="Error cargando datos">
          {String(((usersError as any)?.message || usersError || (companiesError as any)?.message || companiesError) ?? '')}
        </Alert>
      ) : null}

      {isConsultor ? (
        <div className="card section">
          <h3 className="h3-reset">Crear cliente</h3>
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
              Empresas asignadas (mínimo 1)
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
              {creating ? 'Creando…' : 'Crear cliente'}
            </Button>
          </div>
        </div>
      ) : null}

      {isConsultor ? (
        <div className="card section">
          <h3 className="h3-reset">Clientes</h3>
          {!clients.length ? (
            <div className="empty">No hay clientes en tu cartera.</div>
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
          <h3 className="h3-reset">Editar cliente</h3>
          <div className="upload-hint">Usuario id: {editingUserId}</div>

          <div className="grid mt-12">
            <label className="row row-center gap-10 mt-4">
              <input type="checkbox" checked={editingEnabled} onChange={(e) => setEditingEnabled(e.target.checked)} />
              <span className="fw-700">Enabled</span>
              <span className="upload-hint">{editingEnabled ? 'Sí' : 'No'}</span>
            </label>
          </div>

          <div className="upload-hint mt-3 mb-8">
            Empresas asignadas
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
              Enlaces de acceso (envíalo al cliente)
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
