import type { TableHTMLAttributes } from 'react'

type Props = TableHTMLAttributes<HTMLTableElement> & {
  fixed?: boolean
  wrap?: boolean
}

export default function Table({ fixed, wrap = true, className = '', ...rest }: Props) {
  const cls = `table${fixed ? ' table-fixed' : ''}${className ? ` ${className}` : ''}`
  const table = <table {...rest} className={cls} />
  return wrap ? <div className="table-wrap">{table}</div> : table
}

