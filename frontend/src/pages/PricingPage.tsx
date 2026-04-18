import PageHeader from '../components/ui/PageHeader'
import Button from '../components/ui/Button'
import Section from '../components/ui/Section'
import PlanCapabilitiesTable from '../components/PlanCapabilitiesTable'
import RoleCapabilitiesTable from '../components/RoleCapabilitiesTable'

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
      'Panel KPI básico + tendencia mensual',
      'Punto muerto de caja (básico)',
      'Histórico 6 meses',
      'Informes mensuales (HTML) listos para compartir',
      'Soporte por email (48h objetivo)'
    ]
  },
  {
    key: 'gold',
    name: 'Gold',
    tag: 'Más elegido',
    price: '89',
    period: '/mes',
    subtitle: 'Diagnóstico más profundo para decisiones tácticas.',
    accentClass: 'price-accent-gold',
    cta: 'Subir a Gold',
    highlight: true,
    features: [
      'Tribunal (cumplimiento) + riesgos',
      'Drill-down de transacciones + analítica (sin export)',
      'Universal: correlaciones y análisis más profundo',
      'Histórico 12 meses + tendencia',
      'Insights y alertas priorizadas para consultoría',
      'Soporte prioritario (12h objetivo)'
    ]
  },
  {
    key: 'platinum',
    name: 'Platinum',
    tag: 'Enterprise',
    price: '169',
    period: '/mes',
    subtitle: 'Precisión ejecutiva con analítica de mayor profundidad.',
    accentClass: 'price-accent-platinum',
    cta: 'Hablar con ventas',
    features: [
      'Export transacciones (CSV) con detalle',
      'Export Power BI (ZIP) con detalle',
      'Asistente + informe consultivo',
      'Universal: CSV normalizado + preview de filas',
      'Histórico 24 meses',
      'Soporte dedicado (4h objetivo)'
    ]
  }
]

const faqs = [
  {
    q: '¿Puedo cambiar de plan en cualquier momento?',
    a: 'Sí. El cambio es inmediato y se recalculan los dashboards con el nuevo nivel.'
  },
  {
    q: '¿Hay permanencia?',
    a: 'No. Puedes cancelar cuando quieras.'
  },
  {
    q: '¿Qué incluye el asesoramiento?',
    a: 'Insights con explicación de riesgos, tendencias y oportunidades según tu plan.'
  },
  {
    q: '¿En qué plan está la exportación?',
    a: 'La exportación del detalle de transacciones (CSV) y el ZIP para Power BI con detalle están en PLATINUM.'
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
          {plans.map((plan) => (
            <article
              key={plan.key}
              className={`pricing-card card ${plan.highlight ? 'pricing-card-highlight' : ''}`}
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

      <RoleCapabilitiesTable
        title="Roles"
        subtitle="La consultora opera (CONSULTOR/ADMIN) y el cliente decide (CLIENTE). El plan aplica por empresa."
      />

      <PlanCapabilitiesTable
        title="Comparativa por plan"
        subtitle="Tabla rápida para entender qué capacidades se habilitan por empresa (exportaciones, asistente, etc.)."
      />

      <section className="pricing-signal section">
        <div className="card pricing-signal-card">
          <h3>Resultados que se notan</h3>
          <p>
            Aumenta la calidad del diagnóstico con datos consistentes, históricos largos y un lenguaje de consultoría
            accionable para tus clientes.
          </p>
          <div className="pricing-signal-grid">
            <div>
              <strong>+32%</strong>
              <span>De claridad en recomendaciones</span>
            </div>
            <div>
              <strong>-21%</strong>
              <span>Tiempo en revisión manual</span>
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
        <p>El SLA debe mantenerse como objetivo hasta contar con históricos y monitoreo estable.</p>
      </section>
    </div>
  )
}
