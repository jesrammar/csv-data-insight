import type { ReactNode } from 'react'

export default function PageHeader({
  title,
  subtitle,
  actions
}: {
  title: string
  subtitle?: ReactNode
  actions?: ReactNode
}) {
  return (
    <div className="page-header">
      <div className="page-heading">
        <div className="page-kicker"><span aria-hidden="true" /> Centro de control</div>
        <div className="page-title-row">
          <h1 className="page-title">{title}</h1>
          <span className="page-title-accent" aria-hidden="true" />
        </div>
        {subtitle ? <div className="page-sub">{subtitle}</div> : null}
      </div>
      {actions ? <div className="page-actions">{actions}</div> : null}
    </div>
  )
}

