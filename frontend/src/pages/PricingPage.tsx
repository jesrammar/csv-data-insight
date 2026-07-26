import PageHeader from '../components/ui/PageHeader'
import Button from '../components/ui/Button'
import Section from '../components/ui/Section'
import PlanCapabilitiesTable from '../components/PlanCapabilitiesTable'
import RoleCapabilitiesTable from '../components/RoleCapabilitiesTable'

const plans = [
  {
    key: 'bronze',
    name: 'Bronze',
    tag: 'Base',
    price: '29',
    period: '/mes',
    subtitle: 'Ordena y ve.',
    accentClass: 'price-accent-bronze',
    cta: 'Elegir Bronze',
    features: ['Caja y lectura básica', 'Cierre simple', 'Histórico 6 meses', 'Informe mensual', 'Soporte email']
  },
  {
    key: 'gold',
    name: 'Gold',
    tag: 'Pro',
    price: '89',
    period: '/mes',
    subtitle: 'Opera y dirige.',
    accentClass: 'price-accent-gold',
    cta: 'Elegir Gold',
    highlight: true,
    features: ['Tribunal y riesgos', 'Universal avanzado', 'Plan anual', 'Comparativa anual', 'Histórico 12 meses', 'Soporte prioritario']
  },
  {
    key: 'platinum',
    name: 'Platinum',
    tag: 'Auto',
    price: '169',
    period: '/mes',
    subtitle: 'Automatiza y escala.',
    accentClass: 'price-accent-platinum',
    cta: 'Hablar con ventas',
    features: ['Assistant consultivo', 'Exportaciones avanzadas', 'Universal completo', 'Automatización', 'Histórico 24 meses', 'Soporte dedicado']
  }
]

const faqs = [
  { q: '¿Puedo cambiar de plan?', a: 'Sí. El cambio es inmediato.' },
  { q: '¿Hay permanencia?', a: 'No.' },
  { q: '¿Qué cambia entre planes?', a: 'Base para ver, Gold para dirigir y Platinum para automatizar.' },
  { q: '¿Dónde está la exportación?', a: 'En Platinum.' }
]

export default function PricingPage() {
  return (
    <div className="pricing-shell">
      <div className="card soft pricing-hero-panel fade-up">
        <PageHeader title="Planes" subtitle="Más valor operativo, no más ruido." actions={<span className="pricing-chip">EnterpriseIQ</span>} />
        <div className="pricing-hero-actions">
          <Button className="pricing-cta price-accent-gold">Ver demo</Button>
          <Button className="pricing-cta price-accent-bronze" variant="ghost">
            Solicitar propuesta
          </Button>
        </div>
      </div>

      <Section title="Planes" subtitle="Base, dirección y automatización.">
        <div className="pricing-grid">
          {plans.map((plan) => (
            <article key={plan.key} className={`pricing-card card ${plan.highlight ? 'pricing-card-highlight' : ''}`}>
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

      <RoleCapabilitiesTable title="Roles" subtitle="La consultora opera. El cliente consulta. El plan aplica por empresa." />

      <PlanCapabilitiesTable title="Comparativa por plan" subtitle="Resumen rápido por empresa." />

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
    </div>
  )
}
