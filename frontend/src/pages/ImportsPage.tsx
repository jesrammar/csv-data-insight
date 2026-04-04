import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getImports,
  previewImport,
  previewUniversalXlsx,
  retryImport,
  type ImportJob,
  type ImportPreviewDto,
  uploadImportSmart,
  uploadTribunalImport,
  uploadUniversalImport,
  type UniversalXlsxPreview
} from '../api'
import { useEffect, useMemo, useState } from 'react'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'
import { useNavigate, useSearchParams } from 'react-router-dom'

export default function ImportsPage() {
  const { id: companyId, plan } = useCompanySelection()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const toast = useToast()
  const { data } = useQuery({
    queryKey: ['imports', companyId],
    queryFn: () => getImports(companyId as number),
    enabled: !!companyId
  })
  const [mode, setMode] = useState<'auto' | 'transactions' | 'universal'>('auto')
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'
  const guidesModule = mode === 'transactions' ? 'caja' : mode === 'universal' ? 'universal' : 'caja'

  useEffect(() => {
    const m = String(searchParams.get('mode') || '').toLowerCase()
    if (m === 'auto' || m === 'transactions' || m === 'universal') setMode(m as any)
    // only apply on initial navigation
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const [period, setPeriod] = useState('2025-06')
  const [file, setFile] = useState<File | null>(null)
  const [message, setMessage] = useState('')
  const [tone, setTone] = useState<'info' | 'success' | 'danger'>('info')

  const [uploading, setUploading] = useState(false)
  const [xlsxPreview, setXlsxPreview] = useState<UniversalXlsxPreview | null>(null)
  const [xlsxLoading, setXlsxLoading] = useState(false)
  const [sheetIndex, setSheetIndex] = useState<number | null>(null)
  const [headerRow, setHeaderRow] = useState<number | null>(null)
  const [showUniversalGuided, setShowUniversalGuided] = useState(false)

  const [txPreview, setTxPreview] = useState<ImportPreviewDto | null>(null)
  const [txPreviewLoading, setTxPreviewLoading] = useState(false)
  const [txnDateCol, setTxnDateCol] = useState('')
  const [amountCol, setAmountCol] = useState('')
  const [descriptionCol, setDescriptionCol] = useState('')
  const [counterpartyCol, setCounterpartyCol] = useState('')
  const [balanceEndCol, setBalanceEndCol] = useState('')

  const [showAllImports, setShowAllImports] = useState(false)
  const [showDeadImports, setShowDeadImports] = useState(false)
  const [showOnlyFailedImports, setShowOnlyFailedImports] = useState(false)

  const isCsv = !file ? true : file.name.toLowerCase().endsWith('.csv')
  const isXlsx = !file ? false : file.name.toLowerCase().endsWith('.xlsx')
  const isAllowed = !file ? true : isCsv || isXlsx
  const canPreviewXlsx = !!companyId && (mode === 'universal' || mode === 'auto') && isXlsx && !!file

  const downloadTextAsFile = (filename: string, content: string, mime = 'text/csv;charset=utf-8') => {
    const blob = new Blob([content], { type: mime })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  const downloadTransactionsTemplate = () => {
    downloadTextAsFile(
      'plantilla-caja-transacciones.csv',
      [
        'txn_date,amount,description,counterparty,balance_end',
        '2026-04-01,1250.00,"Cobro factura 123","Cliente X",15000.50',
        '2026-04-02,-85.40,"Pago proveedor","Proveedor Y",14915.10'
      ].join('\n')
    )
  }

  const downloadTribunalTemplate = () => {
    downloadTextAsFile(
      'plantilla-tribunal.csv',
      [
        'cliente,cif,gestor,minutas,irpf,ddcc,libros,carga_de_trabajo,pct_contabilidad,promedio,nas2024',
        'Cliente Demo,ESB12345678,Ana,12.5,SI,OK,SI,0.8,0.35,4.2,3',
        'Cliente Demo 2,ESA12345679,Carlos,0,NO,PENDIENTE,NO,0.2,0.10,2.1,0'
      ].join('\n')
    )
  }

  const downloadUniversalTemplate = () => {
    downloadTextAsFile(
      'plantilla-universal.csv',
      [
        'date,metric,category',
        '2026-04-01,1200.5,ventas',
        '2026-04-02,980.0,ventas',
        '2026-04-03,110.2,devoluciones'
      ].join('\n')
    )
  }

  const humanizeFileError = (raw: string) => {
    const msg = String(raw || '').trim()
    const lower = msg.toLowerCase()

    if (!msg) return { title: 'No se pudo procesar el fichero.', detail: '' }

    if (lower.includes('archivo demasiado grande') || lower.includes('payload too large')) {
      return {
        title: 'El fichero es demasiado grande.',
        detail: 'Recomendación: exporta un solo periodo, elimina pestañas/formatos extra o sube un XLSX con una sola tabla.'
      }
    }

    if (
      lower.includes('timeout') ||
      lower.includes('request_timeout') ||
      lower.includes('http_408') ||
      lower.includes('tardó demasiado') ||
      lower.includes('tardo demasiado')
    ) {
      return {
        title: 'La lectura tardó demasiado (timeout).',
        detail:
          'Recomendación: recorta filas/columnas, divide por periodos o (en XLSX) usa el modo guiado para apuntar a la tabla correcta.'
      }
    }

    if (lower.includes('csv malformado') || lower.includes('no tabular')) {
      return {
        title: 'El CSV no parece una tabla limpia (formato raro).',
        detail:
          'Suele pasar con varias tablas, filas de título sueltas o comillas/saltos de línea mal exportados. Prueba a subir el XLSX original o re-exporta como CSV UTF-8 (una sola tabla).'
      }
    }

    if (lower.includes('csv vacío') || lower.includes('csv vacio') || lower.includes('archivo vacio') || lower.includes('archivo vacío')) {
      return { title: 'El fichero está vacío.', detail: 'Exporta de nuevo asegurando que hay filas (no solo cabeceras).' }
    }

    if (lower.includes('no se detectaron encabezados') || lower.includes('csv sin encabezados') || lower.includes('sin encabezados')) {
      return {
        title: 'No se detectaron cabeceras.',
        detail: 'Asegura que la primera fila son nombres de columna. En XLSX, usa el modo guiado para elegir la fila de cabecera.'
      }
    }

    if (lower.includes('se esperaba un xlsx')) {
      return { title: 'El fichero no es un XLSX válido.', detail: 'Sube un .xlsx real o cambia a CSV.' }
    }

    if (lower.includes('columnas seleccionadas no existen')) {
      return {
        title: 'Las columnas elegidas no coinciden con el fichero.',
        detail: 'Vuelve a previsualizar y selecciona columnas de fecha e importe (o cambia a Universal).'
      }
    }

    return { title: msg, detail: '' }
  }

  const summarizeImport = (imp: ImportJob) => {
    const raw = String(imp.errorSummary || imp.lastError || '').trim()
    const lower = raw.toLowerCase()

    const missingTxnCols =
      lower.includes('missing required columns') || lower.includes('se esperan columnas') || lower.includes('expected columns')
    const mentionsTxnDate = lower.includes('txn_date') || lower.includes('txndate') || lower.includes('fecha')
    const mentionsAmount = lower.includes('amount') || lower.includes('importe')

    if (lower.includes('import file missing') || lower.includes('file missing')) {
      return {
        title: 'El archivo del import ya no existe (se limpió el storage).',
        fix: 'Vuelve a subir el fichero. “Reintentar” no funcionará sin el archivo.',
        canRetry: false,
        showTemplate: false,
        raw
      }
    }

    if (missingTxnCols && (mentionsTxnDate || mentionsAmount)) {
      return {
        title: 'No es un CSV de Caja (faltan columnas fecha/importe).',
        fix: 'Solución: usa “Universal” (cualquier tabla) o exporta una tabla con cabeceras: txn_date, amount (opcionales: description, counterparty, balance_end).',
        canRetry: true,
        showTemplate: true,
        raw
      }
    }

    if (lower.includes('0 filas válidas') || lower.includes('0 filas validas')) {
      return {
        title: 'No se detectaron filas válidas.',
        fix: 'Re-exporta el CSV como UTF-8 (una sola tabla) y revisa formato de fecha/importe.',
        canRetry: true,
        showTemplate: true,
        raw
      }
    }

    if (!raw) {
      return {
        title: imp.status === 'OK' ? 'Import correcto.' : 'Import pendiente/ejecutándose.',
        fix: '',
        canRetry: false,
        showTemplate: false,
        raw: ''
      }
    }

    return {
      title: raw,
      fix: '',
      canRetry: true,
      showTemplate: false,
      raw
    }
  }

  const importsForUi = useMemo(() => {
    let out = [...(data || [])]
    if (!showDeadImports) out = out.filter((i) => i.status !== 'DEAD')
    if (showOnlyFailedImports) out = out.filter((i) => i.status === 'ERROR' || i.status === 'DEAD' || i.status === 'WARNING')
    if (!showAllImports) out = out.slice(0, 6)
    return out
  }, [data, showAllImports, showDeadImports, showOnlyFailedImports])

  const tribunalHint = useMemo(() => {
    const headers = txPreview?.headers || []
    if (!headers.length) return { score: 0, isLikely: false }
    const norm = headers.map((h) => String(h || '').toLowerCase())
    const hits = (k: string) => norm.some((h) => h.includes(k))
    let score = 0
    if (hits('cif')) score += 2
    if (hits('gestor') || hits('manager')) score += 2
    if (hits('minuta') || hits('minutas')) score += 2
    if (hits('carga') || hits('carga_de_trabajo')) score += 2
    if (hits('irpf') || hits('ddcc') || hits('libros')) score += 2
    if (hits('cont_modelos') || hits('contabilidad')) score += 2
    return { score, isLikely: score >= 4 }
  }, [txPreview?.headers])

  const autoTarget = useMemo(() => {
    if (mode !== 'auto') return null as null | 'transactions' | 'universal' | 'tribunal'
    if (!file || !txPreview) return null
    if (tribunalHint.isLikely) return 'tribunal'
    const conf = Number(txPreview.confidence || 0)
    if (conf >= 0.6 && txnDateCol && amountCol) return 'transactions'
    return 'universal'
  }, [mode, file, txPreview, tribunalHint.isLikely, txnDateCol, amountCol])

  const previewOpts = useMemo(() => {
    return {
      sheetIndex: sheetIndex ?? undefined,
      headerRow: headerRow ?? undefined
    }
  }, [sheetIndex, headerRow])

  useEffect(() => {
    setXlsxPreview(null)
    setSheetIndex(null)
    setHeaderRow(null)
    setXlsxLoading(false)
    setShowUniversalGuided(false)
    setTxPreview(null)
    setTxnDateCol('')
    setAmountCol('')
    setDescriptionCol('')
    setCounterpartyCol('')
    setBalanceEndCol('')
  }, [file?.name])

  useEffect(() => {
    if (!canPreviewXlsx || !file) return
    let cancelled = false
    const t = window.setTimeout(async () => {
      setXlsxLoading(true)
      try {
        const prev = await previewUniversalXlsx(companyId as number, file, previewOpts)
        if (cancelled) return
        setXlsxPreview(prev)
        if (sheetIndex == null && prev.sheetIndex != null) setSheetIndex(prev.sheetIndex)
        if (headerRow == null && prev.headerRow != null) setHeaderRow(prev.headerRow)
      } catch (e: any) {
        if (!cancelled) {
          setTone('danger')
          const friendly = humanizeFileError(e?.message || e)
          setMessage([friendly.title, friendly.detail].filter(Boolean).join(' '))
        }
      } finally {
        if (!cancelled) setXlsxLoading(false)
      }
    }, 250)
    return () => {
      cancelled = true
      window.clearTimeout(t)
    }
  }, [canPreviewXlsx, file, companyId, previewOpts, sheetIndex, headerRow])

  useEffect(() => {
    if (!companyId || !file || (mode !== 'transactions' && mode !== 'auto')) return
    let cancelled = false
    const t = window.setTimeout(async () => {
      setTxPreviewLoading(true)
      try {
        const prev = await previewImport(companyId as number, file, { sheetIndex: sheetIndex ?? undefined, headerRow: headerRow ?? undefined })
        if (cancelled) return
        setTxPreview(prev)
        const s = prev.suggestedMapping || {}
        setTxnDateCol(String(s.txn_date || ''))
        setAmountCol(String(s.amount || ''))
        setDescriptionCol(String(s.description || ''))
        setCounterpartyCol(String(s.counterparty || ''))
        setBalanceEndCol(String(s.balance_end || ''))
      } catch (e: any) {
        if (!cancelled) {
          setTone('danger')
          const friendly = humanizeFileError(e?.message || e)
          setMessage([friendly.title, friendly.detail].filter(Boolean).join(' '))
        }
      } finally {
        if (!cancelled) setTxPreviewLoading(false)
      }
    }, 250)
    return () => {
      cancelled = true
      window.clearTimeout(t)
    }
  }, [companyId, file, mode, sheetIndex, headerRow])

  async function handleUpload() {
    if (!companyId || !file) return
    setMessage('')
    try {
      setUploading(true)
      if (!isAllowed) {
        setTone('danger')
        setMessage('Formato no soportado. Sube un CSV o XLSX.')
        return
      }

      if (mode === 'auto') {
        if (autoTarget === 'tribunal') {
          if (!hasGold) {
            setTone('danger')
            setMessage('Este fichero parece de Tribunal (cumplimiento), pero requiere plan GOLD/PLATINUM.')
            return
          }
          // Important: in AUTO, we should actually upload the file; otherwise Tribunal will keep showing previous dataset.
          await uploadTribunalImport(companyId, file)
          await queryClient.invalidateQueries({ queryKey: ['tribunal-summary', companyId] })
          await queryClient.invalidateQueries({ queryKey: ['tribunal-status', companyId] })
          setTone('success')
          setMessage('Archivo de Tribunal cargado. Te llevo a Cumplimiento (Tribunal).')
          toast.push({ tone: 'success', title: 'Auto', message: 'Fichero cargado en Tribunal.' })
          navigate('/tribunal')
          return
        }
        if (autoTarget === 'transactions') {
          if (!txnDateCol || !amountCol) {
            setTone('danger')
            setMessage('No se detectaron columnas de fecha/importe. Cambia a Universal o selecciona manualmente el modo Caja.')
            return
          }
          await uploadImportSmart(companyId, period, file, {
            txnDateCol,
            amountCol,
            descriptionCol: descriptionCol || undefined,
            counterpartyCol: counterpartyCol || undefined,
            balanceEndCol: balanceEndCol || undefined,
            sheetIndex: sheetIndex ?? undefined,
            headerRow: headerRow ?? undefined
          })
          await queryClient.invalidateQueries({ queryKey: ['imports', companyId] })
          setTone('success')
          setMessage('Import encolado (auto). Se procesará automáticamente y recalculará KPIs/alertas.')
          toast.push({ tone: 'success', title: 'Import (auto)', message: 'Import encolado para procesado.' })
          return
        }
        const opts =
          file.name.toLowerCase().endsWith('.xlsx')
            ? { sheetIndex: sheetIndex ?? xlsxPreview?.sheetIndex ?? undefined, headerRow: headerRow ?? xlsxPreview?.headerRow ?? undefined }
            : {}
        await uploadUniversalImport(companyId, file, opts)
        await queryClient.invalidateQueries({ queryKey: ['universal-summary', companyId] })
        await queryClient.invalidateQueries({ queryKey: ['universal-suggestions', companyId] })
        setTone('success')
        setMessage('Archivo analizado en Universal (auto). Ya puedes ver columnas, insights y el asesor.')
        toast.push({ tone: 'success', title: 'Universal (auto)', message: 'Archivo analizado correctamente.' })
        navigate('/universal')
        return
      }

      if (mode === 'universal') {
        const opts =
          file.name.toLowerCase().endsWith('.xlsx')
            ? { sheetIndex: sheetIndex ?? xlsxPreview?.sheetIndex ?? undefined, headerRow: headerRow ?? xlsxPreview?.headerRow ?? undefined }
            : {}
        await uploadUniversalImport(companyId, file, opts)
        await queryClient.invalidateQueries({ queryKey: ['universal-summary', companyId] })
        await queryClient.invalidateQueries({ queryKey: ['universal-suggestions', companyId] })
        setTone('success')
        setMessage('Archivo analizado en Universal. Ya puedes ver columnas, insights y el asesor.')
        toast.push({ tone: 'success', title: 'Universal', message: 'Archivo analizado correctamente.' })
        navigate('/universal')
        return
      }

      if (!txnDateCol || !amountCol) {
        setTone('danger')
        setMessage('Selecciona al menos la columna de fecha y la de importe para calcular caja.')
        return
      }

      await uploadImportSmart(companyId, period, file, {
        txnDateCol,
        amountCol,
        descriptionCol: descriptionCol || undefined,
        counterpartyCol: counterpartyCol || undefined,
        balanceEndCol: balanceEndCol || undefined,
        sheetIndex: sheetIndex ?? undefined,
        headerRow: headerRow ?? undefined
      })
      await queryClient.invalidateQueries({ queryKey: ['imports', companyId] })
      setTone('success')
      setMessage('Import encolado. Se procesará automáticamente y recalculará KPIs/alertas.')
      toast.push({ tone: 'success', title: 'Import', message: 'CSV subido y encolado para procesado.' })
    } catch (err: any) {
      setTone('danger')
      const friendly = humanizeFileError(err?.message || err)
      setMessage([friendly.title, friendly.detail].filter(Boolean).join(' '))
      toast.push({ tone: 'danger', title: 'Error', message: friendly.title })
      if ((mode === 'universal' || mode === 'auto') && isXlsx) {
        setShowUniversalGuided(true)
        toast.push({
          tone: 'info',
          title: 'Modo guiado',
          message: 'Prueba a seleccionar hoja y fila de cabecera para que Universal lea la tabla correcta.'
        })
      }
      if ((mode === 'transactions' || mode === 'auto') && String(err?.message || err).toLowerCase().includes('formato incorrecto')) {
        toast.push({ tone: 'info', title: 'Tip', message: 'Si no es un fichero de transacciones, usa el modo Universal.' })
      }
    } finally {
      setUploading(false)
    }
  }

  async function handleRetry(importId: number) {
    if (!companyId) return
    try {
      await retryImport(companyId, importId)
      await queryClient.invalidateQueries({ queryKey: ['imports', companyId] })
      toast.push({ tone: 'success', title: 'Reintento', message: `Import ${importId} reencolado.` })
    } catch (err: any) {
      toast.push({ tone: 'danger', title: 'Error', message: err?.message || 'No se pudo reencolar el import.' })
    }
  }

  return (
    <div>
      <PageHeader
        title="Cargar datos"
        subtitle="Modo AUTO recomendado: sube cualquier fichero y EnterpriseIQ te guía (Caja/Universal/Tribunal)."
        actions={
          <>
            <Button size="sm" variant="ghost" onClick={() => navigate(`/guides?module=${guidesModule}`)}>
              Guías de carga
            </Button>
            <span className="badge">
              {mode === 'auto'
                ? 'AUTO • detecta objetivo'
                : mode === 'transactions'
                ? 'Caja • txn_date + amount'
                : 'Universal • cualquier estructura'}
            </span>
          </>
        }
      />

      <div className="section">
        <div className="segmented" role="tablist" aria-label="Modo de carga">
          <Button
            type="button"
            variant={mode === 'auto' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setMode('auto')}
            aria-selected={mode === 'auto'}
          >
            Auto (recomendado)
          </Button>
          <Button
            type="button"
            variant={mode === 'transactions' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setMode('transactions')}
            aria-selected={mode === 'transactions'}
          >
            Caja (transacciones)
          </Button>
          <Button
            type="button"
            variant={mode === 'universal' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setMode('universal')}
            aria-selected={mode === 'universal'}
          >
            Universal (cualquier CSV/XLSX)
          </Button>
        </div>

        {mode === 'auto' ? (
          <div className="grid" style={{ marginTop: 14 }}>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Auto</h3>
              <p className="hero-sub">Sube un fichero y te sugerimos el destino: Caja (movimientos), Universal (cualquier dataset) o Tribunal.</p>
            </div>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Consejo</h3>
              <p className="hero-sub">Si tu fichero no son movimientos bancarios, casi siempre encaja mejor en Universal.</p>
            </div>
          </div>
        ) : mode === 'transactions' ? (
          <div className="grid" style={{ marginTop: 14 }}>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Formato (Caja)</h3>
              <p className="hero-sub">
                Columnas: <strong>txn_date</strong>, <strong>amount</strong>. Opcionales: <strong>description</strong>,{' '}
                <strong>counterparty</strong>, <strong>balance_end</strong>.
              </p>
            </div>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Buenas prácticas</h3>
              <p className="hero-sub">Un periodo por fichero (YYYY-MM). Fechas ISO (YYYY-MM-DD) para evitar filas descartadas.</p>
            </div>
          </div>
        ) : (
          <div className="grid" style={{ marginTop: 14 }}>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>Formato (Universal)</h3>
              <p className="hero-sub">
                Sube cualquier CSV/XLSX (presupuestos, salarios, inventario, ventas…). No requiere columnas fijas: EnterpriseIQ detecta tipos y genera
                resumen/insights.
              </p>
            </div>
            <div className="card soft">
              <h3 style={{ marginTop: 0 }}>XLSX (guiado)</h3>
              <p className="hero-sub">
                Si el encabezado no está en la primera fila o hay varias hojas/tablas, usa el modo guiado para elegir hoja y fila de cabecera.
              </p>
            </div>
          </div>
        )}
      </div>

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Qué debo subir (según módulo)</h3>
        <div className="grid" style={{ gap: 10 }}>
          <div className="card soft">
            <div className="mini-row" style={{ justifyContent: 'space-between' }}>
              <strong>Caja</strong>
              <a className="btn btn-ghost btn-sm" href="/samples/plantilla-caja-transacciones.csv" download>
                Descargar ejemplo
              </a>
            </div>
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Para KPIs/alertas de tesorería. Sube movimientos (banco/caja) del periodo.
            </div>
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Mínimo: <strong>txn_date</strong> y <strong>amount</strong> (mejor si incluye descripción/contrapartida).
            </div>
          </div>

          <div className="card soft">
            <div className="mini-row" style={{ justifyContent: 'space-between' }}>
              <strong>Tribunal</strong>
              <a className="btn btn-ghost btn-sm" href="/samples/plantilla-tribunal.csv" download>
                Descargar ejemplo
              </a>
            </div>
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Para cumplimiento/seguimiento cartera. Requiere columnas <strong>cliente</strong> y <strong>cif</strong>.
            </div>
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Opcionales típicas: gestor, minutas, IRPF/DDCC/Libros, carga de trabajo, % contabilidad, actividad por año (nas2024…).
            </div>
          </div>

          <div className="card soft">
            <div className="mini-row" style={{ justifyContent: 'space-between' }}>
              <strong>Universal</strong>
              <a className="btn btn-ghost btn-sm" href="/samples/plantilla-universal.csv" download>
                Descargar ejemplo
              </a>
            </div>
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Para datasets no bancarios (presupuesto, ventas, inventario…). No exige columnas fijas.
            </div>
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Ideal: una tabla limpia con cabeceras. Si es XLSX con varias hojas, usa el modo guiado.
            </div>
          </div>

          <div className="card soft">
            <div className="mini-row" style={{ justifyContent: 'space-between' }}>
              <strong>Presupuesto</strong>
              <a className="btn btn-ghost btn-sm" href="/samples/presupuesto-ejemplo.xlsx" download>
                Descargar ejemplo
              </a>
            </div>
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Para análisis anual y PDF con recomendaciones (drivers, meses a cero, concentración).
            </div>
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Sube el XLSX por Universal y luego valida en <strong>Presupuesto</strong> con “Preview long”.
            </div>
          </div>
        </div>
      </div>

      {mode === 'transactions' && file && txPreview && Number(txPreview.confidence || 0) < 0.4 ? (
        <div className="section">
          <Alert tone="warning" title="Este fichero quizá no es de transacciones">
            La detección de columnas tiene poca confianza. Si es un presupuesto / ventas / inventario, usa el modo Universal.
            <div style={{ marginTop: 10 }}>
              <Button size="sm" variant="secondary" onClick={() => setMode('universal')}>
                Cambiar a Universal
              </Button>
            </div>
          </Alert>
        </div>
      ) : null}

      {mode === 'auto' && file && txPreview ? (
        <div className="section">
          <Alert tone="info" title="Sugerencia (auto)">
            {autoTarget === 'transactions'
              ? 'Parece un fichero de transacciones (Caja).'
              : autoTarget === 'tribunal'
              ? 'Parece un fichero de Tribunal (cumplimiento).'
              : 'Parece un dataset genérico. Recomendado: Universal.'}
            <div className="upload-hint" style={{ marginTop: 8 }}>
              Confianza transacciones: {Math.round(Number(txPreview.confidence || 0) * 100)}% · Señales Tribunal: {tribunalHint.score}
            </div>
            <div style={{ marginTop: 10, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <Button size="sm" variant={autoTarget === 'transactions' ? 'secondary' : 'ghost'} onClick={() => setMode('transactions')}>
                Usar Caja
              </Button>
              <Button size="sm" variant={autoTarget === 'universal' ? 'secondary' : 'ghost'} onClick={() => setMode('universal')}>
                Usar Universal
              </Button>
              <Button
                size="sm"
                variant={autoTarget === 'tribunal' ? 'secondary' : 'ghost'}
                onClick={() => navigate('/tribunal')}
                disabled={!hasGold}
              >
                Ir a Tribunal
              </Button>
            </div>
          </Alert>
        </div>
      ) : null}

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Subida</h3>
        {!companyId ? (
          <Alert tone="warning" title="Falta seleccionar empresa">
            Selecciona una empresa arriba para subir el fichero.
          </Alert>
        ) : null}
        <div className="upload-row">
          {mode === 'transactions' ? (
            <input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="YYYY-MM" inputMode="numeric" />
          ) : null}
          <input type="file" accept=".csv,.xlsx" onChange={(e) => setFile(e.target.files?.[0] || null)} />
          {(mode === 'universal' || mode === 'auto') && isXlsx ? (
            <details
              style={{ width: '100%' }}
              open={showUniversalGuided}
              onToggle={(e) => setShowUniversalGuided((e.target as HTMLDetailsElement).open)}
            >
              <summary className="upload-hint" style={{ cursor: 'pointer' }}>
                Modo guiado XLSX (hoja + cabecera)
                {xlsxLoading ? ' • previsualizando…' : ''}
                {xlsxPreview?.headers?.length ? ` • ${xlsxPreview.headers.length} columnas` : ''}
              </summary>
              <div className="upload-row" style={{ marginTop: 10, alignItems: 'flex-end' }}>
                <label style={{ display: 'grid', gap: 6 }}>
                  <span className="upload-hint">Hoja</span>
                  <select
                    value={sheetIndex ?? xlsxPreview?.sheetIndex ?? 0}
                    onChange={(e) => setSheetIndex(Number(e.target.value))}
                    disabled={!xlsxPreview?.sheets?.length}
                  >
                    {(xlsxPreview?.sheets || []).map((name, idx) => (
                      <option key={name} value={idx}>
                        {idx}: {name}
                      </option>
                    ))}
                  </select>
                </label>
                <label style={{ display: 'grid', gap: 6 }}>
                  <span className="upload-hint">Fila cabecera (1-based)</span>
                  <input
                    value={headerRow ?? xlsxPreview?.headerRow ?? ''}
                    onChange={(e) => setHeaderRow(e.target.value === '' ? null : Number(e.target.value))}
                    placeholder="Ej: 3"
                    inputMode="numeric"
                    style={{ width: 160 }}
                  />
                </label>
                <span className="upload-hint" style={{ marginBottom: 6 }}>
                  Tip: pon la fila donde están ENERO…DICIEMBRE / txn_date…
                </span>
              </div>

              {xlsxPreview?.headers?.length ? (
                <div className="upload-hint" style={{ marginTop: 10 }}>
                  Cabeceras detectadas: {xlsxPreview.headers.slice(0, 8).join(' · ')}
                  {xlsxPreview.headers.length > 8 ? ' · …' : ''}
                </div>
              ) : null}

              {xlsxPreview?.sampleRows?.length ? (
                <div style={{ marginTop: 10, overflow: 'auto' }}>
                  <table className="table">
                    <thead>
                      <tr>
                        {(xlsxPreview.headers || []).slice(0, 8).map((h) => (
                          <th key={`xh-${h}`}>{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {(xlsxPreview.sampleRows || []).slice(0, 6).map((r, idx) => (
                        <tr key={`xr-${idx}`}>
                          {r.slice(0, 8).map((v, c) => (
                            <td key={`xc-${idx}-${c}`} className="upload-hint">
                              {String(v || '').slice(0, 60)}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="empty" style={{ marginTop: 10 }}>
                  {xlsxLoading ? 'Previsualizando…' : 'No se pudieron leer filas de muestra.'}
                </div>
              )}
            </details>
          ) : null}
          {mode === 'transactions' && isXlsx ? (
            <details style={{ width: '100%' }}>
              <summary className="upload-hint" style={{ cursor: 'pointer' }}>
                Ajustes XLSX (opcional)
              </summary>
              <div className="upload-row" style={{ marginTop: 10 }}>
                <input
                  value={sheetIndex ?? ''}
                  onChange={(e) => setSheetIndex(e.target.value === '' ? null : Number(e.target.value))}
                  placeholder="sheetIndex"
                  inputMode="numeric"
                  style={{ width: 140 }}
                />
                <input
                  value={headerRow ?? ''}
                  onChange={(e) => setHeaderRow(e.target.value === '' ? null : Number(e.target.value))}
                  placeholder="headerRow"
                  inputMode="numeric"
                  style={{ width: 140 }}
                />
                {txPreviewLoading ? <span className="upload-hint">Analizando…</span> : null}
                {txPreview?.confidence != null ? (
                  <span className="upload-hint">Confianza: {(txPreview.confidence * 100).toFixed(0)}%</span>
                ) : null}
              </div>
            </details>
          ) : null}
          <Button onClick={handleUpload} disabled={!companyId || !file || uploading} loading={uploading}>
            Subir fichero
          </Button>
        </div>
        {file && isCsv ? (
          <div className="upload-hint" style={{ marginTop: 10 }}>
            Si es un CSV “de Excel” con varias tablas/gráficas, suele romperse al exportar. Mejor sube el <strong>XLSX original</strong> y usa el modo
            guiado para elegir cabecera.
          </div>
        ) : null}
        {message ? (
          <div style={{ marginTop: 12 }}>
            <Alert tone={tone}>{message}</Alert>
          </div>
        ) : null}
        {mode === 'transactions' ? (
          <div className="upload-hint" style={{ marginTop: 10 }}>
            Si tu archivo no es de transacciones (por ejemplo presupuestos o datasets externos), usa <strong>Universal</strong>.
          </div>
        ) : (
          <div className="upload-hint" style={{ marginTop: 10 }}>
            Universal no recalcula KPIs de caja automáticamente; sirve para análisis exploratorio y asesoramiento.
          </div>
        )}
      </div>

      {mode === 'transactions' && companyId && file && txPreview ? (
        <div className="card section">
          <h3 style={{ marginTop: 0 }}>Asistente de mapeo (Caja)</h3>
          <div className="upload-hint">
            Selecciona qué columnas significan <strong>fecha</strong> e <strong>importe</strong>. El resto es opcional.
          </div>
          <div className="upload-row" style={{ marginTop: 12 }}>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Fecha (txn_date)</span>
              <select value={txnDateCol} onChange={(e) => setTxnDateCol(e.target.value)}>
                <option value="">—</option>
                {txPreview.headers.map((h) => (
                  <option key={`d-${h}`} value={h}>
                    {h}
                  </option>
                ))}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Importe (amount)</span>
              <select value={amountCol} onChange={(e) => setAmountCol(e.target.value)}>
                <option value="">—</option>
                {txPreview.headers.map((h) => (
                  <option key={`a-${h}`} value={h}>
                    {h}
                  </option>
                ))}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Descripción</span>
              <select value={descriptionCol} onChange={(e) => setDescriptionCol(e.target.value)}>
                <option value="">(ninguna)</option>
                {txPreview.headers.map((h) => (
                  <option key={`ds-${h}`} value={h}>
                    {h}
                  </option>
                ))}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Contrapartida</span>
              <select value={counterpartyCol} onChange={(e) => setCounterpartyCol(e.target.value)}>
                <option value="">(ninguna)</option>
                {txPreview.headers.map((h) => (
                  <option key={`cp-${h}`} value={h}>
                    {h}
                  </option>
                ))}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 6 }}>
              <span className="upload-hint">Saldo fin</span>
              <select value={balanceEndCol} onChange={(e) => setBalanceEndCol(e.target.value)}>
                <option value="">(ninguna)</option>
                {txPreview.headers.map((h) => (
                  <option key={`be-${h}`} value={h}>
                    {h}
                  </option>
                ))}
              </select>
            </label>
          </div>

          {txPreview.sampleRows?.length ? (
            <div style={{ marginTop: 14, overflow: 'auto' }}>
              <table className="table">
                <thead>
                  <tr>
                    {txPreview.headers.slice(0, 8).map((h) => (
                      <th key={`h-${h}`}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {txPreview.sampleRows.slice(0, 6).map((r, idx) => (
                    <tr key={`r-${idx}`}>
                      {r.slice(0, 8).map((v, c) => (
                        <td key={`c-${idx}-${c}`} className="upload-hint">
                          {String(v || '').slice(0, 60)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="empty" style={{ marginTop: 12 }}>
              No se pudieron leer filas de muestra.
            </div>
          )}
        </div>
      ) : null}

      <div className="card section">
        <h3 style={{ marginTop: 0 }}>Historial de imports</h3>
        {!data?.length ? (
          <div className="empty">No hay imports todavía.</div>
        ) : (
          <>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 10, alignItems: 'center' }}>
              <label className="upload-hint" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <input type="checkbox" checked={showAllImports} onChange={(e) => setShowAllImports(e.target.checked)} />
                Mostrar más (hasta 6)
              </label>
              <label className="upload-hint" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <input
                  type="checkbox"
                  checked={showOnlyFailedImports}
                  onChange={(e) => setShowOnlyFailedImports(e.target.checked)}
                />
                Solo con problemas
              </label>
              <label className="upload-hint" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <input type="checkbox" checked={showDeadImports} onChange={(e) => setShowDeadImports(e.target.checked)} />
                Incluir DEAD (técnico)
              </label>
              <div style={{ flex: 1 }} />
              <Button size="sm" variant="secondary" onClick={downloadTransactionsTemplate}>
                Descargar plantilla Caja (CSV)
              </Button>
            </div>

            <table className="table">
              <thead>
                <tr>
                  <th>Periodo</th>
                  <th>Estado</th>
                  <th>Fichero</th>
                  <th>Qué pasó</th>
                  <th>Qué hacer</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {importsForUi.map((imp: ImportJob) => {
                  const info = summarizeImport(imp)
                  const canRetry = (imp.status === 'ERROR' || imp.status === 'DEAD') && info.canRetry && !!imp.storageRef
                  return (
                    <tr key={imp.id}>
                      <td>
                        <div style={{ fontWeight: 700 }}>{imp.period}</div>
                        <div className="upload-hint">{new Date(imp.createdAt).toLocaleString()}</div>
                      </td>
                      <td>
                        <span
                          className={`badge ${
                            imp.status === 'OK'
                              ? 'ok'
                              : imp.status === 'WARNING'
                              ? 'warn'
                              : imp.status === 'ERROR' || imp.status === 'DEAD'
                              ? 'err'
                              : ''
                          }`}
                        >
                          {imp.status}
                        </span>
                        <div className="upload-hint">
                          {typeof imp.attempts === 'number' || typeof imp.maxAttempts === 'number'
                            ? `Intentos: ${imp.attempts ?? 0}/${imp.maxAttempts ?? 3}`
                            : null}
                        </div>
                      </td>
                      <td className="upload-hint">{imp.originalFilename || imp.storageRef || '-'}</td>
                      <td style={{ maxWidth: 460 }}>
                        <div>{info.title}</div>
                        {info.raw ? (
                          <details style={{ marginTop: 6 }}>
                            <summary className="upload-hint">Detalles técnicos</summary>
                            <div className="upload-hint" style={{ whiteSpace: 'pre-wrap' }}>
                              {info.raw}
                            </div>
                          </details>
                        ) : null}
                      </td>
                      <td style={{ maxWidth: 420 }} className="upload-hint">
                        {info.fix || '-'}
                        {info.showTemplate ? (
                          <div style={{ marginTop: 8 }}>
                            <Button size="sm" variant="secondary" onClick={downloadTransactionsTemplate}>
                              Descargar plantilla (CSV)
                            </Button>
                          </div>
                        ) : null}
                      </td>
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                        {imp.status === 'ERROR' || imp.status === 'DEAD' ? (
                          <Button size="sm" disabled={!canRetry} onClick={() => handleRetry(imp.id)}>
                            Reintentar
                          </Button>
                        ) : null}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </>
        )}
      </div>
    </div>
  )
}

