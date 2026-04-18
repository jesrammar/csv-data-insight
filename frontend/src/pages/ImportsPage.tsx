import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getImports,
  getCompanyMapping,
  getImportQuality,
  previewImport,
  previewUniversalXlsx,
  retryImport,
  type ImportJob,
  type ImportPreviewDto,
  type ImportQualityDto,
  uploadImportSmart,
  uploadTribunalImport,
  uploadUniversalImport,
  type UniversalXlsxPreview
} from '../api'
import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { useCompanySelection } from '../hooks/useCompany'
import PageHeader from '../components/ui/PageHeader'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'
import { useToast } from '../components/ui/ToastProvider'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { getWorkPeriod, nowYm } from '../utils/workPeriod'

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

  useEffect(() => {
    if (mode === 'transactions') setGuideModule('caja')
    if (mode === 'universal') setGuideModule('universal')
  }, [mode])

  const [period, setPeriod] = useState(() => getWorkPeriod(companyId) || nowYm())
  const [file, setFile] = useState<File | null>(null)
  const [batchFiles, setBatchFiles] = useState<File[]>([])
  const [message, setMessage] = useState('')
  const [tone, setTone] = useState<'info' | 'success' | 'danger'>('info')

  const [uploading, setUploading] = useState(false)
  const [batchUploading, setBatchUploading] = useState(false)
  const [xlsxPreview, setXlsxPreview] = useState<UniversalXlsxPreview | null>(null)
  const [xlsxLoading, setXlsxLoading] = useState(false)
  const [sheetIndex, setSheetIndex] = useState<number | null>(null)
  const [headerRow, setHeaderRow] = useState<number | null>(null)
  const [showUniversalGuided, setShowUniversalGuided] = useState(false)
  const [showXlsxSample, setShowXlsxSample] = useState(false)

  const [txPreview, setTxPreview] = useState<ImportPreviewDto | null>(null)
  const [txPreviewLoading, setTxPreviewLoading] = useState(false)
  const [txnDateCol, setTxnDateCol] = useState('')
  const [amountCol, setAmountCol] = useState('')
  const [descriptionCol, setDescriptionCol] = useState('')
  const [counterpartyCol, setCounterpartyCol] = useState('')
  const [balanceEndCol, setBalanceEndCol] = useState('')

  const { data: savedSmartMapping } = useQuery({
    queryKey: ['company-mapping', companyId, 'imports.smart'],
    queryFn: () => getCompanyMapping(companyId as number, 'imports.smart'),
    enabled: !!companyId
  })

  const [showAllImports, setShowAllImports] = useState(false)
  const [showDeadImports, setShowDeadImports] = useState(false)
  const [showOnlyFailedImports, setShowOnlyFailedImports] = useState(false)
  const [guideModule, setGuideModule] = useState<'caja' | 'tribunal' | 'universal' | 'presupuesto'>('caja')
  const [qualityOpenId, setQualityOpenId] = useState<number | null>(null)

  const isCsv = !file ? true : file.name.toLowerCase().endsWith('.csv')
  const isXlsx = !file ? false : file.name.toLowerCase().endsWith('.xlsx')
  const isAllowed = !file ? true : isCsv || isXlsx
  const canPreviewXlsx = !!companyId && (mode === 'universal' || mode === 'auto') && isXlsx && !!file
  const isBatch = batchFiles.length > 1
  const batchAbortRef = useRef<{ abort: boolean }>({ abort: false })

  type BatchItem = {
    file: File
    period: string | null
    status: 'pending' | 'uploading' | 'done' | 'error' | 'skipped'
    target?: 'transactions' | 'universal' | 'tribunal' | null
    message?: string
  }

  const [batchItems, setBatchItems] = useState<BatchItem[]>([])

  function nowYmMinus(months: number) {
    const d = new Date()
    const ym = new Date(d.getFullYear(), d.getMonth() - months, 1)
    const y = ym.getFullYear()
    const m = String(ym.getMonth() + 1).padStart(2, '0')
    return `${y}-${m}`
  }

  function inferPeriodFromFilename(name: string): string | null {
    const n = String(name || '')
    // Common patterns: 2026-03, 202603, 03-2026, 03_2026, 2026_03
    const m1 = n.match(/(20\d{2})[-_.](0[1-9]|1[0-2])/)
    if (m1) return `${m1[1]}-${m1[2]}`
    const m2 = n.match(/(20\d{2})(0[1-9]|1[0-2])/)
    if (m2) return `${m2[1]}-${m2[2]}`
    const m3 = n.match(/(0[1-9]|1[0-2])[-_.](20\d{2})/)
    if (m3) return `${m3[2]}-${m3[1]}`
    return null
  }

  function tribunalHintFromHeaders(headers: any[] | undefined) {
    const list = Array.isArray(headers) ? headers : []
    if (!list.length) return { score: 0, isLikely: false }
    const norm = list.map((h) => String(h || '').toLowerCase())
    const hits = (k: string) => norm.some((h) => h.includes(k))
    let score = 0
    if (hits('cif')) score += 2
    if (hits('gestor') || hits('manager')) score += 2
    if (hits('minuta') || hits('minutas')) score += 2
    if (hits('carga') || hits('carga_de_trabajo')) score += 2
    if (hits('irpf') || hits('ddcc') || hits('libros')) score += 2
    if (hits('cont_modelos') || hits('contabilidad')) score += 2
    return { score, isLikely: score >= 4 }
  }

  useEffect(() => {
    if (!companyId) return
    setPeriod(getWorkPeriod(companyId) || nowYm())
  }, [companyId])

  const quickGuide = useMemo(() => {
    switch (guideModule) {
      case 'tribunal':
        return {
          title: 'Tribunal (cartera)',
          href: '/samples/plantilla-tribunal.csv',
          bullets: [
            <>
              Mínimo: <strong>cliente</strong> y <strong>cif</strong>.
            </>,
            <>Útil para seguimiento de cartera y cumplimiento.</>,
            <>Tip: si no tienes esas columnas, usa Universal.</>
          ]
        }
      case 'universal':
        return {
          title: 'Universal (cualquier tabla)',
          href: '/samples/plantilla-universal.csv',
          bullets: [
            <>Ideal: una sola tabla con cabeceras claras.</>,
            <>No exige columnas fijas: detecta tipos y genera insights.</>,
            <>En XLSX con varias hojas/tablas, usa “modo guiado”.</>
          ]
        }
      case 'presupuesto':
        return {
          title: 'Presupuesto (XLSX)',
          href: '/samples/presupuesto-ejemplo.xlsx',
          bullets: [
            <>Sube el XLSX por Universal (si tiene varias hojas, usa modo guiado).</>,
            <>
              Luego ve al dashboard <strong>Presupuesto</strong> para validar “long” e insights.
            </>,
            <>Tip: si hay meses a cero, suele ser cabecera/fila incorrecta.</>
          ]
        }
      default:
        return {
          title: 'Caja (transacciones)',
          href: '/samples/plantilla-caja-transacciones.csv',
          bullets: [
            <>
              Mínimo: <strong>txn_date</strong> y <strong>amount</strong>.
            </>,
            <>Un periodo por fichero (YYYY-MM). Fechas ISO (YYYY-MM-DD).</>,
            <>Si viene “de Excel” con varias tablas/gráficas, sube el XLSX por Universal.</>
          ]
        }
    }
  }, [guideModule])

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
    out = out.slice(0, showAllImports ? 10 : 3)
    return out
  }, [data, showAllImports, showDeadImports, showOnlyFailedImports])

  const latestImport = useMemo(() => {
    const list = [...(data || [])]
    list.sort((a, b) => {
      const at = new Date(a.createdAt).getTime()
      const bt = new Date(b.createdAt).getTime()
      return bt - at
    })
    return list[0] as ImportJob | undefined
  }, [data])

  const {
    data: quality,
    isFetching: qualityLoading,
    error: qualityError
  } = useQuery({
    queryKey: ['import-quality', companyId, qualityOpenId],
    queryFn: () => getImportQuality(companyId as number, qualityOpenId as number),
    enabled: !!companyId && !!qualityOpenId
  })

  const qualitySummary = (q: ImportQualityDto | null | undefined) => {
    if (!q) return null
    const issues = Array.isArray(q.issues) ? q.issues : []
    const high = issues.filter((i) => String(i.severity).toUpperCase() === 'HIGH').length
    const med = issues.filter((i) => String(i.severity).toUpperCase() === 'MEDIUM').length
    const low = issues.filter((i) => String(i.severity).toUpperCase() === 'LOW').length
    const badge = high ? 'err' : med ? 'warn' : low ? 'warn' : 'ok'
    const label = high ? `${high} crítico` : med ? `${med} medio` : low ? `${low} leve` : 'OK'
    return { badge, label }
  }

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
    setBatchUploading(false)
    batchAbortRef.current.abort = false
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

        // Si hay un mapeo guardado por empresa y encaja con las cabeceras, priorizarlo.
        try {
          const m: any = savedSmartMapping || {}
          const headers = Array.isArray(prev.headers) ? prev.headers : []
          const has = (col: any) => !!col && headers.includes(String(col))
          if (sheetIndex == null && m.sheetIndex != null) setSheetIndex(Number(m.sheetIndex))
          if (headerRow == null && m.headerRow != null) setHeaderRow(Number(m.headerRow))
          if (has(m.txnDateCol)) setTxnDateCol(String(m.txnDateCol))
          if (has(m.amountCol)) setAmountCol(String(m.amountCol))
          if (has(m.descriptionCol)) setDescriptionCol(String(m.descriptionCol))
          if (has(m.counterpartyCol)) setCounterpartyCol(String(m.counterpartyCol))
          if (has(m.balanceEndCol)) setBalanceEndCol(String(m.balanceEndCol))
        } catch {}
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
  }, [companyId, file, mode, sheetIndex, headerRow, savedSmartMapping])

  async function uploadOneBatchFile(entry: BatchItem) {
    if (!companyId) throw new Error('Falta empresa')

    const currentPeriod = entry.period || (mode === 'transactions' ? period : null)

    const resolveMappingsForHeaders = (headers: any[], suggested: any) => {
      const headerList = Array.isArray(headers) ? headers.map((h) => String(h || '')) : []
      const has = (col: any) => !!col && headerList.includes(String(col))

      // Start with suggested mapping
      let out = {
        txnDateCol: String(suggested?.txn_date || suggested?.txnDateCol || ''),
        amountCol: String(suggested?.amount || suggested?.amountCol || ''),
        descriptionCol: String(suggested?.description || suggested?.descriptionCol || ''),
        counterpartyCol: String(suggested?.counterparty || suggested?.counterpartyCol || ''),
        balanceEndCol: String(suggested?.balance_end || suggested?.balanceEndCol || '')
      }

      // Company saved mapping has priority if it matches headers
      try {
        const m: any = savedSmartMapping || {}
        if (has(m.txnDateCol)) out.txnDateCol = String(m.txnDateCol)
        if (has(m.amountCol)) out.amountCol = String(m.amountCol)
        if (has(m.descriptionCol)) out.descriptionCol = String(m.descriptionCol)
        if (has(m.counterpartyCol)) out.counterpartyCol = String(m.counterpartyCol)
        if (has(m.balanceEndCol)) out.balanceEndCol = String(m.balanceEndCol)
      } catch {}

      // Finally, UI selection (if present and matches) overrides for batch uploads
      if (has(txnDateCol)) out.txnDateCol = String(txnDateCol)
      if (has(amountCol)) out.amountCol = String(amountCol)
      if (has(descriptionCol)) out.descriptionCol = String(descriptionCol)
      if (has(counterpartyCol)) out.counterpartyCol = String(counterpartyCol)
      if (has(balanceEndCol)) out.balanceEndCol = String(balanceEndCol)

      return out
    }

    if (mode === 'universal') {
      const opts =
        entry.file.name.toLowerCase().endsWith('.xlsx')
          ? { sheetIndex: sheetIndex ?? xlsxPreview?.sheetIndex ?? undefined, headerRow: headerRow ?? xlsxPreview?.headerRow ?? undefined }
          : {}
      await uploadUniversalImport(companyId, entry.file, opts)
      return { target: 'universal' as const }
    }

    if (mode === 'transactions') {
      if (!currentPeriod) throw new Error('Falta periodo (YYYY-MM)')
      const prev = await previewImport(companyId, entry.file, { sheetIndex: sheetIndex ?? undefined, headerRow: headerRow ?? undefined })
      const m = resolveMappingsForHeaders(prev.headers || [], prev.suggestedMapping || {})
      if (!m.txnDateCol || !m.amountCol) {
        throw new Error('No se detectaron columnas de fecha/importe. Ajusta el mapeo o usa Universal.')
      }
      await uploadImportSmart(companyId, currentPeriod, entry.file, {
        txnDateCol: m.txnDateCol,
        amountCol: m.amountCol,
        descriptionCol: m.descriptionCol || undefined,
        counterpartyCol: m.counterpartyCol || undefined,
        balanceEndCol: m.balanceEndCol || undefined,
        sheetIndex: sheetIndex ?? undefined,
        headerRow: headerRow ?? undefined
      })
      return { target: 'transactions' as const }
    }

    // AUTO: attempt to classify each file with previewImport
    const prev = await previewImport(companyId, entry.file, { sheetIndex: sheetIndex ?? undefined, headerRow: headerRow ?? undefined })
    const tribunal = tribunalHintFromHeaders(prev.headers || [])
    if (tribunal.isLikely) {
      if (!hasGold) {
        throw new Error('Este fichero parece de Tribunal, pero requiere plan GOLD/PLATINUM.')
      }
      await uploadTribunalImport(companyId, entry.file)
      return { target: 'tribunal' as const }
    }

    const conf = Number((prev as any).confidence || 0)
    const m = resolveMappingsForHeaders(prev.headers || [], (prev as any).suggestedMapping || {})
    if (conf >= 0.6 && m.txnDateCol && m.amountCol) {
      const p = currentPeriod || nowYmMinus(1)
      await uploadImportSmart(companyId, p, entry.file, {
        txnDateCol: m.txnDateCol,
        amountCol: m.amountCol,
        descriptionCol: m.descriptionCol || undefined,
        counterpartyCol: m.counterpartyCol || undefined,
        balanceEndCol: m.balanceEndCol || undefined,
        sheetIndex: sheetIndex ?? undefined,
        headerRow: headerRow ?? undefined
      })
      return { target: 'transactions' as const }
    }

    const opts =
      entry.file.name.toLowerCase().endsWith('.xlsx')
        ? { sheetIndex: sheetIndex ?? xlsxPreview?.sheetIndex ?? undefined, headerRow: headerRow ?? xlsxPreview?.headerRow ?? undefined }
        : {}
    await uploadUniversalImport(companyId, entry.file, opts)
    return { target: 'universal' as const }
  }

  async function handleBatchUpload() {
    if (!companyId || !batchFiles.length) return
    if (!isBatch) {
      toast.push({ tone: 'warning', title: 'Batch', message: 'Selecciona 2 o más ficheros para subir por lotes.' })
      return
    }
    if (batchUploading) return

    setMessage('')
    setTone('info')
    batchAbortRef.current.abort = false
    setBatchUploading(true)

    // Initialize batch items if not present (e.g. if user refreshed mode)
    setBatchItems((prev) => {
      if (prev.length && prev.length === batchFiles.length) return prev
      return batchFiles.map((f) => ({ file: f, period: inferPeriodFromFilename(f.name), status: 'pending' as const }))
    })

    try {
      for (let idx = 0; idx < batchFiles.length; idx++) {
        if (batchAbortRef.current.abort) break
        const f = batchFiles[idx]

        setBatchItems((prev) => {
          const next = [...prev]
          const existing = next[idx]
          next[idx] = {
            file: f,
            period: existing?.period ?? inferPeriodFromFilename(f.name),
            status: 'uploading',
            target: existing?.target ?? null,
            message: ''
          }
          return next
        })

        try {
          const result = await uploadOneBatchFile({
            file: f,
            period: inferPeriodFromFilename(f.name),
            status: 'uploading'
          })
          setBatchItems((prev) => {
            const next = [...prev]
            const existing = next[idx]
            next[idx] = { ...(existing || { file: f, period: inferPeriodFromFilename(f.name) }), status: 'done', target: result.target, message: '' }
            return next
          })
        } catch (e: any) {
          const friendly = humanizeFileError(e?.message || e)
          const msg = [friendly.title, friendly.detail].filter(Boolean).join(' ')
          setBatchItems((prev) => {
            const next = [...prev]
            const existing = next[idx]
            next[idx] = { ...(existing || { file: f, period: inferPeriodFromFilename(f.name) }), status: 'error', message: msg }
            return next
          })
        }
      }

      // Invalidate caches once at the end (cheaper than per-file)
      await queryClient.invalidateQueries({ queryKey: ['imports', companyId] })
      await queryClient.invalidateQueries({ queryKey: ['universal-summary', companyId] })
      await queryClient.invalidateQueries({ queryKey: ['universal-suggestions', companyId] })
      await queryClient.invalidateQueries({ queryKey: ['tribunal-summary', companyId] })
      await queryClient.invalidateQueries({ queryKey: ['tribunal-status', companyId] })

      toast.push({ tone: 'success', title: 'Batch', message: 'Subida por lotes finalizada.' })
      setMessage('Subida por lotes finalizada. Revisa el estado de imports/universal/tribunal según corresponda.')
    } finally {
      setBatchUploading(false)
    }
  }

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
        subtitle="Sube un CSV/XLSX y EnterpriseIQ te guía al módulo correcto."
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

        <div className="card soft compact-guide mt-3">
          <div className="mini-row row-baseline">
            <h3 className="m-0">Guía rápida</h3>
            <Button size="sm" variant="ghost" onClick={() => navigate(`/guides?module=${guidesModule}`)}>
              Ver guía
            </Button>
          </div>

          <div className="segmented mt-2" role="tablist" aria-label="Módulo">
            <Button type="button" size="sm" variant={guideModule === 'caja' ? 'secondary' : 'ghost'} onClick={() => setGuideModule('caja')}>
              Caja
            </Button>
            <Button
              type="button"
              size="sm"
              variant={guideModule === 'universal' ? 'secondary' : 'ghost'}
              onClick={() => setGuideModule('universal')}
            >
              Universal
            </Button>
            <Button
              type="button"
              size="sm"
              variant={guideModule === 'tribunal' ? 'secondary' : 'ghost'}
              onClick={() => setGuideModule('tribunal')}
              disabled={!hasGold}
              title={!hasGold ? 'Disponible en planes GOLD/PLATINUM' : undefined}
            >
              Tribunal
            </Button>
            <Button
              type="button"
              size="sm"
              variant={guideModule === 'presupuesto' ? 'secondary' : 'ghost'}
              onClick={() => setGuideModule('presupuesto')}
            >
              Presupuesto
            </Button>
          </div>

          {!hasGold ? (
            <div className="upload-hint mt-8">
              Tribunal está disponible en planes GOLD/PLATINUM.
            </div>
          ) : null}

          <div className="mini-row compact-guide-head mt-12 row-wrap">
            <strong>{quickGuide.title}</strong>
            <a className="btn btn-ghost btn-sm" href={quickGuide.href} download>
              Descargar ejemplo
            </a>
          </div>

          <ul className="compact-list mt-2">
            {quickGuide.bullets.map((b, idx) => (
              <li key={`g-${idx}`} className="upload-hint">
                {b}
              </li>
            ))}
          </ul>
        </div>
      </div>

      {mode === 'transactions' && file && txPreview && Number(txPreview.confidence || 0) < 0.4 ? (
        <div className="section">
          <Alert tone="warning" title="Este fichero quizá no es de transacciones">
            La detección de columnas tiene poca confianza. Si es un presupuesto / ventas / inventario, usa el modo Universal.
            <div className="mt-2">
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
            <div className="upload-hint mt-8">
              Confianza transacciones: {Math.round(Number(txPreview.confidence || 0) * 100)}% · Señales Tribunal: {tribunalHint.score}
            </div>
            <div className="row row-wrap row-center gap-2 mt-2">
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
        <h3 className="h3-reset">Subida</h3>
        {!companyId ? (
          <Alert tone="warning" title="Falta seleccionar empresa">
            Selecciona una empresa arriba para subir el fichero.
          </Alert>
        ) : null}
        <div className="upload-row">
          {mode === 'transactions' ? (
            <input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="YYYY-MM" inputMode="numeric" />
          ) : null}
          <input
            type="file"
            accept=".csv,.xlsx"
            multiple
            onChange={(e) => {
              const list = Array.from(e.target.files || [])
              setFile(list[0] || null)
              setBatchFiles(list)
              setBatchItems(list.map((f) => ({ file: f, period: inferPeriodFromFilename(f.name), status: 'pending' })))
            }}
          />
          {(mode === 'universal' || mode === 'auto') && isXlsx ? (
            <details
              className="w-full"
              open={showUniversalGuided}
              onToggle={(e) => setShowUniversalGuided((e.target as HTMLDetailsElement).open)}
            >
              <summary className="upload-hint cursor-pointer">
                XLSX: hoja + cabecera (opcional)
                {xlsxLoading ? ' • previsualizando…' : ''}
                {xlsxPreview?.headers?.length ? ` • ${xlsxPreview.headers.length} columnas` : ''}
              </summary>
              <div className="upload-row tight align-end">
                <label className="stack">
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
                <label className="stack">
                  <span className="upload-hint">Fila cabecera (1-based)</span>
                  <input
                    value={headerRow ?? xlsxPreview?.headerRow ?? ''}
                    onChange={(e) => setHeaderRow(e.target.value === '' ? null : Number(e.target.value))}
                    placeholder="Ej: 3"
                    inputMode="numeric"
                    className="w-160"
                  />
                </label>
                <span className="upload-hint block mb-1">
                  Tip: pon la fila donde están ENERO…DICIEMBRE / txn_date…
                </span>
              </div>

              {xlsxPreview?.headers?.length ? (
                <div className="upload-hint mt-2">
                  Cabeceras detectadas: {xlsxPreview.headers.slice(0, 8).join(' · ')}
                  {xlsxPreview.headers.length > 8 ? ' · …' : ''}
                </div>
              ) : null}

              {xlsxPreview?.sampleRows?.length ? (
                <div className="row row-wrap row-center gap-10 mt-2">
                  <Button type="button" size="sm" variant="ghost" onClick={() => setShowXlsxSample((s) => !s)}>
                    {showXlsxSample ? 'Ocultar preview' : 'Ver preview'}
                  </Button>
                  <span className="upload-hint">Muestra: 6 filas · 8 columnas</span>
                </div>
              ) : null}

              {showXlsxSample && xlsxPreview?.sampleRows?.length ? (
                <div className="mt-2 overflow-auto">
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
              ) : showXlsxSample ? (
                <div className="empty mt-2">
                  {xlsxLoading ? 'Previsualizando…' : 'No se pudieron leer filas de muestra.'}
                </div>
              ) : null}
            </details>
          ) : null}
          {mode === 'transactions' && isXlsx ? (
            <details className="w-full">
              <summary className="upload-hint cursor-pointer">
                Ajustes XLSX (opcional)
              </summary>
              <div className="upload-row tight">
                <input
                  value={sheetIndex ?? ''}
                  onChange={(e) => setSheetIndex(e.target.value === '' ? null : Number(e.target.value))}
                  placeholder="sheetIndex"
                  inputMode="numeric"
                  className="w-140"
                />
                <input
                  value={headerRow ?? ''}
                  onChange={(e) => setHeaderRow(e.target.value === '' ? null : Number(e.target.value))}
                  placeholder="headerRow"
                  inputMode="numeric"
                  className="w-140"
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
          {isBatch ? (
            <Button
              variant="secondary"
              onClick={handleBatchUpload}
              disabled={!companyId || batchUploading || uploading || !batchFiles.length}
              loading={batchUploading}
            >
              Subir {batchFiles.length} ficheros
            </Button>
          ) : null}
          {isBatch && batchUploading ? (
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                batchAbortRef.current.abort = true
                toast.push({ tone: 'warning', title: 'Batch', message: 'Cancelación solicitada. Se parará al terminar el fichero actual.' })
              }}
            >
              Cancelar
            </Button>
          ) : null}
        </div>
        {isBatch ? (
          <details className="mt-12" open>
            <summary className="upload-hint cursor-pointer">
              Subida por lotes ({batchFiles.length})
            </summary>
            <div className="upload-hint mt-8">
              Tip: si tus ficheros incluyen el periodo en el nombre (p. ej. <span className="mono">2026-03</span> o <span className="mono">202603</span>),
              se usará automáticamente al subir Caja.
            </div>
            <div className="table-wrap mt-12">
              <table className="table table-fixed">
                <thead>
                  <tr>
                    <th>Fichero</th>
                    <th className="w-110">Periodo</th>
                    <th className="w-140">Destino</th>
                    <th className="w-140">Estado</th>
                  </tr>
                </thead>
                <tbody>
                  {(batchItems.length
                    ? batchItems
                    : (batchFiles.map((f) => ({
                        file: f,
                        period: inferPeriodFromFilename(f.name),
                        status: 'pending' as const,
                        target: null,
                        message: ''
                      })) as BatchItem[])
                  ).map((it, idx) => (
                      <tr key={`b-${idx}-${it.file.name}`}>
                        <td className="upload-hint">
                          <div className="fw-700">{it.file.name}</div>
                          {it.message ? <div className="upload-hint">{it.message}</div> : null}
                        </td>
                        <td className="mono upload-hint">{it.period || '—'}</td>
                        <td className="upload-hint">{it.target || '—'}</td>
                        <td>
                          <span className={`badge ${it.status === 'done' ? 'ok' : it.status === 'error' ? 'err' : it.status === 'uploading' ? 'warn' : ''}`}>
                            {it.status}
                          </span>
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          </details>
        ) : null}
        {file && isCsv ? (
          <div className="upload-hint mt-2">
            Si es un CSV “de Excel” con varias tablas/gráficas, suele romperse al exportar. Mejor sube el <strong>XLSX original</strong> y usa el modo
            guiado para elegir cabecera.
          </div>
        ) : null}
        {message ? (
          <div className="mt-12">
            <Alert tone={tone}>{message}</Alert>
          </div>
        ) : null}
        {mode === 'transactions' ? (
          <div className="upload-hint mt-2">
            Si tu archivo no es de transacciones (por ejemplo presupuestos o datasets externos), usa <strong>Universal</strong>.
          </div>
        ) : (
          <div className="upload-hint mt-2">
            Universal es para análisis/insights (no recalcula KPIs de Caja).
          </div>
        )}
      </div>

      {mode === 'transactions' && companyId && file && txPreview ? (
        <div className="card section">
          <h3 className="h3-reset">Asistente de mapeo (Caja)</h3>
          <div className="upload-hint">
            Selecciona qué columnas significan <strong>fecha</strong> e <strong>importe</strong>. El resto es opcional.
          </div>
          <div className="upload-row mt-12">
            <label className="stack">
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
            <label className="stack">
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
          </div>

          <details className="mt-2">
            <summary className="upload-hint cursor-pointer">
              Opcional (mejora el dashboard)
            </summary>
            <div className="upload-row tight">
              <label className="stack">
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
              <label className="stack">
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
              <label className="stack">
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
              <div className="mt-12 overflow-auto">
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
              <div className="empty mt-12">
                No se pudieron leer filas de muestra.
              </div>
            )}
          </details>
        </div>
      ) : null}

      <div className="card section">
        <h3 className="h3-reset">Historial de imports</h3>
        {!data?.length ? (
          <div className="empty">No hay imports todavía.</div>
        ) : (
          <>
            {latestImport ? (
              <div className="card soft mb-12">
                <div className="mini-row row-center row-wrap">
                  <strong>Último import</strong>
                  <span className="upload-hint">{new Date(latestImport.createdAt).toLocaleString()}</span>
                </div>

                {(() => {
                  const info = summarizeImport(latestImport)
                  const canRetry =
                    (latestImport.status === 'ERROR' || latestImport.status === 'DEAD') && info.canRetry && !!latestImport.storageRef
                  return (
                    <>
                      <div className="mini-row mt-8 row-wrap">
                        <div className="row row-center row-wrap gap-10">
                          <span className="fw-800">{latestImport.period}</span>
                          <span
                            className={`badge ${
                              latestImport.status === 'OK'
                                ? 'ok'
                                : latestImport.status === 'WARNING'
                                ? 'warn'
                                : latestImport.status === 'ERROR' || latestImport.status === 'DEAD'
                                ? 'err'
                                : ''
                            }`}
                          >
                            {latestImport.status}
                          </span>
                          <span className="upload-hint">{latestImport.originalFilename || latestImport.storageRef || '-'}</span>
                        </div>
                        {latestImport.status === 'ERROR' || latestImport.status === 'DEAD' ? (
                          <Button size="sm" disabled={!canRetry} onClick={() => handleRetry(latestImport.id)}>
                            Reintentar
                          </Button>
                        ) : null}
                      </div>
                      <div className="mt-8">{info.title}</div>
                      {info.fix ? <div className="upload-hint mt-1">{info.fix}</div> : null}
                    </>
                  )
                })()}
              </div>
            ) : null}

            <details>
              <summary className="upload-hint cursor-pointer">
                Ver historial ({data.length})
              </summary>

              <div className="row row-between row-center row-wrap gap-8 fs-12 mt-2 mb-2">
                <label className="upload-hint row row-center gap-8">
                  <input type="checkbox" checked={showAllImports} onChange={(e) => setShowAllImports(e.target.checked)} />
                  Mostrar más
                </label>

                <details>
                  <summary className="upload-hint cursor-pointer">
                    Filtros
                  </summary>
                  <div className="stack gap-8 mt-8">
                    <label className="upload-hint row row-center gap-8">
                      <input
                        type="checkbox"
                        checked={showOnlyFailedImports}
                        onChange={(e) => setShowOnlyFailedImports(e.target.checked)}
                      />
                      Solo con problemas
                    </label>
                    <label className="upload-hint row row-center gap-8">
                      <input type="checkbox" checked={showDeadImports} onChange={(e) => setShowDeadImports(e.target.checked)} />
                      Incluir DEAD (técnico)
                    </label>
                  </div>
                </details>
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
                    const isQualityOpen = qualityOpenId === imp.id
                    return (
                      <Fragment key={imp.id}>
                        <tr key={imp.id}>
                          <td>
                            <div className="fw-700">{imp.period}</div>
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
                          <td className="maxw-460">
                            <div>{info.title}</div>
                            {info.raw ? (
                              <details className="mt-1">
                                <summary className="upload-hint">Detalles técnicos</summary>
                                <div className="upload-hint pre-wrap">{info.raw}</div>
                              </details>
                            ) : null}
                          </td>
                          <td className="upload-hint maxw-420">
                            {info.fix || '-'}
                            {info.showTemplate ? (
                              <div className="mt-8">
                                <Button size="sm" variant="secondary" onClick={downloadTransactionsTemplate}>
                                  Descargar plantilla (CSV)
                                </Button>
                              </div>
                            ) : null}
                          </td>
                          <td className="text-right nowrap">
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={!imp.storageRef}
                              onClick={() => setQualityOpenId((cur) => (cur === imp.id ? null : imp.id))}
                            >
                              Calidad
                            </Button>
                            {imp.status === 'ERROR' || imp.status === 'DEAD' ? (
                              <Button size="sm" disabled={!canRetry} onClick={() => handleRetry(imp.id)}>
                                Reintentar
                              </Button>
                            ) : null}
                          </td>
                        </tr>
                        {isQualityOpen ? (
                          <tr key={`q-${imp.id}`}>
                            <td colSpan={6}>
                              <div className="card soft card-pad-sm mt-2">
                                <div className="mini-row mt-0 row-between row-center row-wrap gap-8">
                                  <div className="row row-center gap-8">
                                    <span className="fw-700">Calidad de dato</span>
                                    {qualityLoading ? <span className="upload-hint">Analizando…</span> : null}
                                    {!qualityLoading && quality ? (
                                      <span className={`badge ${qualitySummary(quality)?.badge || ''}`}>
                                        {qualitySummary(quality)?.label || 'OK'}
                                      </span>
                                    ) : null}
                                  </div>
                                  <div className="upload-hint">
                                    {quality?.minDate && quality?.maxDate ? `Rango: ${quality.minDate} → ${quality.maxDate}` : ''}
                                  </div>
                                </div>

                                {qualityError ? (
                                  <Alert tone="danger">No se pudo calcular la calidad: {String((qualityError as any).message || qualityError)}</Alert>
                                ) : null}

                                {quality ? (
                                  <div className="grid grid-autofit-180 mt-12">
                                    <div className="card soft card-pad-sm">
                                      <div className="upload-hint">Filas parseadas</div>
                                      <div className="fw-700">{quality.rowsParsed}</div>
                                    </div>
                                    <div className="card soft card-pad-sm">
                                      <div className="upload-hint">Errores fecha/importe</div>
                                      <div className="fw-700">
                                        {quality.dateParseErrors}/{quality.amountParseErrors}
                                      </div>
                                    </div>
                                    <div className="card soft card-pad-sm">
                                      <div className="upload-hint">Fuera de periodo</div>
                                      <div className="fw-700">{quality.outsidePeriodRows}</div>
                                    </div>
                                    <div className="card soft card-pad-sm">
                                      <div className="upload-hint">Duplicados</div>
                                      <div className="fw-700">{quality.duplicateRows}</div>
                                    </div>
                                    <div className="card soft card-pad-sm">
                                      <div className="upload-hint">Sin contraparte</div>
                                      <div className="fw-700">{quality.missingCounterpartyRows}</div>
                                    </div>
                                    <div className="card soft card-pad-sm">
                                      <div className="upload-hint">Saldo no cuadra</div>
                                      <div className="fw-700">{quality.balanceEndMismatchRows}</div>
                                    </div>
                                  </div>
                                ) : null}

                                {quality?.issues?.length ? (
                                  <div className="mt-12">
                                    {quality.issues.slice(0, 6).map((it, idx) => (
                                      <div key={`qi-${idx}`} className="row row-center gap-8 mb-1">
                                        <span className={`badge ${String(it.severity).toUpperCase() === 'HIGH' ? 'err' : String(it.severity).toUpperCase() === 'MEDIUM' ? 'warn' : 'ok'}`}>
                                          {String(it.severity || '').toUpperCase()}
                                        </span>
                                        <div>
                                          <div className="fw-700">{it.title}</div>
                                          <div className="upload-hint">{it.detail}</div>
                                        </div>
                                      </div>
                                    ))}
                                  </div>
                                ) : null}

                                {quality?.examples?.length ? (
                                  <details className="mt-12">
                                    <summary className="upload-hint cursor-pointer">Ver ejemplos</summary>
                                    <div className="upload-hint mono pre-wrap mt-8">
                                      {quality.examples.join('\n')}
                                    </div>
                                  </details>
                                ) : null}
                              </div>
                            </td>
                          </tr>
                        ) : null}
                      </Fragment>
                    )
                  })}
                </tbody>
              </table>
            </details>
          </>
        )}
      </div>
    </div>
  )
}

