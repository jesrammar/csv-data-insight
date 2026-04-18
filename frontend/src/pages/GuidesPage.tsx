import { useMemo } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import PageHeader from '../components/ui/PageHeader'
import Section from '../components/ui/Section'
import Alert from '../components/ui/Alert'
import Button from '../components/ui/Button'

type ModuleKey = 'caja' | 'tribunal' | 'universal' | 'presupuesto'

export default function GuidesPage() {
  const navigate = useNavigate()
  const [sp, setSp] = useSearchParams()
  const module = (sp.get('module') || 'caja') as ModuleKey

  const selected: ModuleKey = useMemo(() => {
    if (module === 'tribunal' || module === 'universal' || module === 'presupuesto') return module
    return 'caja'
  }, [module])

  const go = (m: ModuleKey) => {
    const next = new URLSearchParams(sp)
    next.set('module', m)
    setSp(next, { replace: true })
  }

  const hrefFor = (m: ModuleKey) => {
    if (m === 'caja') return '/samples/plantilla-caja-transacciones.csv'
    if (m === 'tribunal') return '/samples/plantilla-tribunal.csv'
    if (m === 'universal') return '/samples/plantilla-universal.csv'
    return '/samples/presupuesto-ejemplo.xlsx'
  }

  return (
    <div>
      <PageHeader
        title="Guías de carga"
        subtitle="Qué subir, ejemplos y cómo arreglar errores típicos. Pensado para trabajar rápido con clientes."
        actions={
          <Button variant="ghost" size="sm" onClick={() => navigate('/imports')}>
            Ir a Cargar datos
          </Button>
        }
      />

      <div className="segmented" role="tablist" aria-label="Módulos">
        <Button type="button" variant={selected === 'caja' ? 'secondary' : 'ghost'} size="sm" onClick={() => go('caja')}>
          Caja
        </Button>
        <Button type="button" variant={selected === 'tribunal' ? 'secondary' : 'ghost'} size="sm" onClick={() => go('tribunal')}>
          Tribunal
        </Button>
        <Button type="button" variant={selected === 'universal' ? 'secondary' : 'ghost'} size="sm" onClick={() => go('universal')}>
          Universal
        </Button>
        <Button
          type="button"
          variant={selected === 'presupuesto' ? 'secondary' : 'ghost'}
          size="sm"
          onClick={() => go('presupuesto')}
        >
          Presupuesto
        </Button>
      </div>

      <Alert tone="info" title="Workflow recomendado (2 minutos)">
        <div className="upload-hint mt-1">
          1) Descarga una plantilla · 2) Rellena 2–3 filas y valida cabecera/columnas · 3) Sube en “Cargar datos” y revisa el dashboard.
        </div>
        <div className="row row-wrap gap-10 mt-2">
          <a className="btn btn-secondary btn-sm" href={hrefFor(selected)} download>
            Descargar ejemplo
          </a>
          <Button variant="ghost" size="sm" onClick={() => navigate('/imports')}>
            Ir a Cargar datos
          </Button>
        </div>
      </Alert>

      {selected === 'caja' ? (
        <>
          <Section title="Qué subir" subtitle="Movimientos de banco/caja del periodo para KPIs, alertas y cashflow.">
            <div className="grid">
              <div className="card soft">
                <div className="fw-900">Formato mínimo</div>
                <div className="upload-hint mt-8">
                  CSV con cabecera y 2 columnas obligatorias:
                  <div className="mt-8">
                    <code className="code-inline">txn_date</code> (YYYY-MM-DD) · <code className="code-inline">amount</code> (decimal)
                  </div>
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Mejor si incluye</div>
                <div className="upload-hint mt-8">
                  <code className="code-inline">description</code>, <code className="code-inline">counterparty</code>,{' '}
                  <code className="code-inline">balance_end</code> para enriquecer insights (contrapartidas, saldos, anomalías).
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Buenas prácticas</div>
                <div className="upload-hint mt-8">
                  Un fichero por periodo (YYYY-MM). Evita tablas duplicadas y filas “de título” arriba.
                </div>
              </div>
            </div>
          </Section>

          <Section title="Ejemplo rápido" subtitle="Plantilla descargable (CSV).">
            <div className="card">
              <div className="mini-row">
                <div className="upload-hint">Úsalo como referencia para exportar desde banca/ERP.</div>
                <a className="btn btn-secondary btn-sm" href="/samples/plantilla-caja-transacciones.csv" download>
                  Descargar plantilla
                </a>
              </div>

              <pre className="code-block mt-12">
                <code>
                  {[
                    'txn_date,amount,description,counterparty,balance_end',
                    '2026-04-01,1250.00,"Cobro factura 123","Cliente X",15000.50',
                    '2026-04-02,-85.40,"Pago proveedor","Proveedor Y",14915.10'
                  ].join('\n')}
                </code>
              </pre>
            </div>
          </Section>

          <Section title="Errores humanos típicos" subtitle="Mensajes y cómo arreglarlos en 2 minutos.">
            <div className="grid">
              <div className="card soft">
                <div className="fw-900">“Faltan columnas fecha/importe”</div>
                <div className="upload-hint mt-8">
                  Solución: renombra cabeceras a <code className="code-inline">txn_date</code> y <code className="code-inline">amount</code> o usa
                  Universal si no son movimientos.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">“0 filas válidas”</div>
                <div className="upload-hint mt-8">
                  Suele ser fecha con formato raro (01/04/2026) o importes con separadores inconsistentes. Exporta como CSV UTF-8 y revisa que el
                  decimal sea consistente.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">“CSV malformado / no tabular”</div>
                <div className="upload-hint mt-8">
                  Hay varias tablas o filas de título. Limpia el Excel (una tabla) o sube el XLSX por Universal en modo guiado.
                </div>
              </div>
            </div>
          </Section>

          <Alert tone="info" title="Siguiente paso">
            <div className="row row-wrap gap-10 mt-2">
              <Button size="sm" variant="secondary" onClick={() => navigate('/imports?mode=transactions')}>
                Subir a Caja
              </Button>
              <Button size="sm" variant="ghost" onClick={() => navigate('/dashboard')}>
                Ver KPIs de Caja
              </Button>
            </div>
          </Alert>
        </>
      ) : null}

      {selected === 'tribunal' ? (
        <>
          <Section title="Qué subir" subtitle="CSV de cartera/seguimiento (cumplimiento, carga, minutas, flags).">
            <div className="grid">
              <div className="card soft">
                <div className="fw-900">Formato mínimo</div>
                <div className="upload-hint mt-8">
                  Cabeceras obligatorias: <code className="code-inline">cliente</code> y <code className="code-inline">cif</code>.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Campos típicos</div>
                <div className="upload-hint mt-8">
                  gestor, minutas, IRPF/DDCC/Libros, carga_de_trabajo, pct_contabilidad, promedio, nas2024…
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Consejo</div>
                <div className="upload-hint mt-8">
                  Mantén el CIF como texto (no número) para no perder ceros o letras.
                </div>
              </div>
            </div>
          </Section>

          <Section title="Ejemplo rápido" subtitle="Plantilla descargable (CSV).">
            <div className="card">
              <div className="mini-row">
                <div className="upload-hint">Útil para arrancar si el cliente “solo tiene Excel”.</div>
                <a className="btn btn-secondary btn-sm" href="/samples/plantilla-tribunal.csv" download>
                  Descargar plantilla
                </a>
              </div>

              <pre className="code-block mt-12">
                <code>
                  {[
                    'cliente,cif,gestor,minutas,irpf,ddcc,libros,carga_de_trabajo,pct_contabilidad,promedio,nas2024',
                    'Cliente Demo,ESB12345678,Ana,12.5,SI,OK,SI,0.8,0.35,4.2,3',
                    'Cliente Demo 2,ESA12345679,Carlos,0,NO,PENDIENTE,NO,0.2,0.10,2.1,0'
                  ].join('\n')}
                </code>
              </pre>
            </div>
          </Section>

          <Section title="Errores humanos típicos" subtitle="Lo que pasa en asesorías cada día.">
            <div className="grid">
              <div className="card soft">
                <div className="fw-900">CIF “se rompe”</div>
                <div className="upload-hint mt-8">
                  En Excel, marca la columna CIF como texto antes de exportar a CSV.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Cabeceras distintas (“Cliente”, “CIF”) con espacios</div>
                <div className="upload-hint mt-8">
                  Recomendación: usa cabeceras en minúsculas y sin tildes; si no, reexporta y valida en Universal.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Varias tablas en la misma hoja</div>
                <div className="upload-hint mt-8">
                  Deja una única tabla con cabecera clara o usa Universal con modo guiado.
                </div>
              </div>
            </div>
          </Section>

          <Alert tone="info" title="Siguiente paso">
            <div className="row row-wrap gap-10 mt-2">
              <Button size="sm" variant="secondary" onClick={() => navigate('/imports?mode=auto')}>
                Subir (Auto)
              </Button>
              <Button size="sm" variant="ghost" onClick={() => navigate('/tribunal')}>
                Ver dashboard Tribunal
              </Button>
            </div>
          </Alert>
        </>
      ) : null}

      {selected === 'universal' ? (
        <>
          <Section title="Qué subir" subtitle="Cualquier CSV/XLSX: ventas, inventario, salarios, presupuesto, etc.">
            <div className="grid">
              <div className="card soft">
                <div className="fw-900">Lo ideal</div>
                <div className="upload-hint mt-8">
                  Una tabla limpia con cabeceras (fila 1) y sin celdas combinadas.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Si el XLSX es “raro”</div>
                <div className="upload-hint mt-8">
                  Usa modo guiado: elige hoja y fila de cabecera. Esto evita que “lea” el logo o títulos.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Qué obtienes</div>
                <div className="upload-hint mt-8">
                  Tipos detectados, preview de filas, problemas, insights y posibilidad de construir vistas (Universal Views).
                </div>
              </div>
            </div>
          </Section>

          <Section title="Ejemplo rápido" subtitle="Dataset genérico (CSV).">
            <div className="card">
              <div className="mini-row">
                <div className="upload-hint">Sirve para probar el flujo Universal en 30 segundos.</div>
                <a className="btn btn-secondary btn-sm" href="/samples/plantilla-universal.csv" download>
                  Descargar plantilla
                </a>
              </div>

              <pre className="code-block mt-12">
                <code>{['date,metric,category', '2026-04-01,1200.5,ventas', '2026-04-02,980.0,ventas', '2026-04-03,110.2,devoluciones'].join('\n')}</code>
              </pre>
            </div>
          </Section>

          <Section title="Errores humanos típicos" subtitle="Y cómo resolverlos sin pelearse con Excel.">
            <div className="grid">
              <div className="card soft">
                <div className="fw-900">Varias hojas / varias tablas</div>
                <div className="upload-hint mt-8">
                  Modo guiado + seleccionar cabecera. Si sigue fallando, exporta solo la tabla a CSV.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Archivo enorme (lento/timeout)</div>
                <div className="upload-hint mt-8">
                  Divide por periodos o elimina columnas sin valor. Si solo quieres “ver columnas e insights”, recorta filas a una muestra representativa.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Cabeceras vacías (“Unnamed”)</div>
                <div className="upload-hint mt-8">
                  Rellena nombres de columna en Excel y vuelve a exportar. Universal depende de cabeceras para sugerencias.
                </div>
              </div>
            </div>
          </Section>

          <Alert tone="info" title="Siguiente paso">
            <div className="row row-wrap gap-10 mt-2">
              <Button size="sm" variant="secondary" onClick={() => navigate('/imports?mode=universal')}>
                Subir a Universal
              </Button>
              <Button size="sm" variant="ghost" onClick={() => navigate('/universal')}>
                Ver dashboard Universal
              </Button>
              <Button size="sm" variant="ghost" onClick={() => navigate('/universal/views')}>
                Universal Views
              </Button>
            </div>
          </Alert>
        </>
      ) : null}

      {selected === 'presupuesto' ? (
        <>
          <Section title="Qué subir" subtitle="Plantilla anual (XLSX) con meses en columnas (ENERO…DICIEMBRE).">
            <div className="grid">
              <div className="card soft">
                <div className="fw-900">Estructura esperada</div>
                <div className="upload-hint mt-8">
                  Una tabla con:
                  <ul className="list-steps mt-8">
                    <li>Una columna “etiqueta” (partida/concepto/código)</li>
                    <li>12 columnas de meses (ENERO…DICIEMBRE)</li>
                    <li>Importes numéricos (sin texto “€” pegado)</li>
                  </ul>
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Si el XLSX tiene títulos/logos arriba</div>
                <div className="upload-hint mt-8">
                  Usa Universal en modo guiado para indicar la fila de cabecera real. Luego valida en el Dashboard de Presupuesto con “Preview long”.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Qué obtienes</div>
                <div className="upload-hint mt-8">
                  Normalización a formato largo + insights (drivers, meses a cero, concentración top3) + PDF con recomendaciones.
                </div>
              </div>
            </div>
          </Section>

          <Section title="Ejemplo XLSX" subtitle="Un fichero realista para empezar sin pelearse con el formato.">
            <div className="card">
              <div className="mini-row">
                <div className="upload-hint">Descarga, edita 2–3 partidas y súbelo por Universal (modo guiado si hace falta).</div>
                <a className="btn btn-secondary btn-sm" href="/samples/presupuesto-ejemplo.xlsx" download>
                  Descargar ejemplo (XLSX)
                </a>
              </div>
              <div className="upload-hint mt-12">
                Tip: si tu Excel tiene títulos/logos arriba, en Universal selecciona la fila donde empiezan ENERO…DICIEMBRE.
              </div>
            </div>
          </Section>

          <Section title="Validación (pro)" subtitle="Antes de hablar con el cliente, comprueba que la lectura es correcta.">
            <div className="card">
              <div className="upload-hint">
                En el Dashboard Presupuesto:
                <ul className="list-steps mt-8">
                  <li>Botón “Preview long”: confirma columna etiqueta + meses + muestra de filas</li>
                  <li>Botón “CSV largo”: descarga y verifica que no hay meses desplazados</li>
                  <li>Sección “Insights accionables”: revisa top drivers, concentración, meses a cero</li>
                </ul>
              </div>
            </div>
          </Section>

          <Section title="Errores humanos típicos" subtitle="Los que más te van a aparecer en consultoría.">
            <div className="grid">
              <div className="card soft">
                <div className="fw-900">Meses en otra fila / cabecera no es la primera</div>
                <div className="upload-hint mt-8">
                  Solución: Universal (modo guiado) y selecciona la fila exacta donde están ENERO…DICIEMBRE.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Importes con símbolos / celdas como texto</div>
                <div className="upload-hint mt-8">
                  Limpia formato en Excel (valores numéricos) o reexporta. Si hay “€” pegado en la celda, puede romper detección.
                </div>
              </div>
              <div className="card soft">
                <div className="fw-900">Hay subtotales/total anual mezclados</div>
                <div className="upload-hint mt-8">
                  Deja subtotales fuera de la tabla principal o marca claramente la etiqueta (EnterpriseIQ intenta excluir filas tipo “TOTAL”).
                </div>
              </div>
            </div>
          </Section>

          <Alert tone="info" title="Siguiente paso">
            <div className="row row-wrap gap-10 mt-2">
              <Button size="sm" variant="secondary" onClick={() => navigate('/imports?mode=universal')}>
                Subir XLSX (Universal guiado)
              </Button>
              <Button size="sm" variant="ghost" onClick={() => navigate('/budget')}>
                Abrir Dashboard Presupuesto
              </Button>
            </div>
          </Alert>
        </>
      ) : null}
    </div>
  )
}
