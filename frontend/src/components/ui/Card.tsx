import type { HTMLAttributes } from 'react'

type Props = HTMLAttributes<HTMLDivElement> & {
  variant?: 'default' | 'soft'
}

export default function Card({ variant = 'default', className = '', ...rest }: Props) {
  const cls = `card${variant === 'soft' ? ' soft' : ''}${className ? ` ${className}` : ''}`
  return <div {...rest} className={cls} />
}

