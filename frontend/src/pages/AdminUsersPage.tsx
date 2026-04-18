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
import Card from '../components/ui/Card'
import Field from '../components/ui/Field'
import Grid from '../components/ui/Grid'
import Table from '../components/ui/Table'
import { useToast } from '../components/ui/ToastProvider'

type Company = { id: number; name: string; plan: string }

export default function AdminUsersPage() {
  const toast = useToast()
  const qc = useQueryClient()
  const role = getUserRole()
  const isAdmin = role === 'ADMIN'

  const { data: users, error: usersError, isFetching: usersFetching } = useQuery({
    queryKey: ['admin-users'],
    queryFn: getUsers,
    enabled: isAdmin
  })

  const { data: companies, error: companiesError, isFetching: companiesFetching } = useQuery({
    queryKey: ['admin-companies'],
    queryFn: getCompanies,
    enabled: isAdmin
  })

  const companiesList = (companies || []) as Company[]
  const companiesById = useMemo(() => {
    const m = new Map<number, Company>()
    for (const c of companiesList) m.set(Number(c.id), c)
    return m
  }, [companiesList])

  const [creating, setCreating] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [newRole, setNewRole] = useState<'CONSULTOR' | 'CLIENTE'>('CONSULTOR')
  const [newCompanyIds, setNewCompanyIds] = useState<number[]>([])

  const [editingUserId, setEditingUserId] = useState<number | null>(null)
  const [editingCompanyIds, setEditingCompanyIds] = useState<number[]>([])
  const [editingRole, setEditingRole] = useState<'CONSULTOR' | 'CLIENTE'>('CONSULTOR')
  const [editingEnabled, setEditingEnabled] = useState(true)
  const [saving, setSaving] = useState(false)
  const [linkLoading, setLinkLoading] = useState(false)
  const [actionLink, setActionLink] = useState<UserActionLink | null>(null)

  async function copyLink(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      toast.push({ tone: 'success', title: 'Copiado', message: 'Enlace copiado al portapapeles.' })
    } catch {
      toast.push({ tone: 'warning', title: 'Copia manual', message: 'No se pudo copiar automáticamente. Copia el enlace a mano.' })
    }
  }

  async function handleCreate() {
    if (!isAdmin) return
    const e = email.trim().toLowerCase()
    if (!e || !password.trim()) {
      toast.push({ tone: 'warning', title: 'Falta información', message: 'Email y contraseña son obligatorios.' })
      return
    }
    setCreating(true)
    try {
      await createUser({ email: e, password, role: newRole, companyIds: newCompanyIds })
      toast.push({ tone: 'success', title: 'Usuario', message: 'Usuario creado.' })
      setEmail('')
      setPassword('')
      setNewCompanyIds([])
      await qc.invalidateQueries({ queryKey: ['admin-users'] })
    } catch (e: any) {
      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo crear el usuario.' })
    } finally {
      setCreating(false)
    }
  }

  function startEdit(u: UserDto) {
    setEditingUserId(u.id)
    setEditingCompanyIds(Array.isArray(u.companyIds) ? u.companyIds.map(Number) : [])
    const r = String(u.role || '').toUpperCase()
    setEditingRole(r === 'CLIENTE' ? 'CLIENTE' : 'CONSULTOR')
    setEditingEnabled(!!u.enabled)
    setActionLink(null)
  }

  async function handleSaveAll() {
    if (!isAdmin || editingUserId == null) return
    setSaving(true)
    try {
      await updateUser(editingUserId, { role: editingRole, enabled: editingEnabled })
      await updateUserCompanies(editingUserId, editingCompanyIds)
      toast.push({ tone: 'success', title: 'Accesos', message: 'Empresas actualizadas.' })
      setEditingUserId(null)
      setEditingCompanyIds([])
      await qc.invalidateQueries({ queryKey: ['admin-users'] })
      await qc.invalidateQueries({ queryKey: ['companies'] })
      window.dispatchEvent(new Event('company-change'))
    } catch (e: any) {
      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo actualizar.' })
    } finally {
      setSaving(false)
    }
  }

  function toggleCompany(list: number[], id: number) {
    if (list.includes(id)) return list.filter((x) => x !== id)
    return [...list, id]
  }

  const usersList = (users || []) as UserDto[]

  return (
    <div>
      <PageHeader
        title="Admin · Usuarios"
        subtitle="Crea consultores/clientes y asigna las empresas que pueden ver. (ADMIN interno)"
        actions={
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              qc.invalidateQueries({ queryKey: ['admin-users'] })
              qc.invalidateQueries({ queryKey: ['admin-companies'] })
            }}
            disabled={!isAdmin || usersFetching || companiesFetching}
          >
            {usersFetching || companiesFetching ? 'Actualizando…' : 'Recargar'}
          </Button>
        }
      />

      {!isAdmin ? (
        <Alert tone="warning" title="No autorizado">
          Solo ADMIN puede gestionar usuarios.
        </Alert>
      ) : usersError || companiesError ? (
        <Alert tone="danger" title="Error cargando datos">
          {String(((usersError as any)?.message || usersError || (companiesError as any)?.message || companiesError) ?? '')}
        </Alert>
      ) : null}

      {isAdmin ? (
        <Card className="section">
          <h3 className="h3-reset">Crear usuario</h3>
          <Grid>
            <Field label="Email">
              <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="consultor@cliente.com" />
            </Field>
            <Field label="Contraseña">
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="password" />
            </Field>
            <Field label="Rol">
              <select value={newRole} onChange={(e) => setNewRole(e.target.value as any)}>
                <option value="CONSULTOR">CONSULTOR</option>
                <option value="CLIENTE">CLIENTE</option>
              </select>
            </Field>
          </Grid>

          <div className="mt-3">
            <div className="upload-hint mb-2">Empresas asignadas (si está vacío, no verá ninguna)</div>
            <Grid>
              {companiesList.map((c) => (
                <label key={c.id} className="card soft card-pad-sm row row-center gap-2">
                  <input
                    type="checkbox"
                    checked={newCompanyIds.includes(c.id)}
                    onChange={() => setNewCompanyIds((prev) => toggleCompany(prev, c.id))}
                  />
                  <span className="fw-700">{c.name}</span>
                  <span className="badge ml-auto">{String(c.plan || '').toUpperCase()}</span>
                </label>
              ))}
            </Grid>
          </div>

          <div className="mt-3">
            <Button onClick={handleCreate} disabled={creating}>
              {creating ? 'Creando…' : 'Crear usuario'}
            </Button>
          </div>
        </Card>
      ) : null}

      {isAdmin ? (
        <Card className="section">
          <h3 className="h3-reset">Usuarios</h3>
          {!usersList.length ? (
            <div className="empty">No hay usuarios.</div>
          ) : (
            <Table>
                <thead>
                  <tr>
                    <th>Email</th>
                    <th>Rol</th>
                    <th>Enabled</th>
                    <th>Empresas</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {usersList.map((u) => {
                    const companyNames = (u.companyIds || [])
                      .map((id) => companiesById.get(Number(id))?.name)
                      .filter(Boolean)
                      .join(', ')
                    return (
                      <tr key={u.id}>
                        <td className="fw-700">{u.email}</td>
                        <td>{String(u.role || '').toUpperCase()}</td>
                        <td>{u.enabled ? 'Sí' : 'No'}</td>
                        <td className="upload-hint">{companyNames || '—'}</td>
                        <td className="text-right">
                          {String(u.role || '').toUpperCase() === 'ADMIN' ? (
                            <span className="upload-hint">ADMIN (interno)</span>
                          ) : (
                            <Button variant="ghost" size="sm" onClick={() => startEdit(u)}>
                              Asignar empresas
                            </Button>
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
            </Table>
          )}
        </Card>
      ) : null}

      {isAdmin && editingUserId != null ? (
        <Card className="section">
          <h3 className="h3-reset">Editar usuario</h3>
          <div className="upload-hint">Usuario id: {editingUserId}</div>

          <div className="mt-2">
            <Grid>
              <Field label="Rol">
                <select value={editingRole} onChange={(e) => setEditingRole(e.target.value as any)}>
                  <option value="CONSULTOR">CONSULTOR</option>
                  <option value="CLIENTE">CLIENTE</option>
                </select>
              </Field>
              <Field label="Enabled">
                <div className="row row-center gap-2">
                  <input type="checkbox" checked={editingEnabled} onChange={(e) => setEditingEnabled(e.target.checked)} />
                  <span className="upload-hint">{editingEnabled ? 'Sí' : 'No'}</span>
                </div>
              </Field>
            </Grid>
          </div>

          <div className="upload-hint mt-3 mb-2">Empresas asignadas</div>
          <Grid>
            {companiesList.map((c) => (
              <label key={c.id} className="card soft card-pad-sm row row-center gap-2">
                <input
                  type="checkbox"
                  checked={editingCompanyIds.includes(c.id)}
                  onChange={() => setEditingCompanyIds((prev) => toggleCompany(prev, c.id))}
                />
                <span className="fw-700">{c.name}</span>
                <span className="badge ml-auto">{String(c.plan || '').toUpperCase()}</span>
              </label>
            ))}
          </Grid>

          <div className="row gap-2 mt-3">
            <Button variant="ghost" onClick={() => setEditingUserId(null)} disabled={saving}>
              Cancelar
            </Button>
            <Button onClick={handleSaveAll} disabled={saving}>
              {saving ? 'Guardando…' : 'Guardar'}
            </Button>
          </div>

          <div className="mt-3">
            <div className="upload-hint mb-2">Enlaces de acceso (envíalo al usuario)</div>
            <div className="row row-wrap row-center gap-2">
              <Button
                size="sm"
                variant="secondary"
                loading={linkLoading}
                onClick={async () => {
                  if (!editingUserId) return
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
                  if (!editingUserId) return
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
              <Card variant="soft" className="card-pad-sm mt-2">
                <div className="upload-hint">Enlace</div>
                <div className="mt-1 fw-800 break-all">{`${window.location.origin}${actionLink.path}`}</div>
                <div className="upload-hint mt-1">
                  Caduca: {actionLink.expiresAt ? new Date(actionLink.expiresAt).toLocaleString() : '—'}
                </div>
                <div className="mt-2">
                  <Button size="sm" variant="ghost" onClick={() => copyLink(`${window.location.origin}${actionLink.path}`)}>
                    Copiar enlace
                  </Button>
                </div>
              </Card>
            ) : null}
          </div>
        </Card>
      ) : null}
    </div>
  )
}
