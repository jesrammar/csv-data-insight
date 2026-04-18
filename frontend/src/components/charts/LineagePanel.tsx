import type { UniversalChartData, UniversalImportLineageDto } from '../../api'

function fmtBytes(n?: number | null) {
  if (!n || !Number.isFinite(n)) return '-'
  const kb = 1024
  const mb = kb * 1024
  if (n >= mb) return `${(n / mb).toFixed(1)} MB`
  if (n >= kb) return `${(n / kb).toFixed(0)} KB`
  return `${n} B`
}

function fmtMs(n?: number | null) {
  if (n == null || !Number.isFinite(n)) return '-'
  if (n >= 1000) return `${(n / 1000).toFixed(1)} s`
  return `${Math.round(n)} ms`
}

function asStr(value: any) {
  const text = value == null ? '' : String(value)
  return text.trim()
}

function requestSummary(meta: Record<string, any> | null | undefined) {
  const req = (meta as any)?.request
  if (!req || typeof req !== 'object') return null
  const type = asStr((req as any).type)
  const cols = [
    asStr((req as any).dateColumn),
    asStr((req as any).categoryColumn),
    asStr((req as any).valueColumn),
    asStr((req as any).xColumn),
    asStr((req as any).yColumn)
  ].filter(Boolean)
  const agg = asStr((req as any).aggregation)
  const topN = (req as any).topN
  const maxPoints = (req as any).maxPoints
  return { type, cols, agg, topN, maxPoints }
}

export default function LineagePanel({
  lineage,
  chart,
  className
}: {
  lineage?: UniversalImportLineageDto | null
  chart?: UniversalChartData | null
  className?: string
}) {
  const meta = (chart as any)?.meta as Record<string, any> | undefined
  const req = requestSummary(meta)
  const analysis = lineage?.analysis || null
  const filters = Array.isArray((meta as any)?.filters) ? ((meta as any).filters as any[]) : null

  return (
    <details className={['mt-12', className].filter(Boolean).join(' ')}>
      <summary className="upload-hint cursor-pointer">Lineage completo (trazabilidad)</summary>

      <div className="grid grid-autofit-220 mt-12">
        <div className="card soft card-pad-sm">
          <div className="upload-hint">Fuente</div>
          <div className="fw-700">{lineage?.filename || asStr((meta as any)?.sourceFilename) || '-'}</div>
          <div className="upload-hint mt-1">
            Import {lineage?.importId ?? (meta as any)?.sourceImportId ?? '-'}
            {lineage?.createdAt ? ` · ${String(lineage.createdAt).slice(0, 19).replace('T', ' ')}` : ''}
          </div>
        </div>

        <div className="card soft card-pad-sm">
          <div className="upload-hint">Ingesta</div>
          <div className="fw-700">{analysis?.convertedFromXlsx ? 'XLSX -> CSV' : 'CSV'}</div>
          <div className="upload-hint mt-1">
            {analysis?.delimiter ? `Delim: ${analysis.delimiter}` : 'Delim: -'} · {analysis?.charsetName ? `Charset: ${analysis.charsetName}` : 'Charset: -'}
          </div>
        </div>

        <div className="card soft card-pad-sm">
          <div className="upload-hint">Procesado</div>
          <div className="fw-700">
            {fmtBytes(analysis?.bytes ?? null)} · {fmtMs(analysis?.durationMs ?? null)}
          </div>
          <div className="upload-hint mt-1">
            {analysis?.sampled ? 'Muestreado' : 'Completo'}
            {analysis?.removedEmptyColumns ? ` · Limpieza: ${analysis.removedEmptyColumns} col vacias` : ''}
          </div>
        </div>

        <div className="card soft card-pad-sm">
          <div className="upload-hint">Filas</div>
          <div className="fw-700">
            {analysis?.goodRows != null ? `${analysis.goodRows} ok` : '-'}
            {analysis?.badRows != null ? ` · ${analysis.badRows} malas` : ''}
          </div>
          <div className="upload-hint mt-1">
            {analysis?.totalRowsRead != null ? `Leidas: ${analysis.totalRowsRead}` : 'Leidas: -'}
            {analysis?.observedRows != null ? ` · Observadas: ${analysis.observedRows}` : ''}
          </div>
        </div>
      </div>

      {analysis?.xlsx?.sheetIndex != null || analysis?.xlsx?.headerRow1Based != null ? (
        <div className="upload-hint mt-12">
          XLSX: hoja {analysis.xlsx?.sheetIndex ?? '-'} · cabecera fila {analysis.xlsx?.headerRow1Based ?? '-'}
        </div>
      ) : null}

      {req ? (
        <div className="mt-12">
          <div className="upload-hint">Config del grafico</div>
          <div className="upload-hint mt-6">
            Tipo: <strong>{req.type || '-'}</strong>
            {req.agg ? ` · Agg: ${req.agg}` : ''}
            {req.topN != null ? ` · TopN: ${req.topN}` : ''}
            {req.maxPoints != null ? ` · MaxPoints: ${req.maxPoints}` : ''}
          </div>
          {req.cols?.length ? <div className="upload-hint mt-6">Columnas: {req.cols.join(' · ')}</div> : null}
        </div>
      ) : null}

      {filters && filters.length ? (
        <div className="mt-12">
          <div className="upload-hint">Filtros aplicados</div>
          <ul className="m-0 pl-18 mt-6">
            {filters.slice(0, 8).map((filter: any, idx: number) => (
              <li key={`${idx}`}>
                {asStr(filter?.column) || 'col'} {asStr(filter?.op) || 'eq'} {asStr(filter?.value) || ''}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </details>
  )
}
