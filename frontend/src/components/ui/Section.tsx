import type { ReactNode } from 'react'

export default function Section({ title, subtitle, children }: { title?: string; subtitle?: string; children: ReactNode }) {
  return (
    <section className="section-block">
      {title ? (
        <div className="section-head">
          <h3 className="section-title">{title}</h3>
          {subtitle ? <p className="section-sub">{subtitle}</p> : null}
        </div>
      ) : null}
      {children}
    </section>
  )
}

