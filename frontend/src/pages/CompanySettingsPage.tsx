import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getCompanySettings, updateCompanySettings } from '../api'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import PageHeader from '../components/ui/PageHeader'
import Skeleton from '../components/ui/Skeleton'
import { useToast } from '../components/ui/ToastProvider'
import { useCompanySelection } from '../hooks/useCompany'
import { getWorkPeriod, nowYm, setWorkPeriod } from '../utils/workPeriod'

function isValidWorkingPeriod(value: string) {
  const v = value.trim()
  return /^\d{4}-(0[1-9]|1[0-2])$/.test(v)
}

export default function CompanySettingsPage() {
  const toast = useToast()
  const queryClient = useQueryClient()
  const { id: companyId } = useCompanySelection()
  const [saving, setSaving] = useState(false)

  const { data: settings, isPending, error } = useQuery({
    queryKey: ['company-settings', companyId],
    queryFn: () => getCompanySettings(companyId as number),
    enabled: !!companyId
  })

  const [workingPeriod, setWorkingPeriodState] = useState('')
  const [autoMonthlyReport, setAutoMonthlyReport] = useState(false)
  const [reportConsultancyName, setReportConsultancyName] = useState('')
  const [reportLogoUrl, setReportLogoUrl] = useState('')
  const [reportPrimaryColor, setReportPrimaryColor] = useState('')
  const [reportFooterText, setReportFooterText] = useState('')
  const [touched, setTouched] = useState(false)

  useEffect(() => {
    if (!companyId) return
    const local = getWorkPeriod(companyId)
    const fromServer = settings?.workingPeriod ? String(settings.workingPeriod) : null
    const next = (local || fromServer || nowYm()).trim()
    setWorkingPeriodState(next)
    setAutoMonthlyReport(Boolean(settings?.autoMonthlyReport))
    setReportConsultancyName(String(settings?.reportConsultancyName || '').trim())
    setReportLogoUrl(String(settings?.reportLogoUrl || '').trim())
    setReportPrimaryColor(String(settings?.reportPrimaryColor || '').trim())
    setReportFooterText(String(settings?.reportFooterText || '').trim())
    setTouched(false)
  }, [companyId, settings])

  const workingPeriodError = useMemo(() => {
    if (!touched) return null
    if (!workingPeriod.trim()) return 'Indica un periodo en formato YYYY-MM.'
    if (!isValidWorkingPeriod(workingPeriod)) return 'Formato inválido. Usa YYYY-MM (mes 01–12).'
    return null
  }, [touched, workingPeriod])

  async function handleSave() {
    if (!companyId) return
    setTouched(true)
    const period = workingPeriod.trim()
    if (!isValidWorkingPeriod(period)) {
      toast.push({ tone: 'warning', title: 'Revisa el periodo', message: 'Usa el formato YYYY-MM (mes 01–12).' })
      return
    }

    setSaving(true)
    try {
      await updateCompanySettings(companyId, {
        workingPeriod: period,
        autoMonthlyReport,
        reportConsultancyName: reportConsultancyName.trim() || null,
        reportLogoUrl: reportLogoUrl.trim() || null,
        reportPrimaryColor: reportPrimaryColor.trim() || null,
        reportFooterText: reportFooterText.trim() || null
      })
      setWorkPeriod(companyId, period)
      await queryClient.invalidateQueries({ queryKey: ['company-settings', companyId] })
      toast.push({ tone: 'success', title: 'Ajustes guardados', message: 'Se han actualizado los ajustes de la empresa.' })
    } catch (e: any) {
      toast.push({ tone: 'danger', title: 'Error', message: e?.message || 'No se pudo guardar.' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <PageHeader
        title="Ajustes de empresa"
        subtitle={
          <>
            Configura el <span className="badge">mes de trabajo</span> y el entregable automático para la empresa seleccionada.
          </>
        }
        actions={
          <Button onClick={handleSave} disabled={!companyId || isPending || saving} loading={saving}>
            Guardar
          </Button>
        }
      />

      {!companyId ? <Alert tone="warning">Selecciona una empresa para configurar sus ajustes.</Alert> : null}
      {error ? <Alert tone="danger">{String((error as any)?.message || error)}</Alert> : null}

      {companyId && isPending ? (
        <div className="card section">
          <Skeleton className="sk-w-56p sk-h-14" />
          <Skeleton className="sk-w-82p sk-h-12 mt-2" />
          <Skeleton className="sk-w-74p sk-h-12 mt-2" />
        </div>
      ) : null}

      {companyId && !isPending ? (
        <div className="grid section">
          <div className="card">
            <h3 className="h3-reset">Periodo</h3>
            <div className="field mt-2">
              <span className="field-label">Mes de trabajo</span>
              <input
                value={workingPeriod}
                onChange={(e) => setWorkingPeriodState(e.target.value)}
                onBlur={() => setTouched(true)}
                placeholder="YYYY-MM"
                inputMode="numeric"
                aria-invalid={workingPeriodError ? 'true' : undefined}
              />
              <div className="field-hint">Afecta a checklist, informes y algunos dashboards. Ejemplo: 2026-04.</div>
              {workingPeriodError ? <div className="error mt-1">{workingPeriodError}</div> : null}
            </div>

            <div className="mt-2">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  const v = nowYm()
                  setWorkingPeriodState(v)
                  setTouched(true)
                }}
              >
                Usar mes actual ({nowYm()})
              </Button>
            </div>
          </div>

          <div className="card">
            <h3 className="h3-reset">Entregables</h3>
            <div className="field mt-2">
              <span className="field-label">Informe mensual automático</span>
              <label className="row row-center gap-2 mt-1">
                <input type="checkbox" checked={autoMonthlyReport} onChange={(e) => setAutoMonthlyReport(e.target.checked)} />
                <span>Generar entregable por defecto al cerrar el mes de trabajo</span>
              </label>
              <div className="field-hint">Si lo activas, el backend puede generar el informe mensual automáticamente según el periodo configurado.</div>
            </div>
          </div>

          <div className="card">
            <h3 className="h3-reset">Branding del informe</h3>
            <div className="field mt-2">
              <span className="field-label">Nombre de la consultora</span>
              <input value={reportConsultancyName} onChange={(e) => setReportConsultancyName(e.target.value)} placeholder="Ej: ASECON" />
              <div className="field-hint">Se muestra en la portada del PDF/HTML.</div>
            </div>

            <div className="field mt-2">
              <span className="field-label">Color principal</span>
              <input value={reportPrimaryColor} onChange={(e) => setReportPrimaryColor(e.target.value)} placeholder="#14b8a6" />
              <div className="field-hint">
                Usa <span className="mono">#RRGGBB</span> (o déjalo vacío para el color por defecto).
              </div>
            </div>

            <div className="field mt-2">
              <span className="field-label">Logo inline (opcional)</span>
              <input value={reportLogoUrl} onChange={(e) => setReportLogoUrl(e.target.value)} placeholder="data:image/png;base64,..." />
              <div className="field-hint">Por seguridad solo se aceptan logos inline en base64. Si no se configura, se usa el nombre de la consultora como logo.</div>
            </div>

            <div className="field mt-2">
              <span className="field-label">Texto del pie (opcional)</span>
              <input value={reportFooterText} onChange={(e) => setReportFooterText(e.target.value)} placeholder="Ej: Confidencial · Uso interno" />
              <div className="field-hint">Se muestra al final del informe.</div>
            </div>
          </div>
        </div>
      ) : null}
    </>
  )
}
