import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import {
  assistantChat,
  generateAdvisorReport,
  getLatestRecommendations,
  getReportContent,
  type AdvisorAction,
  type AssistantMessage
} from '../api'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'

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

  const [assistantMessages, setAssistantMessages] = useState<AssistantMessage[]>([
    {
      role: 'assistant',
      content:
        'Soy tu asesor PLATINUM. Dime tu objetivo (margen, costes, caja o crecimiento) y te propongo un plan 30/60/90 días con acciones.'
    }
  ])
  const [assistantInput, setAssistantInput] = useState('')
  const [assistantLoading, setAssistantLoading] = useState(false)
  const [assistantActions, setAssistantActions] = useState<AdvisorAction[]>([])
  const [assistantQuestions, setAssistantQuestions] = useState<string[]>([])

  const { data: snapshot } = useQuery({
    queryKey: ['advisor-snapshot', companyId],
    queryFn: () => getLatestRecommendations(companyId as number),
    enabled: !!companyId && hasPlatinum
  })

  const latestActions = useMemo(() => {
    const a = (snapshot as any)?.actions || []
    return Array.isArray(a) ? (a as AdvisorAction[]) : []
  }, [snapshot])

  async function handleSend() {
    if (!companyId || !hasPlatinum) return
    const content = assistantInput.trim()
    if (!content) return
    const nextMessages: AssistantMessage[] = [...assistantMessages, { role: 'user', content }]
    setAssistantMessages(nextMessages)
    setAssistantInput('')
    setAssistantLoading(true)
    try {
      const res = await assistantChat(companyId, nextMessages)
      setAssistantMessages([...nextMessages, { role: 'assistant', content: res.reply }])
      setAssistantActions(res.actions || [])
      setAssistantQuestions(res.questions || [])
    } catch (err: any) {
      toast.push({ tone: 'danger', title: 'Asesor', message: err?.message || 'No se pudo consultar al asesor.' })
    } finally {
      setAssistantLoading(false)
    }
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
    await queryClient.invalidateQueries({ queryKey: ['advisor-snapshot', companyId] })
  }

  return (
    <div>
      <PageHeader
        title="Asesor PLATINUM"
        subtitle="Chat consultivo + recomendaciones accionables basadas en tus datos."
        actions={<span className="badge">{(plan || 'BRONZE').toUpperCase()}</span>}
      />

      {!companyId ? (
        <Alert tone="warning" title="Falta seleccionar empresa">
          Selecciona una empresa arriba para usar el asesor.
        </Alert>
      ) : null}

      {!hasPlatinum ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>Disponible en PLATINUM</h3>
          <div className="upload-hint">
            Para habilitar chat consultivo y acciones 30/60/90 días, sube el plan de la empresa a PLATINUM.
          </div>
        </div>
      ) : (
        <>
          <div className="grid section" style={{ gridTemplateColumns: '1.2fr .8fr' }}>
            <div className="card">
              <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
                <h3 style={{ margin: 0 }}>Chat</h3>
                <Button size="sm" variant="secondary" onClick={handleGenerateConsultingReport} disabled={!companyId}>
                  Generar informe consultivo
                </Button>
              </div>

              <div className="chat-box" style={{ marginTop: 12 }}>
                {assistantMessages.map((m, idx) => (
                  <div key={idx} className={`chat-line ${m.role}`}>
                    <div className="chat-meta">{m.role === 'user' ? 'Tú' : 'Asesor'}</div>
                    <div className="chat-msg">{m.content}</div>
                  </div>
                ))}
                {assistantLoading ? <div className="chat-typing">Pensando…</div> : null}
              </div>

              <div className="upload-row" style={{ marginTop: 12 }}>
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
                <div style={{ marginTop: 10 }} className="upload-hint">
                  <strong>Preguntas sugeridas:</strong> {assistantQuestions.slice(0, 5).join(' · ')}
                </div>
              ) : null}
            </div>

            <div className="card">
              <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
                <h3 style={{ margin: 0 }}>Recomendaciones</h3>
                <Button size="sm" variant="ghost" onClick={handleRefreshSnapshot}>
                  Refrescar
                </Button>
              </div>

              {!latestActions?.length ? (
                <div className="empty" style={{ marginTop: 12 }}>
                  Aún no hay snapshots. Puedes generar uno desde Automatización.
                </div>
              ) : (
                <div style={{ marginTop: 12, display: 'grid', gap: 10 }}>
                  {latestActions.slice(0, 8).map((a, idx) => (
                    <div key={idx} className="card soft">
                      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
                        <div style={{ fontWeight: 700 }}>{a.title}</div>
                        <span className={`badge ${priorityTone(a.priority)}`}>{a.priority}</span>
                      </div>
                      <div className="hero-sub" style={{ marginTop: 8 }}>
                        {a.detail}
                      </div>
                      <div className="upload-hint" style={{ marginTop: 8 }}>
                        {a.horizon} · {a.kpi}
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {assistantActions?.length ? (
                <div style={{ marginTop: 14 }}>
                  <div className="upload-hint" style={{ marginBottom: 8 }}>
                    <strong>Acciones del chat:</strong>
                  </div>
                  <div style={{ display: 'grid', gap: 10 }}>
                    {assistantActions.slice(0, 6).map((a, idx) => (
                      <div key={idx} className="card soft">
                        <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
                          <div style={{ fontWeight: 700 }}>{a.title}</div>
                          <span className={`badge ${priorityTone(a.priority)}`}>{a.priority}</span>
                        </div>
                        <div className="hero-sub" style={{ marginTop: 8 }}>
                          {a.detail}
                        </div>
                        <div className="upload-hint" style={{ marginTop: 8 }}>
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

