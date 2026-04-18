type Props = {
  title?: string
  see: string[]
  why: string[]
  todo: string[]
  className?: string
}

function Block({ label, items }: { label: string; items: string[] }) {
  return (
    <div className="chart-narrative-block">
      <div className="chart-narrative-k">{label}</div>
      {!items.length ? (
        <div className="upload-hint">-</div>
      ) : (
        <ul className="chart-narrative-list">
          {items.map((text) => (
            <li key={text}>{text}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default function ChartNarrative({ title = 'Lectura rapida', see, why, todo, className }: Props) {
  if (!see.length && !why.length && !todo.length) return null
  return (
    <div className={`chart-narrative ${className || ''}`.trim()}>
      <div className="chart-narrative-title">{title}</div>
      <div className="chart-narrative-grid">
        <Block label="Que veo" items={see} />
        <Block label="Por que importa" items={why} />
        <Block label="Que haria" items={todo} />
      </div>
    </div>
  )
}
