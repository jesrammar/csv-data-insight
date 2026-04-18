import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  assistantChat,
  generateAdvisorReport,
  getLatestRecommendationsByObjective,
  getIngestionStatus,
  getUniversalSummary,
  getDashboard,
  getReportContent,
  snapshotRecommendations,
  type AdvisorAction,
  type AdvisorEvidence,
  type AssistantMessage
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'
import { formatMoney } from '../utils/format'

function priorityTone(priority: string) {
  const p = (priority || '').toLowerCase()
  if (p.includes('alta') || p.includes('high')) return 'danger'
  if (p.includes('media') || p.includes('medium')) return 'warning'
  return 'info'
}

export default function AdvisorPage() {
  const { id: companyId, plan } = useCompanySelection()
  const queryClient = useQueryClient()
  const toast = useToast()
  const hasPlatinum = plan === 'PLATINUM'
  const nowPeriod = new Date().toISOString().slice(0, 7)
  const contextKey = companyId ? `advisor.profile.${companyId}` : ''

  const [assistantMessages, setAssistantMessages] = useState<AssistantMessage[]>([
    {
      role: 'assistant',
      content:
        'Soy tu Assistant (reglas) en PLATINUM. Dime tu objetivo (margen, costes, caja o crecimiento) y te propongo un plan 30/60/90 días con acciones y evidencias.'
    }
  ])
  const [assistantInput, setAssistantInput] = useState('')
  const [assistantLoading, setAssistantLoading] = useState(false)
  const [assistantActions, setAssistantActions] = useState<AdvisorAction[]>([])
  const [assistantQuestions, setAssistantQuestions] = useState<string[]>([])
  const [assistantDisclosure, setAssistantDisclosure] = useState('')
  const [objective, setObjective] = useState<'GENERAL' | 'CASH' | 'COST' | 'MARGIN' | 'GROWTH' | 'RISK'>('GENERAL')
  const [snapshotPeriod, setSnapshotPeriod] = useState(nowPeriod)
  const [persona, setPersona] = useState<'CONSULTORIA' | 'CEO' | 'FIN' | 'OPS'>('CONSULTORIA')
  const [sector, setSector] = useState('')
  const [goal, setGoal] = useState('')
  const [constraints, setConstraints] = useState('')
  const [profileLoaded, setProfileLoaded] = useState(false)

  const { data: ingestion } = useQuery({
    queryKey: ['advisor-ingestion', companyId],
    queryFn: () => getIngestionStatus(companyId as number),
    enabled: !!companyId && hasPlatinum
  })

  const { data: universal } = useQuery({
    queryKey: ['advisor-universal', companyId],
    queryFn: () => getUniversalSummary(companyId as number),
    enabled: !!companyId && hasPlatinum
  })

  const { data: dashboard } = useQuery({
    queryKey: ['advisor-dashboard', companyId],
    queryFn: () => getDashboard(companyId as number, nowPeriod, nowPeriod),
    enabled: !!companyId && hasPlatinum
  })

  if (!profileLoaded && contextKey) {
    try {
      const raw = window.localStorage.getItem(contextKey)
      if (raw) {
        const p = JSON.parse(raw || '{}')
        if (p?.persona) setPersona(p.persona)
        if (p?.sector) setSector(String(p.sector))
        if (p?.goal) setGoal(String(p.goal))
        if (p?.constraints) setConstraints(String(p.constraints))
      }
    } catch {
      // ignore
    } finally {
      setProfileLoaded(true)
    }
  }

  const { data: snapshot } = useQuery({
    queryKey: ['advisor-snapshot', companyId, objective],
    queryFn: () => getLatestRecommendationsByObjective(companyId as number, objective),
    enabled: !!companyId && hasPlatinum
  })

  const latestActions = useMemo(() => {
    const a = (snapshot as any)?.actions || []
    return Array.isArray(a) ? (a as AdvisorAction[]) : []
  }, [snapshot])

  function saveProfile() {
    if (!contextKey) return
    try {
      window.localStorage.setItem(
        contextKey,
        JSON.stringify({
          persona,
          sector: sector.trim(),
          goal: goal.trim(),
          constraints: constraints.trim()
        })
      )
    } catch {
      // ignore
    }
  }

  function objectiveLabel(o: typeof objective) {
    if (o === 'CASH') return 'Caja'
    if (o === 'COST') return 'Costes'
    if (o === 'MARGIN') return 'Margen'
    if (o === 'GROWTH') return 'Crecimiento'
    if (o === 'RISK') return 'Riesgo'
    return 'General'
  }

  function personaLabel(p: typeof persona) {
    if (p === 'CEO') return 'CEO/gerencia'
    if (p === 'FIN') return 'Finanzas'
    if (p === 'OPS') return 'Operaciones'
    return 'Consultoría PYME'
  }

  function buildInitialPrompt() {
    const lines: string[] = []
    lines.push(`Objetivo: ${objectiveLabel(objective)}.`)
    lines.push(`Perfil: ${personaLabel(persona)}.`)
    if (sector.trim()) lines.push(`Sector/actividad: ${sector.trim()}.`)
    if (goal.trim()) lines.push(`Objetivo concreto: ${goal.trim()}.`)
    if (constraints.trim()) lines.push(`Restricciones: ${constraints.trim()}.`)
    lines.push('Devuélveme: (1) diagnóstico en 5 bullets, (2) plan 30/60/90 con 2–3 acciones por horizonte, (3) 3 KPIs a vigilar y umbrales, (4) riesgos y quick wins.')
    lines.push('Si falta algún dato, pregunta solo lo mínimo (2 preguntas).')
    return lines.join('\n')
  }

  async function sendMessage(content: string) {
    if (!companyId || !hasPlatinum) return
    const nextMessages: AssistantMessage[] = [...assistantMessages, { role: 'user', content }]
    setAssistantMessages(nextMessages)
    setAssistantLoading(true)
    try {
      const res = await assistantChat(companyId, nextMessages)
      setAssistantMessages([...nextMessages, { role: 'assistant', content: res.reply }])
      setAssistantActions(res.actions || [])
      setAssistantQuestions(res.questions || [])
      if (res.disclosure) setAssistantDisclosure(String(res.disclosure))
    } catch (err: any) {
      toast.push({ tone: 'danger', title: 'Assistant', message: err?.message || 'No se pudo consultar al assistant.' })
    } finally {
      setAssistantLoading(false)
    }
  }

  async function handleSend() {
    const content = assistantInput.trim()
    if (!content) return
    setAssistantInput('')
    await sendMessage(content)
  }

  async function handleStartPersonalPlan() {
    if (!hasPlatinum) return
    saveProfile()
    await sendMessage(buildInitialPrompt())
  }

  async function handleGenerateConsultingReport() {
    if (!companyId || !hasPlatinum) return
    try {
      const rep = await generateAdvisorReport(companyId)
      await queryClient.invalidateQueries({ queryKey: ['reports', companyId] })
      const html = await getReportContent(companyId, rep.id)
      const w = window.open('', '_blank')
      if (w) {
        w.document.open()
        w.document.write(html)
        w.document.close()
      }
      toast.push({ tone: 'success', title: 'Informe', message: 'Informe consultivo generado.' })
    } catch (err: any) {
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'No se pudo generar el informe.' })
    }
  }

  async function handleRefreshSnapshot() {
    if (!companyId) return
    await queryClient.invalidateQueries({ queryKey: ['advisor-snapshot', companyId, objective] })
  }

  async function handleGenerateSnapshot() {
    if (!companyId || !hasPlatinum) return
    const p = (snapshotPeriod || '').trim() || nowPeriod
    try {
      await snapshotRecommendations(companyId as number, p, objective)
      await queryClient.invalidateQueries({ queryKey: ['advisor-snapshot', companyId, objective] })
      toast.push({ tone: 'success', title: 'Recomendaciones', message: 'Snapshot generado.' })
    } catch (err: any) {
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'No se pudo generar el snapshot.' })
    }
  }

  type EvidenceNav = { to: string; label: string; state?: any }

  function evidenceLink(e: AdvisorEvidence): EvidenceNav | null {
    const extractPeriod = (raw: string) => {
      const m = raw.match(/(\d{4}-\d{2})(?:-\d{2})?/)
      return m?.[1] || null
    }

    const maybePeriod =
      extractPeriod(String(e?.subtitle || '')) ||
      extractPeriod(String(e?.title || '')) ||
      extractPeriod(String(e?.detail || '')) ||
      extractPeriod(String(e?.metric || ''))

    const t = String(e?.type || '').toLowerCase()
    if (!t) return null

    if (t.includes('tribunal')) return { to: '/tribunal', label: 'Abrir Tribunal' }
    if (t.includes('universal')) return { to: '/universal', label: 'Abrir Universal' }
    if (t.includes('budget')) return { to: '/budget', label: 'Abrir Presupuesto' }

    if (t.includes('alert')) {
      const to = maybePeriod ? `/alerts?period=${encodeURIComponent(maybePeriod)}` : '/alerts'
      return { to, label: 'Abrir Alertas' }
    }

    if (t.includes('dashboard') || t.includes('tx_')) {
      return maybePeriod ? { to: '/dashboard', state: { drillPeriod: maybePeriod }, label: 'Abrir Caja' } : { to: '/dashboard', label: 'Abrir Caja' }
    }

    return null
  }

  const groupedActions = useMemo(() => {
    const list = latestActions || []
    const buckets: Record<string, AdvisorAction[]> = { '7d': [], '30d': [], '60d': [], '90d': [], other: [] }
    for (const a of list) {
      const h = String(a.horizon || '').toLowerCase()
      if (h.includes('7')) buckets['7d'].push(a)
      else if (h.includes('30')) buckets['30d'].push(a)
      else if (h.includes('60')) buckets['60d'].push(a)
      else if (h.includes('90')) buckets['90d'].push(a)
      else buckets.other.push(a)
    }
    return buckets
  }, [latestActions])

  return (
    <div>
      <PageHeader
        title="Assistant (reglas) · PLATINUM"
        subtitle="Motor de reglas/heurísticas: diagnóstico + plan 30/60/90 + evidencias. No IA generativa."
        actions={<span className="badge">{(plan || 'BRONZE').toUpperCase()}</span>}
      />

      {!companyId ? (
        <Alert tone="warning" title="Falta seleccionar empresa">
          Selecciona una empresa arriba para usar el asesor.
        </Alert>
      ) : null}

      {!hasPlatinum ? (
        <div className="card section">
          <h3 className="h3-reset">Disponible en PLATINUM</h3>
          <div className="upload-hint">
            Para habilitar chat consultivo y acciones 30/60/90 días, sube el plan de la empresa a PLATINUM.
          </div>
        </div>
      ) : (
        <>
          <div className="grid section advisor-cols-12-10">
            <div className="card">
              <div className="row row-between row-center">
                <h3 className="m-0">Personalización</h3>
                <Button size="sm" onClick={handleStartPersonalPlan} disabled={assistantLoading}>
                  Generar plan
                </Button>
              </div>
              <div className="upload-hint mt-2">
                Ajusta 4 datos y el asesor te devuelve diagnóstico + plan 30/60/90 con evidencias.
              </div>

              <div className="grid mt-12">
                <div className="card soft">
                  <div className="upload-hint">Objetivo</div>
                  <select value={objective} onChange={(e) => setObjective(e.target.value as any)} className="mt-8">
                    <option value="GENERAL">General</option>
                    <option value="CASH">Caja</option>
                    <option value="COST">Costes</option>
                    <option value="MARGIN">Margen</option>
                    <option value="GROWTH">Crecimiento</option>
                    <option value="RISK">Riesgo</option>
                  </select>
                </div>
                <div className="card soft">
                  <div className="upload-hint">Perfil</div>
                  <select value={persona} onChange={(e) => setPersona(e.target.value as any)} className="mt-8">
                    <option value="CONSULTORIA">Consultoría PYME</option>
                    <option value="CEO">CEO/gerencia</option>
                    <option value="FIN">Finanzas</option>
                    <option value="OPS">Operaciones</option>
                  </select>
                </div>
              </div>

              <div className="upload-row tight">
                <input value={sector} onChange={(e) => setSector(e.target.value)} placeholder="Sector/actividad (opcional)" />
                <input value={goal} onChange={(e) => setGoal(e.target.value)} placeholder="Objetivo (p.ej. +3pp margen, -10% costes)" />
              </div>
              <div className="mt-2">
                <textarea
                  value={constraints}
                  onChange={(e) => setConstraints(e.target.value)}
                  placeholder="Restricciones (p.ej. no subir precios, no contratar, caja mínima)"
                  className="w-full minh-80"
                />
              </div>

              <div className="grid mt-12">
                <div className="card soft">
                  <div className="upload-hint">Datos detectados</div>
                  <div className="upload-hint mt-8">
                    Caja: {(dashboard as any)?.kpis?.length ? 'OK' : '—'} · Universal: {(universal as any)?.filename ? 'OK' : '—'} · Último import:{' '}
                    {String((ingestion as any)?.lastImport?.status || '—')}
                  </div>
                </div>
                <div className="card soft">
                  <div className="upload-hint">Última ingesta</div>
                  <div className="fw-900 mt-1">
                    {(ingestion as any)?.lastProcessedImport?.processedAt
                      ? new Date((ingestion as any).lastProcessedImport.processedAt).toLocaleString()
                      : (ingestion as any)?.lastImport?.createdAt
                        ? new Date((ingestion as any).lastImport.createdAt).toLocaleString()
                        : '—'}
                  </div>
                  <div className="upload-hint mt-1">
                    Neto mes: {(dashboard as any)?.kpis?.[0]?.netFlow != null ? formatMoney((dashboard as any).kpis[0].netFlow) : '—'}
                  </div>
                </div>
              </div>
            </div>

            <div className="card">
              <div className="row row-between row-center">
                <h3 className="m-0">Plan 30/60/90</h3>
                <Button size="sm" variant="secondary" onClick={handleGenerateConsultingReport} disabled={!companyId}>
                  Generar informe
                </Button>
              </div>
              <div className="upload-hint mt-2">
                Usa snapshots por objetivo. Luego abre evidencias para ir al detalle (Caja/Universal/Tribunal).
              </div>

              <div className="upload-row mt-12">
                <input value={snapshotPeriod} onChange={(e) => setSnapshotPeriod(e.target.value)} placeholder="YYYY-MM" />
                <Button size="sm" onClick={handleGenerateSnapshot} disabled={!snapshotPeriod.trim()}>
                  Generar snapshot
                </Button>
                <Button size="sm" variant="ghost" onClick={handleRefreshSnapshot}>
                  Refrescar
                </Button>
              </div>

              {!latestActions?.length ? (
                <div className="empty mt-12">
                  Aún no hay snapshots para este objetivo. Pulsa “Generar snapshot”.
                </div>
              ) : (
                <div className="stack gap-10 mt-12">
                  {(['7d', '30d', '60d', '90d'] as const).map((h) =>
                    groupedActions[h].length ? (
                      <div key={h} className="card soft">
                        <div className="upload-hint fw-900">
                          Horizonte: {h}
                        </div>
                        <div className="stack gap-8 mt-2">
                          {groupedActions[h].slice(0, 3).map((a, idx) => (
                            <details key={`${a.title}-${idx}`} className="card soft card-pad-sm">
                              <summary className="cursor-pointer">
                                <span className="fw-800">{a.title}</span>{' '}
                                <span className={`badge ${priorityTone(a.priority)}`}>{a.priority}</span>
                              </summary>
                              <div className="upload-hint mt-8">
                                {a.detail}
                              </div>
                              <div className="upload-hint mt-8">
                                KPI: {a.kpi}
                              </div>
                              {a.evidence?.length ? (
                                <div className="stack gap-8 mt-2">
                                  {(a.evidence || []).slice(0, 6).map((e, i) => {
                                    const link = evidenceLink(e as any)
                                    return (
                                      <div key={`${e.type}-${i}`} className="card card-pad-xs">
                                        <div className="row row-between row-center">
                                          <div className="fw-800">{e.title}</div>
                                          {link ? (
                                            <Link className="badge" to={link.to} state={link.state}>
                                              {link.label}
                                            </Link>
                                          ) : (
                                            <span className="badge">{String(e.type || 'evidence')}</span>
                                          )}
                                        </div>
                                        {e.subtitle ? <div className="upload-hint mt-1">{e.subtitle}</div> : null}
                                        {e.metric ? <div className="upload-hint">Metric: {e.metric}</div> : null}
                                        {e.detail ? <div className="upload-hint">{e.detail}</div> : null}
                                      </div>
                                    )
                                  })}
                                </div>
                              ) : null}
                            </details>
                          ))}
                        </div>
                      </div>
                    ) : null
                  )}
                </div>
              )}
            </div>
          </div>

          <div className="grid section advisor-cols-12-08">
            <div className="card">
              <div className="row row-between row-center">
                <h3 className="m-0">Chat consultivo</h3>
                <Button size="sm" variant="secondary" onClick={handleGenerateConsultingReport} disabled={!companyId}>
                  Generar informe consultivo
                </Button>
              </div>

              {assistantDisclosure ? <div className="upload-hint mt-2">{assistantDisclosure}</div> : null}

              <div className="chat-box mt-12">
                {assistantMessages.map((m, idx) => (
                  <div key={idx} className={`chat-line ${m.role}`}>
                    <div className="chat-meta">{m.role === 'user' ? 'Tú' : 'Assistant'}</div>
                    <div className="chat-msg">{m.content}</div>
                  </div>
                ))}
                {assistantLoading ? <div className="chat-typing">Pensando…</div> : null}
              </div>

              <div className="upload-row mt-12">
                <input
                  value={assistantInput}
                  onChange={(e) => setAssistantInput(e.target.value)}
                  placeholder="Pregunta por caja, márgenes, costes, precios o riesgos…"
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') handleSend()
                  }}
                />
                <Button onClick={handleSend} disabled={!assistantInput.trim() || assistantLoading}>
                  Enviar
                </Button>
              </div>

              {assistantQuestions?.length ? (
                <div className="upload-hint mt-2">
                  <strong>Preguntas sugeridas:</strong> {assistantQuestions.slice(0, 5).join(' · ')}
                </div>
              ) : null}
            </div>

            <div className="card">
              <div className="row row-between row-center">
                <h3 className="m-0">Recomendaciones</h3>
                <div className="row gap-8">
                  <Button size="sm" variant="ghost" onClick={handleRefreshSnapshot}>
                    Refrescar
                  </Button>
                </div>
              </div>

              <div className="upload-row mt-12">
                <select value={objective} onChange={(e) => setObjective(e.target.value as any)}>
                  <option value="GENERAL">Objetivo: General</option>
                  <option value="CASH">Objetivo: Caja</option>
                  <option value="COST">Objetivo: Costes</option>
                  <option value="MARGIN">Objetivo: Margen</option>
                  <option value="GROWTH">Objetivo: Crecimiento</option>
                  <option value="RISK">Objetivo: Riesgo</option>
                </select>
                <input value={snapshotPeriod} onChange={(e) => setSnapshotPeriod(e.target.value)} placeholder="YYYY-MM" />
                <Button size="sm" onClick={handleGenerateSnapshot} disabled={!snapshotPeriod.trim()}>
                  Generar
                </Button>
              </div>

              {!latestActions?.length ? (
                <div className="empty mt-12">
                  Aún no hay snapshots para este objetivo. Pulsa “Generar” o usa Automatización.
                </div>
              ) : (
                <div className="stack gap-10 mt-12">
                  {latestActions.slice(0, 8).map((a, idx) => (
                    <div key={idx} className="card soft">
                      <div className="row row-between row-center">
                        <div className="fw-700">{a.title}</div>
                        <span className={`badge ${priorityTone(a.priority)}`}>{a.priority}</span>
                      </div>
                      <div className="hero-sub mt-8">
                        {a.detail}
                      </div>
                      <div className="upload-hint mt-8">
                        {a.horizon} · {a.kpi}
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {assistantActions?.length ? (
                <div className="mt-3">
                  <div className="upload-hint mb-8">
                    <strong>Acciones del chat:</strong>
                  </div>
                  <div className="stack gap-10">
                    {assistantActions.slice(0, 6).map((a, idx) => (
                      <div key={idx} className="card soft">
                        <div className="row row-between row-center">
                          <div className="fw-700">{a.title}</div>
                          <span className={`badge ${priorityTone(a.priority)}`}>{a.priority}</span>
                        </div>
                        <div className="hero-sub mt-8">
                          {a.detail}
                        </div>
                        <div className="upload-hint mt-8">
                          {a.horizon} · {a.kpi}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
            </div>
          </div>
        </>
      )}
    </div>
  )
}

