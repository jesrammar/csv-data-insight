import type { ReactNode } from 'react'

type Props = {
  label: string
  hint?: ReactNode
  children: ReactNode
  className?: string
}

export default function Field({ label, hint, children, className = '' }: Props) {
  return (
    <label className={`field ${className}`.trim()}>
      <span className="field-label">{label}</span>
      {children}
      {hint ? <span className="field-hint">{hint}</span> : null}
    </label>
  )
}

