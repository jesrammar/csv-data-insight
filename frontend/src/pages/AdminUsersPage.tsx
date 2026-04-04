import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createUser, getCompanies, getUsers, getUserRole, updateUser, updateUserCompanies, type UserDto } from '../api'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
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
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>Crear usuario</h3>
          <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Email</span>
              <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="consultor@cliente.com" />
            </label>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Contraseña</span>
              <input value={password} onChange={(e) => setPassword(e.target.value)} placeholder="password" />
            </label>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Rol</span>
              <select value={newRole} onChange={(e) => setNewRole(e.target.value as any)}>
                <option value="CONSULTOR">CONSULTOR</option>
                <option value="CLIENTE">CLIENTE</option>
              </select>
            </label>
          </div>

          <div style={{ marginTop: 14 }}>
            <div className="upload-hint" style={{ marginBottom: 8 }}>
              Empresas asignadas (si está vacío, no verá ninguna)
            </div>
            <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
              {companiesList.map((c) => (
                <label key={c.id} className="card soft" style={{ padding: 12, display: 'flex', gap: 10, alignItems: 'center' }}>
                  <input
                    type="checkbox"
                    checked={newCompanyIds.includes(c.id)}
                    onChange={() => setNewCompanyIds((prev) => toggleCompany(prev, c.id))}
                  />
                  <span style={{ fontWeight: 700 }}>{c.name}</span>
                  <span className="badge" style={{ marginLeft: 'auto' }}>
                    {String(c.plan || '').toUpperCase()}
                  </span>
                </label>
              ))}
            </div>
          </div>

          <div style={{ marginTop: 14 }}>
            <Button onClick={handleCreate} disabled={creating}>
              {creating ? 'Creando…' : 'Crear usuario'}
            </Button>
          </div>
        </div>
      ) : null}

      {isAdmin ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>Usuarios</h3>
          {!usersList.length ? (
            <div className="empty">No hay usuarios.</div>
          ) : (
            <div style={{ overflow: 'auto' }}>
              <table className="table">
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
                        <td style={{ fontWeight: 700 }}>{u.email}</td>
                        <td>{String(u.role || '').toUpperCase()}</td>
                        <td>{u.enabled ? 'Sí' : 'No'}</td>
                        <td className="upload-hint">{companyNames || '—'}</td>
                        <td style={{ textAlign: 'right' }}>
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
              </table>
            </div>
          )}
        </div>
      ) : null}

      {isAdmin && editingUserId != null ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>Editar usuario</h3>
          <div className="upload-hint">Usuario id: {editingUserId}</div>

          <div className="grid" style={{ marginTop: 12, gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Rol</span>
              <select value={editingRole} onChange={(e) => setEditingRole(e.target.value as any)}>
                <option value="CONSULTOR">CONSULTOR</option>
                <option value="CLIENTE">CLIENTE</option>
              </select>
            </label>
            <label style={{ display: 'flex', gap: 10, alignItems: 'center', marginTop: 22 }}>
              <input type="checkbox" checked={editingEnabled} onChange={(e) => setEditingEnabled(e.target.checked)} />
              <span style={{ fontWeight: 700 }}>Enabled</span>
              <span className="upload-hint">{editingEnabled ? 'Sí' : 'No'}</span>
            </label>
          </div>

          <div className="upload-hint" style={{ marginTop: 14, marginBottom: 8 }}>
            Empresas asignadas
          </div>
          <div className="grid" style={{ marginTop: 12, gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
            {companiesList.map((c) => (
              <label key={c.id} className="card soft" style={{ padding: 12, display: 'flex', gap: 10, alignItems: 'center' }}>
                <input
                  type="checkbox"
                  checked={editingCompanyIds.includes(c.id)}
                  onChange={() => setEditingCompanyIds((prev) => toggleCompany(prev, c.id))}
                />
                <span style={{ fontWeight: 700 }}>{c.name}</span>
                <span className="badge" style={{ marginLeft: 'auto' }}>
                  {String(c.plan || '').toUpperCase()}
                </span>
              </label>
            ))}
          </div>

          <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
            <Button variant="ghost" onClick={() => setEditingUserId(null)} disabled={saving}>
              Cancelar
            </Button>
            <Button onClick={handleSaveAll} disabled={saving}>
              {saving ? 'Guardando…' : 'Guardar'}
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  )
}
