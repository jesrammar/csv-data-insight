import PageHeader from '../components/ui/PageHeader'
import Button from '../components/ui/Button'
import Section from '../components/ui/Section'

const plans = [
  {
    key: 'bronze',
    name: 'Bronze',
    tag: 'Esencial',
    price: '29',
    period: '/mes',
    subtitle: 'Visibilidad clara con lo imprescindible para operar.',
    accentClass: 'price-accent-bronze',
    cta: 'Empezar con Bronze',
    features: [
      'Panel KPI basico y tendencia mensual',
      'Punto muerto de caja (basico)',
      'Historico 6 meses',
      'Exportaciones CSV',
      'Soporte por email (48h objetivo)'
    ]
  },
  {
    key: 'gold',
    name: 'Gold',
    tag: 'Mas elegido',
    price: '89',
    period: '/mes',
    subtitle: 'Diagnostico mas profundo para decisiones tacticas.',
    accentClass: 'price-accent-gold',
    cta: 'Subir a Gold',
    highlight: true,
    features: [
      'Estadistica contable avanzada',
      'Punto muerto con volatilidad',
      'Historico 12 meses + pendiente de tendencia',
      'Insights y alertas para consultoria',
      'Soporte prioritario (12h objetivo)'
    ]
  },
  {
    key: 'platinum',
    name: 'Platinum',
    tag: 'Enterprise',
    price: '169',
    period: '/mes',
    subtitle: 'Precision ejecutiva con analitica predictiva.',
    accentClass: 'price-accent-platinum',
    cta: 'Hablar con ventas',
    features: [
      'Capa predictiva (forecast + estacionalidad)',
      'Benchmarking y senales estrategicas',
      'Historico 24 meses',
      'Informes de direccion',
      'Soporte dedicado (4h objetivo)'
    ]
  }
]

const matrix = [
  { item: 'Profundidad KPI', bronze: 'Core', gold: 'Avanzado', platinum: 'Experto + Forecast' },
  { item: 'Ventana historica', bronze: '6 meses', gold: '12 meses', platinum: '24 meses' },
  { item: 'Punto muerto', bronze: 'Caja basico', gold: 'Caja extendido', platinum: 'Extendido + predictivo' },
  { item: 'Insights', bronze: 'Basico', gold: 'Priorizado', platinum: 'Estrategico' },
  { item: 'Soporte objetivo', bronze: '48h', gold: '12h', platinum: '4h' }
]

const faqs = [
  {
    q: 'Puedo cambiar de plan en cualquier momento?',
    a: 'Si. El cambio es inmediato y se recalculan los dashboards con el nuevo nivel.'
  },
  {
    q: 'Hay permanencia?',
    a: 'No. Puedes cancelar cuando quieras.'
  },
  {
    q: 'Que incluye el asesoramiento?',
    a: 'Insights con explicacion de riesgos, tendencias y oportunidades segun tu plan.'
  }
]

export default function PricingPage() {
  return (
    <div className="pricing-shell">
      <div className="card soft pricing-hero-panel fade-up">
        <PageHeader
          title="Planes"
          subtitle="Mismo producto, distinta profundidad. Cobras por calidad: mejores gráficas, análisis y asesoramiento."
          actions={<span className="pricing-chip">EnterpriseIQ</span>}
        />
        <div className="pricing-hero-actions">
          <Button className="pricing-cta price-accent-gold">Ver demo en vivo</Button>
          <Button className="pricing-cta price-accent-bronze" variant="ghost">
            Solicitar propuesta
          </Button>
        </div>
      </div>

      <Section title="Tiers" subtitle="Diferencias claras para vender consultoría por impacto.">
        <div className="pricing-grid">
        {plans.map((plan, idx) => (
          <article
            key={plan.key}
            className={`pricing-card card ${plan.highlight ? 'pricing-card-highlight' : ''}`}
            style={{ animationDelay: `${idx * 120}ms` }}
          >
            <div className="pricing-card-top">
              <span className={`pricing-tag ${plan.accentClass}`}>{plan.tag}</span>
              <h2>{plan.name}</h2>
              <p className="pricing-card-sub">{plan.subtitle}</p>
            </div>

            <div className="pricing-price-row">
              <strong>{plan.price} EUR</strong>
              <span>{plan.period}</span>
            </div>

            <ul className="pricing-feature-list">
              {plan.features.map((feature) => (
                <li key={feature}>{feature}</li>
              ))}
            </ul>

            <Button className={`pricing-cta ${plan.accentClass}`}>{plan.cta}</Button>
          </article>
        ))}
        </div>
      </Section>

      <section className="card section pricing-matrix-card">
        <div className="pricing-matrix-head">
          <h3>Comparativa por calidad</h3>
          <p>Diferencias visibles y medibles en analisis y asesoramiento.</p>
        </div>
        <div className="pricing-table-wrap">
          <table className="table pricing-table">
            <thead>
              <tr>
                <th>Capacidad</th>
                <th>Bronze</th>
                <th>Gold</th>
                <th>Platinum</th>
              </tr>
            </thead>
            <tbody>
              {matrix.map((row) => (
                <tr key={row.item}>
                  <td>{row.item}</td>
                  <td>{row.bronze}</td>
                  <td>{row.gold}</td>
                  <td>{row.platinum}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="pricing-signal section">
        <div className="card pricing-signal-card">
          <h3>Resultados que se notan</h3>
          <p>
            Aumenta la calidad del diagnostico con datos consistentes, historicos largos
            y un lenguaje de consultoria accionable para tus clientes.
          </p>
          <div className="pricing-signal-grid">
            <div>
              <strong>+32%</strong>
              <span>De claridad en recomendaciones</span>
            </div>
            <div>
              <strong>-21%</strong>
              <span>Tiempo en revision manual</span>
            </div>
            <div>
              <strong>4h</strong>
              <span>Objetivo de soporte Platinum</span>
            </div>
          </div>
        </div>
      </section>

      <section className="pricing-faq section">
        <div className="card">
          <h3>Preguntas frecuentes</h3>
          <div className="pricing-faq-grid">
            {faqs.map((item) => (
              <div key={item.q} className="pricing-faq-item">
                <h4>{item.q}</h4>
                <p>{item.a}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className="pricing-footnote section">
        <p>
          El SLA debe mantenerse como objetivo hasta contar con historicos y monitoreo estable.
        </p>
      </section>
    </div>
  )
}
