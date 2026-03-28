import type { ReactNode } from 'react'

type Props = {
  tone?: 'info' | 'success' | 'warning' | 'danger'
  title?: string
  children: ReactNode
  className?: string
}

export default function Alert({ tone = 'info', title, children, className = '' }: Props) {
  return (
    <div className={`alert alert-${tone} ${className}`.trim()} role={tone === 'danger' ? 'alert' : undefined}>
      {title ? <div className="alert-title">{title}</div> : null}
      <div className="alert-body">{children}</div>
    </div>
  )
}

