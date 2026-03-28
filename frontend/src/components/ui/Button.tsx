import type { ButtonHTMLAttributes } from 'react'

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
  size?: 'sm' | 'md'
  loading?: boolean
}

export default function Button({ variant = 'primary', size = 'md', loading, disabled, className = '', children, ...rest }: Props) {
  const cls = `btn btn-${variant} btn-${size} ${className}`.trim()
  return (
    <button {...rest} className={cls} disabled={disabled || loading} aria-busy={loading ? 'true' : undefined}>
      <span className="btn-label">
        {loading ? <span className="spinner" aria-hidden="true" /> : null}
        {children}
      </span>
    </button>
  )
}
