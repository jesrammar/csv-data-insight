import type { ReactNode } from 'react'
import Icon from './Icon'

type Props = {
  tone?: 'info' | 'success' | 'warning' | 'danger'
  title?: string
  children: ReactNode
  className?: string
}

export default function Alert({ tone = 'info', title, children, className = '' }: Props) {
  const icon = tone === 'success' ? 'check' : tone === 'warning' ? 'warning' : tone === 'danger' ? 'danger' : 'info'
  return (
    <div className={`alert alert-${tone} ${className}`.trim()} role={tone === 'danger' ? 'alert' : undefined}>
      <div className="alert-inner">
        <div className={`alert-icon alert-icon-${tone}`.trim()} aria-hidden="true">
          <Icon name={icon as any} size={18} />
        </div>
        <div className="alert-content">
          {title ? <div className="alert-title">{title}</div> : null}
          <div className="alert-body">{children}</div>
        </div>
      </div>
    </div>
  )
}

