import type { HTMLAttributes } from 'react'

type Props = HTMLAttributes<HTMLDivElement>

export default function Grid({ className = '', ...rest }: Props) {
  const cls = `grid${className ? ` ${className}` : ''}`
  return <div {...rest} className={cls} />
}

