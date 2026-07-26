export function universalAggregationModeLabel(value: string | null | undefined) {
  const key = String(value || '').trim().toUpperCase()
  const labels: Record<string, string> = {
    ROW_COUNT: 'Filas',
    DISTINCT_ENTRY_COUNT: 'Asientos',
    DISTINCT_DOCUMENT_COUNT: 'Documentos',
    DISTINCT_INVOICE_COUNT: 'Facturas',
    DISTINCT_PARTY_COUNT: 'Terceros',
    SUM_DEBIT: 'Debe',
    SUM_CREDIT: 'Haber',
    NET_BALANCE: 'Saldo',
    SUM_AMOUNT: 'Importe',
    AVG_VALUE: 'Media'
  }
  return labels[key] || 'Modo pendiente'
}

export function universalAggregationExecutiveLine(value: string | null | undefined) {
  const key = String(value || '').trim().toUpperCase()
  const labels: Record<string, string> = {
    ROW_COUNT: 'Esta vista cuenta filas operativas.',
    DISTINCT_ENTRY_COUNT: 'Esta vista cuenta asientos distintos.',
    DISTINCT_DOCUMENT_COUNT: 'Esta vista cuenta documentos distintos.',
    DISTINCT_INVOICE_COUNT: 'Esta vista cuenta facturas distintas.',
    DISTINCT_PARTY_COUNT: 'Esta vista cuenta terceros distintos.',
    SUM_DEBIT: 'Esta vista suma importe en debe.',
    SUM_CREDIT: 'Esta vista suma importe en haber.',
    NET_BALANCE: 'Esta vista calcula saldo neto.',
    SUM_AMOUNT: 'Esta vista suma importe monetario.',
    AVG_VALUE: 'Esta vista calcula la media del valor.'
  }
  return labels[key] || 'Esta vista mantiene un modo oficial pendiente de lectura.'
}
