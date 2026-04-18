type Props = {
  title?: string
  see: string[]
  why: string[]
  todo: string[]
  className?: string
  focusLabel?: string | null
}

function pick(items: string[]) {
  const list = Array.isArray(items) ? items : []
  for (const text of list) {
    const value = String(text || '').trim()
    if (value) return value
  }
  return '-'
}

export default function ExplainThisChart({ title = 'Explica este grafico', see, why, todo, className, focusLabel }: Props) {
  const bullet1 = pick(see)
  const bullet2 = pick(why)
  const bullet3 = pick(todo)

  return (
    <div className={`explain-panel ${className || ''}`.trim()}>
      <div className="row row-between row-center row-wrap gap-10">
        <div className="explain-title">{title}</div>
        {focusLabel ? <span className="badge">{focusLabel}</span> : null}
      </div>
      <ul className="explain-list">
        <li>
          <span className="explain-k">Que pasa</span>
          <span className="explain-v">{bullet1}</span>
        </li>
        <li>
          <span className="explain-k">Por que importa</span>
          <span className="explain-v">{bullet2}</span>
        </li>
        <li>
          <span className="explain-k">Que haria hoy</span>
          <span className="explain-v">{bullet3}</span>
        </li>
      </ul>
    </div>
  )
}
