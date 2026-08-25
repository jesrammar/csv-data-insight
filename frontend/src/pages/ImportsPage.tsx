// @ts-nocheck
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getImports,
  getReports,
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
  type UniversalSummaryDto,
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
import { intakeDetail, intakeDisplayLabel, intakeKind, isAnnualBudgetDiagnosis } from '../utils/intakeDiagnosis'

export default function ImportsPage() {
  const { id: companyId, plan } = useCompanySelection()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const annualFlow = String(searchParams.get('flow') || '').toLowerCase() === 'budget'
  const annualFocus = String(searchParams.get('focus') || '').toLowerCase()
  const queryClient = useQueryClient()
  const toast = useToast()
  const { data } = useQuery({
    queryKey: ['imports', companyId],
    queryFn: () => getImports(companyId as number),
    enabled: !!companyId
  })
  const { data: reports } = useQuery({
    queryKey: ['imports-flow-reports', companyId],
    queryFn: () => getReports(companyId as number),
    enabled: !!companyId
  })
  const [mode, setMode] = useState<'auto' | 'transactions' | 'universal'>('auto')
  const hasGold = plan === 'GOLD' || plan === 'PLATINUM'

  useEffect(() => {
    const m = String(searchParams.get('mode') || '').toLowerCase()
    if (m === 'auto' || m === 'transactions' || m === 'universal') setMode(m as any)
    if (String(searchParams.get('flow') || '').toLowerCase() === 'budget') setMode('universal')
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
  const modeSectionRef = useRef<HTMLDivElement | null>(null)
  const uploadSectionRef = useRef<HTMLDivElement | null>(null)
  const exceptionSectionRef = useRef<HTMLDetailsElement | null>(null)

  function finishUniversalUpload(result: UniversalSummaryDto, sourceLabel: 'AUTO' | 'UNIVERSAL') {
    const diagnosis = result?.intakeDiagnosis || null
    const detectedAnnualBudget = isAnnualBudgetDiagnosis(diagnosis)
    const detectedLabel = intakeDisplayLabel(diagnosis, 'tabla analitica')
    const detectedDetail = intakeDetail(diagnosis)
    const detectedKind = intakeKind(diagnosis)

    setTone(annualFlow && !detectedAnnualBudget ? 'info' : 'success')

    if (annualFlow) {
      if (detectedAnnualBudget) {
        setMessage('Plan anual detectado. Volvemos a Plan anual para validar la lectura.')
        toast.push({ tone: 'success', title: 'Plan anual', message: 'Fichero anual detectado. Abriendo el flujo anual.' })
        navigate('/budget?source=upload')
        return
      }

      setMessage('El fichero se ha cargado, pero no parece un plan anual. Revísalo desde Cargar datos antes de volver a Plan anual.')
      toast.push({
        tone: 'warning',
        title: 'No parece presupuesto',
        message: `${detectedLabel}. Revísalo en Cargar datos antes de usarlo como plan anual.`
      })
      navigate('/imports')
      return
    }

    if (detectedAnnualBudget) {
      setMessage('Plan anual detectado. Lo abrimos directamente en Plan anual.')
      toast.push({ tone: 'success', title: 'Plan anual', message: 'Presupuesto anual detectado correctamente.' })
      navigate('/budget?source=upload')
      return
    }

    if (detectedKind === 'CASH_TRANSACTIONS') {
      setMessage('Parece un fichero de caja. La carga queda registrada y puedes seguir afinándola desde Cargar datos.')
      toast.push({
        tone: 'info',
        title: 'Caja detectada',
        message: 'La lectura queda registrada en esta misma pantalla para revisar calidad o volver a subir.'
      })
      navigate('/imports')
      return
    }

    if (detectedKind === 'TRIBUNAL_PORTFOLIO') {
      setMessage('Parece una cartera operativa. La carga queda registrada para revisar desde Cargar datos.')
      toast.push({
        tone: 'info',
        title: 'Tribunal detectado',
        message: 'El fichero encaja mejor en cartera operativa que en una carga mensual.'
      })
      navigate('/imports')
      return
    }

    setMessage(
      detectedDetail
        ? `${detectedLabel}. ${detectedDetail}`
        : `Archivo analizado correctamente${sourceLabel === 'AUTO' ? ' (auto)' : ''}.`
    )
    toast.push({
      tone: 'success',
      title: sourceLabel === 'AUTO' ? 'Universal (auto)' : 'Universal',
      message: detectedDetail || 'Archivo analizado correctamente.'
    })
    navigate('/imports')
  }

  type BatchItem = {
    file: File
    period: string | null
    status: 'pending' | 'uploading' | 'done' | 'error' | 'skipped'
    target?: 'transactions' | 'universal' | 'tribunal' | null
    message?: string
  }

  type ExceptionInboxEntry = {
    severity: 'HIGH' | 'MEDIUM' | 'LOW'
    code: string
    title: string
    detail: string
    action: string
    evidence?: string
  }

  const [batchItems, setBatchItems] = useState<BatchItem[]>([])
  const workflowHref = '/budget'
  const selectedPeriodImport = useMemo(() => {
    const list = ((data || []) as ImportJob[]).filter((item) => String(item.period || '') === String(period || ''))
    return list.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0] || null
  }, [data, period])
  const selectedPeriodReport = useMemo(() => {
    return ((reports || []) as any[]).find((item) => String(item?.period || '') === String(period || '')) || null
  }, [period, reports])
  const selectedPeriodFlow = useMemo(() => {
    const importState = !companyId
      ? { title: 'Sin empresa gestionada', detail: 'Selecciona una empresa gestionada para activar el flujo de este periodo.' }
      : !selectedPeriodImport
        ? { title: 'Carga pendiente', detail: 'Todavía no hay un import registrado para este periodo.' }
        : selectedPeriodImport.status === 'OK'
          ? { title: 'Datos cargados', detail: 'El import del periodo terminó correctamente.' }
          : selectedPeriodImport.status === 'WARNING'
            ? { title: 'Datos con avisos', detail: 'El periodo está cargado, pero conviene revisar calidad antes de seguir.' }
            : { title: `Import ${selectedPeriodImport.status}`, detail: 'El flujo está bloqueado hasta corregir o reintentar esta carga.' }

    const reviewReady = !!selectedPeriodImport && ['OK', 'WARNING'].includes(String(selectedPeriodImport.status || ''))
    const reportReady = !!selectedPeriodReport

    return {
      importState,
      reviewState: reviewReady
        ? { title: 'Lectura disponible', detail: 'Ya puedes validar si esta carga alimenta bien el plan anual o volver a corregirla.' }
        : { title: 'Lectura bloqueada', detail: 'Primero necesitas un import válido para revisar el periodo.' },
      reportState: reportReady
        ? { title: 'PDF disponible', detail: 'Ya existe un informe generado para esta empresa.' }
        : { title: 'PDF pendiente', detail: 'Cuando valides la lectura, podrás cerrar el periodo en Entregables.' }
    }
  }, [companyId, selectedPeriodImport, selectedPeriodReport])

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
          title: 'Plan anual (XLSX)',
          href: '/samples/presupuesto-ejemplo.xlsx',
          bullets: [
            <>Sube el XLSX por Universal (si tiene varias hojas, usa modo guiado).</>,
            <>
              Luego ve al <strong>plan anual</strong> para validar la lectura, y al análisis técnico para revisar “long” e insights.
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
    if (showOnlyFailedImports) out = out.filter((i) => i.status === 'ERROR' || i.status === 'DEAD' || i.status === 'WARNING' || i.status === 'BLOCKED')
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

  const statusBadgeClass = (status: ImportJob['status']) =>
    status === 'OK'
      ? 'ok'
      : status === 'WARNING'
      ? 'warn'
      : status === 'ERROR' || status === 'DEAD' || status === 'BLOCKED'
      ? 'err'
      : ''

  const severityBadgeClass = (severity: string) => {
    const normalized = String(severity || '').toUpperCase()
    return normalized === 'HIGH' ? 'err' : normalized === 'MEDIUM' ? 'warn' : 'ok'
  }

  const formatRate = (value: number | null | undefined, total: number | null | undefined) => {
    const safeValue = Number(value || 0)
    const safeTotal = Number(total || 0)
    if (!safeTotal) return null
    return `${Math.round((safeValue / safeTotal) * 100)}%`
  }

  const blockingMeta = (code: string | null | undefined) => {
    switch (String(code || '').toUpperCase()) {
      case 'DUPLICATE_CONTENT_HASH':
        return {
          title: 'El fichero ya se había cargado exactamente igual.',
          action: 'No lo reintentes. Revisa si esa versión ya está aplicada y sube solo una variante real.'
        }
      case 'DUPLICATE_NORMALIZED_HASH':
        return {
          title: 'Los datos efectivos coinciden con una versión ya aplicada.',
          action: 'Evita reprocesar el mismo periodo. Si cambió algo, sube una versión corregida con contenido distinto.'
        }
      case 'MIN_VALID_ROWS':
        return {
          title: 'No hay suficientes filas válidas para confiar en el import.',
          action: 'Reexporta el Excel/CSV, revisa cabeceras y valida formato de fecha e importe antes de subirlo de nuevo.'
        }
      case 'HIGH_WARNING_RATE':
        return {
          title: 'La tasa de incidencias supera el umbral de calidad.',
          action: 'Corrige el origen o limpia el fichero antes de reintentar para no contaminar los cuadros de mando.'
        }
      case 'OUTSIDE_PERIOD_ROWS':
        return {
          title: 'Hay movimientos fuera del periodo declarado.',
          action: 'Separa por meses o corrige el periodo objetivo antes de volver a subir el fichero.'
        }
      case 'HIGH_DUPLICATE_RATE':
        return {
          title: 'El fichero trae demasiados duplicados.',
          action: 'Depura duplicados en origen o conserva una única línea por movimiento antes de reimportar.'
        }
      default:
        return {
          title: 'El import quedó bloqueado por reglas de ingestión.',
          action: 'Revisa la causa y vuelve a subir una versión corregida del fichero.'
        }
    }
  }

  const qualityIssueAction = (code: string | null | undefined) => {
    switch (String(code || '').toUpperCase()) {
      case 'MISSING_TXN_DATE':
      case 'DATE_PARSE_ERRORS':
        return 'Normaliza la columna de fecha y asegúrate de que todas las filas usan un formato consistente.'
      case 'MISSING_AMOUNT':
      case 'AMOUNT_PARSE_ERRORS':
        return 'Convierte importes a número limpio, sin texto embebido ni separadores ambiguos.'
      case 'OUTSIDE_PERIOD_ROWS':
        return 'Saca del fichero los asientos que pertenecen a otros meses o cambia el periodo de carga.'
      case 'DUPLICATE_ROWS':
        return 'Elimina movimientos repetidos para no inflar KPIs, saldos y tendencias.'
      case 'MISSING_COUNTERPARTY':
        return 'Completa la contraparte si quieres una lectura más útil de clientes, proveedores y dispersión.'
      case 'BALANCE_END_MISMATCH':
        return 'Revisa el saldo final informado porque no está cuadrando con la secuencia de movimientos.'
      default:
        return 'Revisa esta incidencia antes de dar por buena la carga o de seguir con el dashboard.'
    }
  }

  const buildImportExceptionEntries = (imp: ImportJob): ExceptionInboxEntry[] => {
    const entries: ExceptionInboxEntry[] = []
    const info = summarizeImport(imp)
    const warningRate = formatRate((imp.rowsReceived ?? 0) - (imp.rowsValid ?? 0), imp.rowsReceived)

    if (imp.status === 'BLOCKED') {
      const meta = blockingMeta(imp.blockingCode)
      entries.push({
        severity: 'HIGH',
        code: imp.blockingCode || 'BLOCKED',
        title: meta.title,
        detail: imp.blockingReason || info.title,
        action: meta.action,
        evidence:
          [
            imp.versionNo ? `Versión ${imp.versionNo}` : null,
            imp.rowsValid != null && imp.rowsReceived != null ? `${imp.rowsValid}/${imp.rowsReceived} filas válidas` : null,
            imp.duplicateOfImportId ? `Duplica al import #${imp.duplicateOfImportId}` : null
          ]
            .filter(Boolean)
            .join(' · ') || undefined
      })
    }

    if (imp.status === 'ERROR' || imp.status === 'DEAD') {
      entries.push({
        severity: 'HIGH',
        code: imp.status,
        title: info.title,
        detail: info.raw || 'El proceso no pudo completar la ingestión.',
        action: info.fix || 'Revisa el fichero fuente, corrige el problema y vuelve a subirlo.',
        evidence: imp.attempts != null ? `Intentos ${imp.attempts}/${imp.maxAttempts ?? 3}` : undefined
      })
    }

    if (imp.status === 'WARNING') {
      entries.push({
        severity: 'MEDIUM',
        code: 'WARNING_IMPORT',
        title: 'El import terminó, pero dejó incidencias que merecen revisión.',
        detail:
          imp.errorSummary ||
          imp.lastError ||
          'La carga se procesó con avisos y puede introducir ruido en la lectura ejecutiva.',
        action: 'Abre la bandeja, valida las incidencias y decide si basta con aceptar la carga o conviene rehacerla.',
        evidence:
          [
            imp.warningCount ? `${imp.warningCount} warnings` : null,
            warningRate ? `${warningRate} de filas con incidencias` : null,
            imp.supersedesImportId ? `Sustituye al import #${imp.supersedesImportId}` : null
          ]
            .filter(Boolean)
            .join(' · ') || undefined
      })
    }

    return entries
  }

  const importRowNextStep = (imp: ImportJob) => {
    const info = summarizeImport(imp)

    if (imp.status === 'BLOCKED') {
      const meta = blockingMeta(imp.blockingCode)
      return {
        title: 'Corregir y volver a subir',
        detail: meta.action
      }
    }

    if (imp.status === 'ERROR' || imp.status === 'DEAD') {
      return {
        title: info.canRetry ? 'Reintentar o rehacer fichero' : 'Volver a subir fichero',
        detail: info.fix || 'Revisa el origen, corrige el problema y lanza una nueva versión.'
      }
    }

    if (imp.status === 'WARNING') {
      return {
        title: 'Abrir excepciones',
        detail: 'Valida las incidencias de calidad antes de dar esta carga por buena o seguir con el cierre.'
      }
    }

    if (imp.status === 'OK') {
      return {
        title: 'Seguir con el cierre',
        detail: 'La base es utilizable. Desde aquí ya toca revisar el periodo o preparar el entregable.'
      }
    }

    return {
      title: 'Esperar procesamiento',
      detail: 'La carga todavía no ha terminado de resolverse por completo.'
    }
  }

  const attentionImports = useMemo(() => {
    const list = ((data || []) as ImportJob[]).filter((imp) => {
      return imp.status === 'WARNING' || imp.status === 'BLOCKED' || imp.status === 'ERROR' || imp.status === 'DEAD'
    })

    return list
      .map((imp) => {
        const entries = buildImportExceptionEntries(imp)
        const top = entries[0]
        const priority = top?.severity === 'HIGH' ? 3 : top?.severity === 'MEDIUM' ? 2 : 1
        return { imp, entries, top, priority }
      })
      .sort((a, b) => {
        if (b.priority !== a.priority) return b.priority - a.priority
        return new Date(b.imp.createdAt).getTime() - new Date(a.imp.createdAt).getTime()
      })
  }, [data])

  const attentionSummary = useMemo(() => {
    const blocked = attentionImports.filter((item) => item.imp.status === 'BLOCKED').length
    const warnings = attentionImports.filter((item) => item.imp.status === 'WARNING').length
    const technical = attentionImports.filter((item) => item.imp.status === 'ERROR' || item.imp.status === 'DEAD').length
    return { blocked, warnings, technical }
  }, [attentionImports])

  const importDecisionState = useMemo(() => {
    if (!companyId) {
      return {
        title: 'Selecciona una empresa gestionada',
        detail: 'Sin empresa no podemos cargar, revisar excepciones ni cerrar el periodo.'
      }
    }

    if (attentionSummary.blocked || attentionSummary.technical) {
      return {
        title: 'La ingestión necesita intervención',
        detail: 'Hay bloqueos o fallos técnicos abiertos. Conviene resolverlos antes de seguir con lectura o entregable.'
      }
    }

    if (attentionSummary.warnings) {
      return {
        title: 'Hay cargas con avisos',
        detail: 'La base existe, pero todavía merece una revisión rápida de calidad antes de dar el periodo por bueno.'
      }
    }

    if (file) {
      return {
        title: 'Fichero listo para subir',
        detail: `Ya has preparado ${file.name}. El siguiente paso es cargarlo y validar cómo lo interpreta el sistema.`
      }
    }

    if (selectedPeriodImport && ['OK', 'WARNING'].includes(String(selectedPeriodImport.status || ''))) {
      return {
        title: 'Periodo listo para revisión',
        detail: 'La base del periodo ya existe. Desde aquí toca revisar excepciones si las hubiera o seguir el cierre mensual.'
      }
    }

    return {
      title: 'Arranca la carga del periodo',
      detail: 'Sube un CSV o XLSX limpio y deja que Imports active el resto del flujo operativo.'
    }
  }, [attentionSummary.blocked, attentionSummary.technical, attentionSummary.warnings, companyId, file, selectedPeriodImport])

  const importsNextAction = useMemo(() => {
    if (!companyId) {
      return {
        label: 'Selecciona empresa',
        detail: 'Empieza activando una empresa gestionada para operar sobre su periodo.',
        kind: 'idle' as const
      }
    }

    if (attentionImports.length) {
      return {
        label: 'Revisar excepciones',
        detail: 'Prioriza bloqueos, avisos o fallos antes de subir más versiones o seguir el cierre.',
        kind: 'exceptions' as const
      }
    }

    if (file || !selectedPeriodImport) {
      return {
        label: 'Elegir modo y subir',
        detail: file
          ? 'El fichero ya está preparado, pero primero conviene confirmar el modo correcto antes de subirlo.'
          : 'Empieza eligiendo cómo debe leer el sistema este fichero y después súbelo.',
        kind: 'upload' as const
      }
    }

    if (selectedPeriodReport) {
      return {
        label: 'Abrir informe',
        detail: 'Ya existe un entregable para este periodo. Revísalo o compártelo desde Informes.',
        kind: 'route' as const,
        href: '/reports'
      }
    }

    return {
      label: 'Abrir cierre mensual',
      detail: 'La siguiente pantalla natural ya no es Imports: es el workflow oficial del periodo.',
      kind: 'route' as const,
      href: workflowHref
    }
  }, [attentionImports.length, companyId, file, selectedPeriodImport, selectedPeriodReport, workflowHref])
  const importsSupportOpen = attentionImports.length > 0

  const openImport = useMemo(() => {
    return ((data || []) as ImportJob[]).find((item) => item.id === qualityOpenId) || null
  }, [data, qualityOpenId])

  const openImportExceptions = useMemo(() => {
    if (!openImport) return []
    return buildImportExceptionEntries(openImport)
  }, [openImport])

  const qualityExceptions = useMemo(() => {
    if (!quality) return []
    return (Array.isArray(quality.issues) ? quality.issues : []).map((issue) => ({
      severity: String(issue.severity || '').toUpperCase() === 'HIGH' ? 'HIGH' : String(issue.severity || '').toUpperCase() === 'MEDIUM' ? 'MEDIUM' : 'LOW',
      code: issue.code,
      title: issue.title,
      detail: issue.detail,
      action: qualityIssueAction(issue.code)
    })) as ExceptionInboxEntry[]
  }, [quality])

  const openImportAllIssues = useMemo(() => {
    return [...openImportExceptions, ...qualityExceptions]
  }, [openImportExceptions, qualityExceptions])

  const openImportDecisionState = useMemo(() => {
    if (!openImport) {
      return {
        title: 'Sin carga abierta',
        detail: 'Abre una carga para revisar su bandeja de excepciones.'
      }
    }

    if (qualityLoading) {
      return {
        title: 'Analizando calidad',
        detail: 'Estamos leyendo incidencias operativas y de calidad para esta carga.'
      }
    }

    const primaryIssue = openImportAllIssues[0]
    if (primaryIssue) {
      return {
        title: primaryIssue.title,
        detail: primaryIssue.action
      }
    }

    return {
      title: 'Carga estable',
      detail: 'No se detectan incidencias abiertas. Esta base ya puede sostener la revisión del periodo.'
    }
  }, [openImport, openImportAllIssues, qualityLoading])

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
      setMessage('Subida por lotes finalizada. Revisa el estado de las cargas y decide si ya puedes seguir con el plan anual.')
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
          setMessage('Archivo de cartera cargado. La carga queda registrada en Cargar datos para decidir el siguiente paso.')
          toast.push({ tone: 'success', title: 'Auto', message: 'Fichero cargado correctamente.' })
          navigate('/imports')
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
        const result = await uploadUniversalImport(companyId, file, opts)
        await queryClient.invalidateQueries({ queryKey: ['universal-summary', companyId] })
        await queryClient.invalidateQueries({ queryKey: ['universal-suggestions', companyId] })
        await queryClient.invalidateQueries({ queryKey: ['budget-workflow', companyId] })
        await queryClient.invalidateQueries({ queryKey: ['budget-summary-workflow', companyId] })
        await queryClient.invalidateQueries({ queryKey: ['budget-insights-workflow', companyId] })
        finishUniversalUpload(result, 'AUTO')
        return
      }

      if (mode === 'universal') {
        const opts =
          file.name.toLowerCase().endsWith('.xlsx')
            ? { sheetIndex: sheetIndex ?? xlsxPreview?.sheetIndex ?? undefined, headerRow: headerRow ?? xlsxPreview?.headerRow ?? undefined }
            : {}
        const result = await uploadUniversalImport(companyId, file, opts)
        await queryClient.invalidateQueries({ queryKey: ['universal-summary', companyId] })
        await queryClient.invalidateQueries({ queryKey: ['universal-suggestions', companyId] })
        await queryClient.invalidateQueries({ queryKey: ['budget-workflow', companyId] })
        await queryClient.invalidateQueries({ queryKey: ['budget-summary-workflow', companyId] })
        await queryClient.invalidateQueries({ queryKey: ['budget-insights-workflow', companyId] })
        finishUniversalUpload(result, 'UNIVERSAL')
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

  const quickModeLabel = mode === 'auto' ? 'Auto' : mode === 'transactions' ? 'Caja' : 'Universal'
  const quickHeroTitle = annualFlow ? 'Corrige el presupuesto anual.' : 'Sube el fichero del periodo.'
  const quickHeroDetail = annualFlow
    ? 'Carga el XLSX correcto y toca hoja o cabecera solo si Plan anual no detecta bien los meses.'
    : 'Elige modo, periodo y archivo. El resto solo aparece si hace falta.'
  const quickImportStatus = selectedPeriodImport?.status || 'Sin carga'

  return (
    <div className="imports-page imports-quick-page">
      <PageHeader
        title="Cargar datos"
        subtitle="Carga el fichero correcto y decide el siguiente paso sin ruido."
        actions={
          <div className="imports-quick-top-actions">
            <span className="badge">{period}</span>
            {annualFlow ? <span className="badge badge-ok">Plan anual</span> : null}
          </div>
        }
      />

      {!companyId ? <Alert tone="warning">Selecciona una empresa para empezar.</Alert> : null}
      {message ? (
        <Alert tone={tone === 'danger' ? 'danger' : tone === 'success' ? 'success' : 'info'} className="mb-3">
          {message}
        </Alert>
      ) : null}
      {annualFlow ? (
        <Alert tone="info" className="mb-3">
          Estás corrigiendo un presupuesto anual. Sube el XLSX correcto y, si hace falta, ajusta hoja y cabecera antes de volver a Plan anual.
        </Alert>
      ) : null}

      <section className="imports-quick-hero card">
        <div className="imports-quick-hero-copy">
          <span className="imports-quick-eyebrow">{annualFlow ? 'Plan anual' : 'Cargar datos'}</span>
          <h2>{quickHeroTitle}</h2>
          <p>{quickHeroDetail}</p>
          <div className="imports-quick-tags">
            <span className="imports-quick-tag">{quickModeLabel}</span>
            <span className="imports-quick-tag">{period}</span>
            <span className="imports-quick-tag">{quickImportStatus}</span>
          </div>
        </div>
        <div className="imports-quick-hero-side">
          <div className="imports-quick-context-card">
            <span>Ultima carga</span>
            <strong>{selectedPeriodImport?.filename || 'Sin fichero registrado'}</strong>
            <small>{selectedPeriodFlow.importState.detail}</small>
          </div>
          <div className="imports-quick-context-card">
            <span>Siguiente paso</span>
            <strong>{selectedPeriodFlow.reviewState.title}</strong>
            <small>{selectedPeriodFlow.reviewState.detail}</small>
          </div>
        </div>
      </section>

      <div className="card section soft imports-quick-shell">
        <div className="mini-row row-baseline">
          <div>
            <span>1. Carga</span>
            <h3>Subir fichero</h3>
          </div>
          <span className="upload-hint">Elige módulo, periodo y archivo. Nada más.</span>
        </div>
        <div className="grid grid-autofit-220 mt-12">
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Modo</div>
            <select value={mode} onChange={(e) => setMode(e.target.value as 'auto' | 'transactions' | 'universal')} className="mt-8">
              <option value="auto">AUTO</option>
              <option value="transactions">Caja</option>
              <option value="universal">Universal</option>
            </select>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Periodo</div>
            <input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="YYYY-MM" className="pipeline-period-input mt-8" />
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Archivo</div>
            <label className="workforce-file-trigger mt-8">
              <input type="file" accept=".csv,.xlsx" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="sr-only" />
              <span className="workforce-file-trigger-button">Seleccionar archivo</span>
              <span className="workforce-file-trigger-name" title={file ? file.name : 'Sin fichero seleccionado.'}>
                {file ? file.name : 'Sin fichero seleccionado.'}
              </span>
            </label>
            <div className="upload-hint mt-8">{file ? file.name : 'Todavía sin fichero seleccionado.'}</div>
          </div>
        </div>
        <div className="row row-wrap gap-8 mt-12">
          <Button onClick={handleUpload} disabled={!companyId || !file || uploading || !isAllowed} loading={uploading}>
            Subir
          </Button>
          <Button variant="ghost" onClick={() => navigate('/budget')} disabled={!companyId}>
            Ir al plan anual
          </Button>
        </div>
      </div>

      {annualFlow ? (
        <div className="card section soft">
          <div className="mini-row row-baseline">
            <h3 className="m-0">Ajuste anual</h3>
            <span className="upload-hint">Solo toca esto si Plan anual no detecta bien los meses.</span>
          </div>
          <div className="grid grid-autofit-220 mt-12">
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Qué probar primero</div>
              <div className="fw-800 mt-1">
                {annualFocus === 'header' ? 'Cambiar fila de cabecera' : annualFocus === 'sheet' ? 'Elegir otra hoja' : 'Hoja y cabecera'}
              </div>
              <div className="upload-hint mt-1">
                Si no detecta meses ENERO..DICIEMBRE, el problema suele estar en la hoja elegida o en la fila tomada como encabezado.
              </div>
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Hoja</div>
              {isXlsx && xlsxPreview?.sheets?.length ? (
                <select
                  value={sheetIndex ?? xlsxPreview?.sheetIndex ?? 0}
                  onChange={(e) => setSheetIndex(Number(e.target.value))}
                  className="mt-8"
                >
                  {xlsxPreview.sheets.map((s, idx) => (
                    <option key={`${s}-${idx}`} value={idx}>
                      {idx + 1}. {s}
                    </option>
                  ))}
                </select>
              ) : (
                <div className="upload-hint mt-8">Selecciona primero un XLSX para elegir hoja.</div>
              )}
            </div>
            <div className="card soft card-pad-sm">
              <div className="upload-hint">Fila de cabecera</div>
              <input
                type="number"
                min={1}
                value={headerRow ?? xlsxPreview?.headerRow ?? ''}
                onChange={(e) => setHeaderRow(Number(e.target.value))}
                className="pipeline-period-input mt-8"
                disabled={!isXlsx}
              />
              <div className="upload-hint mt-8">Prueba con la fila donde realmente empiezan los nombres de columnas o meses.</div>
            </div>
          </div>
        </div>
      ) : null}

      <div className="card section soft">
        <div className="mini-row row-baseline">
          <h3 className="m-0">2. Estado del periodo</h3>
          <span className="upload-hint">Solo la lectura útil del último intento.</span>
        </div>
        <div className="grid grid-autofit-220 mt-12">
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Carga</div>
            <div className="fw-800 mt-1">{selectedPeriodFlow.importState.title}</div>
            <div className="upload-hint mt-1">{selectedPeriodFlow.importState.detail}</div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Lectura</div>
            <div className="fw-800 mt-1">{selectedPeriodFlow.reviewState.title}</div>
            <div className="upload-hint mt-1">{selectedPeriodFlow.reviewState.detail}</div>
          </div>
          <div className="card soft card-pad-sm">
            <div className="upload-hint">Entregable</div>
            <div className="fw-800 mt-1">{selectedPeriodFlow.reportState.title}</div>
            <div className="upload-hint mt-1">{selectedPeriodFlow.reportState.detail}</div>
          </div>
        </div>
      </div>

      {selectedPeriodImport ? (
        <div className="card section soft">
          <div className="mini-row row-baseline">
            <h3 className="m-0">3. Último import</h3>
            <span className="upload-hint">Solo lo justo para decidir si sigues o corriges.</span>
          </div>
          <div className="card soft card-pad-sm mt-12">
            <div className="row row-wrap gap-8 row-center">
              <span className={`badge ${selectedPeriodImport.status === 'OK' ? 'ok' : selectedPeriodImport.status === 'WARNING' ? 'warn' : 'err'}`}>
                {selectedPeriodImport.status}
              </span>
              <span className="fw-700">{selectedPeriodImport.filename}</span>
            </div>
            <div className="upload-hint mt-8">
              {selectedPeriodImport.blockingReason || selectedPeriodImport.errorSummary || selectedPeriodImport.lastError || 'Carga registrada para este periodo.'}
            </div>
            <div className="row row-wrap gap-8 mt-12">
              {(selectedPeriodImport.status === 'ERROR' || selectedPeriodImport.status === 'DEAD' || selectedPeriodImport.status === 'BLOCKED') ? (
                <Button size="sm" variant="secondary" onClick={() => handleRetry(selectedPeriodImport.id)}>
                  Reprocesar
                </Button>
              ) : null}
              <Button size="sm" variant="ghost" onClick={() => navigate(workflowHref)}>
                Abrir plan anual
              </Button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )

  return (
    <div className="imports-page">
      <PageHeader
        title="Cargar datos"
        subtitle="Sube un CSV/XLSX y EnterpriseIQ te guia al modulo correcto."
        actions={
          <>
            <span className="badge">
              {mode === 'auto'
                ? 'AUTO | detecta objetivo'
                : mode === 'transactions'
                ? 'Caja | txn_date + amount'
                : 'Universal | cualquier estructura'}
            </span>
          </>
        }
      />

      <div className="card section soft imports-flow-shell">
        <div className="imports-quick-head">
          <h3 className="m-0">Ruta de carga</h3>
          <span className="upload-hint">Primero decide modo y sube. El detalle queda debajo por si hace falta.</span>
        </div>
        <div className="grid grid-autofit-220 mt-12 imports-flow-grid">
          <div className="card soft card-pad-sm imports-flow-card">
            <div className="upload-hint">Situacion</div>
            <div className="fw-800 mt-1">{importDecisionState.title}</div>
            <div className="upload-hint mt-1">{importDecisionState.detail}</div>
          </div>
          <div className="card soft card-pad-sm imports-flow-card">
            <div className="upload-hint">Periodo activo</div>
            <div className="fw-800 mt-1">{period}</div>
            <div className="upload-hint mt-1">{selectedPeriodFlow.importState.detail}</div>
            <div className="upload-hint mt-8">
              Revision: {selectedPeriodFlow.reviewState.title} | Entregable: {selectedPeriodFlow.reportState.title}
            </div>
          </div>
          <div className="card soft card-pad-sm imports-flow-card">
            <div className="upload-hint">Siguiente paso</div>
            <div className="fw-800 mt-1">{importsNextAction.label}</div>
            <div className="upload-hint mt-1">{importsNextAction.detail}</div>
            {importsNextAction.kind === 'upload' ? (
              <div className="mt-2">
                <Button size="sm" variant="secondary" onClick={() => modeSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })}>
                  Elegir modo
                </Button>
              </div>
            ) : null}
            {importsNextAction.kind === 'exceptions' ? (
              <div className="mt-2">
                <Button size="sm" variant="secondary" onClick={() => exceptionSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })}>
                  Abrir excepciones
                </Button>
              </div>
            ) : null}
            {importsNextAction.kind === 'route' && importsNextAction.href ? (
              <div className="mt-2">
                <Button size="sm" variant="secondary" onClick={() => navigate(importsNextAction.href as string)}>
                  {importsNextAction.label}
                </Button>
              </div>
            ) : null}
          </div>
        </div>
      </div>

      <div className="card section soft imports-upload-shell" ref={modeSectionRef}>
        <div className="mini-row row-baseline mb-12">
          <h3 className="m-0">1. Elegir modo</h3>
          <span className="upload-hint">Solo decide la ruta. El resto de ajustes queda plegado.</span>
        </div>
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

        <details className="card soft compact-guide mt-3 imports-fold-panel">
          <summary className="upload-hint cursor-pointer">Ayuda y ejemplos</summary>
          <div className="card-pad-sm">
          <div className="mini-row row-baseline">
            <h3 className="m-0">Guia rapida</h3>
            <span className="upload-hint">La ayuda cambia segun el modo elegido.</span>
          </div>

          <div className="segmented mt-2" role="tablist" aria-label="Modulo">
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
              Tribunal esta disponible en planes GOLD/PLATINUM.
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
        </details>
      </div>

      {mode === 'transactions' && file && txPreview && Number(txPreview.confidence || 0) < 0.4 ? (
        <div className="section">
          <Alert tone="warning" title="Este fichero quiza no es de transacciones">
            La deteccion de columnas tiene poca confianza. Si es un presupuesto, ventas o inventario, usa el modo Universal.
            <div className="mt-2">
              <Button size="sm" variant="secondary" onClick={() => setMode('universal')}>
                Cambiar a Universal
              </Button>
            </div>
          </Alert>
        </div>
      ) : null}

      {mode === 'auto' && file && txPreview ? (
        <details className="card section soft imports-fold-panel">
          <summary>
            <div>
              <strong>Sugerencia automatica</strong>
              <div className="upload-hint">Solo abre este bloque si quieres dejar que AUTO te reoriente.</div>
            </div>
            <span className="badge">AUTO</span>
          </summary>
          <div className="imports-fold-body">
            <Alert tone="info" title="Sugerencia (auto)">
              {autoTarget === 'transactions'
                ? 'Parece un fichero de transacciones (Caja).'
                : autoTarget === 'tribunal'
                ? 'Parece un fichero de cumplimiento. Si no vas a trabajar ese modulo, sigue por Universal.'
                : 'Parece un dataset generico. Recomendado: Universal.'}
              <div className="upload-hint mt-8">
                Confianza transacciones: {Math.round(Number(txPreview.confidence || 0) * 100)}% | Senales Tribunal: {tribunalHint.score}
              </div>
              <div className="row row-wrap row-center gap-2 mt-2">
                <Button size="sm" variant={autoTarget === 'transactions' ? 'secondary' : 'ghost'} onClick={() => setMode('transactions')}>
                  Usar Caja
                </Button>
                <Button size="sm" variant={autoTarget === 'universal' || autoTarget === 'tribunal' ? 'secondary' : 'ghost'} onClick={() => setMode('universal')}>
                  Usar Universal
                </Button>
              </div>
            </Alert>
          </div>
        </details>
      ) : null}

      <div className="card section soft imports-upload-shell" ref={uploadSectionRef}>
        <div className="mini-row row-baseline mb-12">
          <h3 className="m-0">2. Subir fichero</h3>
          <span className="upload-hint">Carga el archivo y deja el detalle avanzado solo para los casos que lo necesiten.</span>
        </div>
        {!companyId ? (
          <Alert tone="warning" title="Falta seleccionar empresa gestionada">
            Selecciona una empresa gestionada arriba para subir el fichero.
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
                {xlsxLoading ? ' | previsualizando...' : ''}
                {xlsxPreview?.headers?.length ? ` | ${xlsxPreview.headers.length} columnas` : ''}
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
                  Tip: pon la fila donde estan ENERO...DICIEMBRE o txn_date...
                </span>
              </div>

              {xlsxPreview?.headers?.length ? (
                <div className="upload-hint mt-2">
                  Cabeceras detectadas: {xlsxPreview.headers.slice(0, 8).join(' | ')}
                  {xlsxPreview.headers.length > 8 ? ' | ...' : ''}
                </div>
              ) : null}

              {xlsxPreview?.sampleRows?.length ? (
                <div className="row row-wrap row-center gap-10 mt-2">
                  <Button type="button" size="sm" variant="ghost" onClick={() => setShowXlsxSample((s) => !s)}>
                    {showXlsxSample ? 'Ocultar preview' : 'Ver preview'}
                  </Button>
                  <span className="upload-hint">Muestra: 6 filas | 8 columnas</span>
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
                  {xlsxLoading ? 'Previsualizando...' : 'No se pudieron leer filas de muestra.'}
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
                {txPreviewLoading ? <span className="upload-hint">Analizando...</span> : null}
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
                toast.push({ tone: 'warning', title: 'Batch', message: 'Cancelacion solicitada. Se parara al terminar el fichero actual.' })
              }}
            >
              Cancelar
            </Button>
          ) : null}
        </div>
        {isBatch ? (
          <details className="mt-12">
            <summary className="upload-hint cursor-pointer">
              Subida por lotes ({batchFiles.length})
            </summary>
            <div className="upload-hint mt-8">
              Tip: si tus ficheros incluyen el periodo en el nombre (p. ej. <span className="mono">2026-03</span> o <span className="mono">202603</span>),
              se usara automaticamente al subir Caja.
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
                        <td className="mono upload-hint">{it.period || '-'}</td>
                        <td className="upload-hint">{it.target || '-'}</td>
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
            Si es un CSV de Excel con varias tablas o graficas, suele romperse al exportar. Mejor sube el <strong>XLSX original</strong> y usa el modo
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
            Universal es para analisis e insights; no recalcula KPIs de Caja.
          </div>
        )}
      </div>

      {mode === 'transactions' && companyId && file && txPreview ? (
        <details className="card section">
          <summary className="upload-hint cursor-pointer">Ajustar mapeo de columnas</summary>
          <div className="card-pad-sm">
          <h3 className="h3-reset">Asistente de mapeo (Caja)</h3>
          <div className="upload-hint">
            Selecciona que columnas significan <strong>fecha</strong> e <strong>importe</strong>. El resto es opcional.
          </div>
          <div className="upload-row mt-12">
            <label className="stack">
              <span className="upload-hint">Fecha (txn_date)</span>
              <select value={txnDateCol} onChange={(e) => setTxnDateCol(e.target.value)}>
                <option value="">-</option>
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
        </details>
      ) : null}

      <details className="imports-support-details" open={importsSupportOpen} ref={exceptionSectionRef}>
        <summary>
          <div>
            <div className="fw-700">Soporte operativo</div>
            <div className="upload-hint">Solo abre este bloque si necesitas resolver, reintentar o auditar una carga.</div>
          </div>
          <div className="row row-center row-wrap gap-8">
            <span className={`badge ${attentionSummary.blocked ? 'err' : 'ok'}`}>{attentionSummary.blocked} bloqueados</span>
            <span className={`badge ${attentionSummary.warnings ? 'warn' : 'ok'}`}>{attentionSummary.warnings} con avisos</span>
            <span className={`badge ${attentionSummary.technical ? 'err' : 'ok'}`}>{attentionSummary.technical} tecnicos</span>
          </div>
        </summary>
        <div className="imports-support-body">
          {!data?.length ? (
            <div className="card section">
              <div className="empty">Todavia no hay imports ni excepciones abiertas.</div>
            </div>
          ) : (
            <>
              <div className="card section soft">
                <div className="mini-row mt-0 row-between row-center row-wrap gap-8">
                  <div>
                    <div className="fw-700">Bandeja prioritaria</div>
                    <div className="upload-hint">
                      Prioriza bloqueos, avisos de calidad y fallos tecnicos antes de dar por bueno el periodo.
                    </div>
                  </div>
                  <span className="badge">{attentionImports.length} casos a revisar</span>
                </div>

                {!attentionImports.length ? (
                  <div className="empty mt-12">Sin excepciones.</div>
                ) : (
                  <div className="stack gap-8 mt-12">
                    {attentionImports.slice(0, 5).map(({ imp, top, entries }) => (
                      <div key={`attention-${imp.id}`} className="card soft card-pad-sm">
                        <div className="mini-row mt-0 row-between row-center row-wrap gap-8">
                          <div className="row row-center row-wrap gap-8">
                            <span className={`badge ${severityBadgeClass(top?.severity || 'LOW')}`}>{top?.severity || 'LOW'}</span>
                            <span className={`badge ${statusBadgeClass(imp.status)}`}>{imp.status}</span>
                            <span className="fw-700">
                              {imp.period}
                              {imp.versionNo ? ` · v${imp.versionNo}` : ''}
                            </span>
                            <span className="upload-hint">{imp.originalFilename || imp.storageRef || '-'}</span>
                          </div>
                          <Button size="sm" variant="ghost" onClick={() => setQualityOpenId((cur) => (cur === imp.id ? null : imp.id))}>
                            {qualityOpenId === imp.id ? 'Cerrar' : 'Abrir'}
                          </Button>
                        </div>
                        <div className="mt-8 fw-700">{top?.title || 'Revision pendiente'}</div>
                        <div className="upload-hint mt-1">{top?.detail || 'Hay incidencias abiertas en esta carga.'}</div>
                        <div className="upload-hint mt-8">
                          <strong>Siguiente paso:</strong> {top?.action || 'Revisar la carga.'}
                        </div>
                        {top?.evidence ? <div className="upload-hint mt-8">{top.evidence}</div> : null}
                        {entries.length > 1 ? <div className="upload-hint mt-8">{entries.length} incidencias principales en esta carga.</div> : null}
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {latestImport ? (
                <div className="card section soft">
                  <div className="mini-row row-center row-wrap">
                    <strong>Ultimo import</strong>
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
                            <span className={`badge ${statusBadgeClass(latestImport.status)}`}>{latestImport.status}</span>
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

              <div className="card section soft">
                <div className="mini-row row-baseline mb-12">
                  <h3 className="m-0">Historial operativo</h3>
                  <span className="upload-hint">Ultimas cargas, reintentos y detalle de calidad solo cuando necesites mirar atras.</span>
                </div>

                <div className="row row-between row-center row-wrap gap-8 fs-12 mt-2 mb-2">
                  <label className="upload-hint row row-center gap-8">
                    <input type="checkbox" checked={showAllImports} onChange={(e) => setShowAllImports(e.target.checked)} />
                    Mostrar mas
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
                        Incluir DEAD (tecnico)
                      </label>
                    </div>
                  </details>
                </div>

                <table className="table">
                  <thead>
                    <tr>
                      <th>Que paso</th>
                      <th>Situacion</th>
                      <th>Siguiente paso</th>
                    </tr>
                  </thead>
                  <tbody>
                    {importsForUi.map((imp: ImportJob) => {
                      const info = summarizeImport(imp)
                      const canRetry = (imp.status === 'ERROR' || imp.status === 'DEAD') && info.canRetry && !!imp.storageRef
                      const isQualityOpen = qualityOpenId === imp.id
                      const nextStep = importRowNextStep(imp)
                      return (
                        <Fragment key={imp.id}>
                          <tr key={imp.id}>
                            <td>
                              <div className="fw-700">{imp.period}</div>
                              <div className="row row-wrap row-center gap-8 mt-8">
                                <span className={`badge ${statusBadgeClass(imp.status)}`}>{imp.status}</span>
                                {imp.versionNo ? <span className="badge">v{imp.versionNo}</span> : null}
                                <span className="upload-hint">{new Date(imp.createdAt).toLocaleString()}</span>
                              </div>
                              <div className="upload-hint mt-8">{imp.originalFilename || imp.storageRef || '-'}</div>
                            </td>
                            <td className="maxw-460">
                              <div>{info.title}</div>
                              <div className="upload-hint mt-8">
                                {typeof imp.attempts === 'number' || typeof imp.maxAttempts === 'number'
                                  ? `Intentos ${imp.attempts ?? 0}/${imp.maxAttempts ?? 3}`
                                  : 'Sin detalle de reintentos'}
                              </div>
                              {info.raw ? (
                                <details className="mt-1">
                                  <summary className="upload-hint">Detalles técnicos</summary>
                                  <div className="upload-hint pre-wrap">{info.raw}</div>
                                </details>
                              ) : null}
                            </td>
                            <td className="upload-hint maxw-420 nowrap">
                              <div className="fw-700">{nextStep.title}</div>
                              <div className="upload-hint mt-8">{nextStep.detail}</div>
                              <div className="row row-wrap row-center gap-8 mt-8">
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  disabled={!imp.storageRef}
                                  onClick={() => setQualityOpenId((cur) => (cur === imp.id ? null : imp.id))}
                                >
                                  {isQualityOpen ? 'Cerrar detalle' : 'Ver detalle'}
                                </Button>
                                {imp.status === 'ERROR' || imp.status === 'DEAD' ? (
                                  <Button size="sm" disabled={!canRetry} onClick={() => handleRetry(imp.id)}>
                                    Reintentar
                                  </Button>
                                ) : null}
                                {info.showTemplate ? (
                                  <Button size="sm" variant="secondary" onClick={downloadTransactionsTemplate}>
                                    Plantilla CSV
                                  </Button>
                                ) : null}
                              </div>
                            </td>
                          </tr>
                          {isQualityOpen ? (
                            <tr key={`q-${imp.id}`}>
                              <td colSpan={3}>
                                <div className="card soft card-pad-sm mt-2">
                                  <div className="mini-row mt-0 row-between row-center row-wrap gap-8">
                                    <div className="row row-center gap-8">
                                      <span className="fw-700">Excepciones de esta carga</span>
                                      {qualityLoading ? <span className="upload-hint">Analizando…</span> : null}
                                      {!qualityLoading && quality ? (
                                        <span className={`badge ${qualitySummary(quality)?.badge || ''}`}>
                                          {qualitySummary(quality)?.label || 'OK'}
                                        </span>
                                      ) : null}
                                    </div>
                                    <div className="upload-hint">
                                      {quality?.minDate && quality?.maxDate ? `Rango detectado: ${quality.minDate} → ${quality.maxDate}` : ''}
                                    </div>
                                  </div>

                                  {qualityError ? (
                                    <Alert tone="danger">No se pudo cargar la calidad: {String((qualityError as any).message || qualityError)}</Alert>
                                  ) : null}

                                  <div className="grid grid-autofit-220 mt-12">
                                    <div className="card soft card-pad-sm">
                                      <div className="upload-hint">Situación</div>
                                      <div className="fw-700 mt-8">{imp.status}</div>
                                      <div className="upload-hint mt-8">
                                        {imp.period}
                                        {imp.versionNo ? ` · versión ${imp.versionNo}` : ''}
                                      </div>
                                    </div>
                                    <div className="card soft card-pad-sm">
                                      <div className="upload-hint">Calidad detectada</div>
                                      <div className="fw-700 mt-8">
                                        {qualityLoading ? 'Analizando…' : qualitySummary(quality)?.label || 'Sin incidencias'}
                                      </div>
                                      <div className="upload-hint mt-8">
                                        {quality?.minDate && quality?.maxDate ? `Rango ${quality.minDate} → ${quality.maxDate}` : 'Todavía no hay rango calculado'}
                                      </div>
                                    </div>
                                    <div className="card soft card-pad-sm">
                                      <div className="upload-hint">Qué haría ahora</div>
                                      <div className="fw-700 mt-8">{openImportDecisionState.title}</div>
                                      <div className="upload-hint mt-8">{openImportDecisionState.detail}</div>
                                    </div>
                                  </div>

                                  {openImportAllIssues.length ? (
                                    <div className="mt-12">
                                      <div className="fw-700 mb-1">Incidencias a resolver</div>
                                      {openImportAllIssues.map((entry, idx) => (
                                        <div key={`issue-${idx}`} className="card soft card-pad-sm mb-1">
                                          <div className="row row-center row-wrap gap-8">
                                            <span className={`badge ${severityBadgeClass(entry.severity)}`}>{entry.severity}</span>
                                            <span className="fw-700">{entry.title}</span>
                                          </div>
                                          <div className="upload-hint mt-8">{entry.detail}</div>
                                          <div className="upload-hint mt-8">
                                            <strong>Siguiente paso:</strong> {entry.action}
                                          </div>
                                          {entry.evidence ? <div className="upload-hint mt-8">{entry.evidence}</div> : null}
                                        </div>
                                      ))}
                                    </div>
                                  ) : (
                                    <div className="empty mt-12">Sin incidencias.</div>
                                  )}

                                  {quality ? (
                                    <details className="mt-12">
                                      <summary className="upload-hint cursor-pointer">Ver señales de calidad</summary>
                                      <div className="grid grid-autofit-180 mt-12">
                                        <div className="card soft card-pad-sm">
                                          <div className="upload-hint">Filas válidas</div>
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
                                    </details>
                                  ) : null}

                                  {quality?.examples?.length ? (
                                    <details className="mt-12">
                                      <summary className="upload-hint cursor-pointer">Ver evidencia y ejemplos</summary>
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
              </div>
            </>
          )}
        </div>
      </details>
    </div>
  )
}


