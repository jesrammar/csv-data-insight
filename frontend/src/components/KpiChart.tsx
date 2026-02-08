import React from 'react'

type Point = { label: string; value: number }

type Props = {
  title: string
  points: Point[]
}

export default function KpiChart({ title, points }: Props) {
  if (!points.length) {
    return <div className="empty">Sin datos para graficar.</div>
  }

  const values = points.map((p) => p.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 1
  const padding = 16
  const width = 520
  const height = 180
  const innerW = width - padding * 2
  const innerH = height - padding * 2

  const coords = points.map((p, i) => {
    const x = padding + (innerW * i) / (points.length - 1 || 1)
    const y = padding + innerH - ((p.value - min) / range) * innerH
    return { x, y }
  })

  const path = coords.map((c, i) => `${i === 0 ? 'M' : 'L'} ${c.x} ${c.y}`).join(' ')

  return (
    <div>
      <h4 style={{ margin: '0 0 8px' }}>{title}</h4>
      <svg width="100%" height={height} viewBox={`0 0 ${width} ${height}`}>
        <defs>
          <linearGradient id="lineGrad" x1="0" x2="1" y1="0" y2="0">
            <stop offset="0%" stopColor="#0f4c5c" />
            <stop offset="100%" stopColor="#f59e0b" />
          </linearGradient>
        </defs>
        <rect x="0" y="0" width={width} height={height} fill="#f8fafc" rx="14" />
        <path d={path} fill="none" stroke="url(#lineGrad)" strokeWidth="3" />
        {coords.map((c, idx) => (
          <circle key={idx} cx={c.x} cy={c.y} r="4" fill="#0f4c5c" />
        ))}
      </svg>
      <div className="chart-labels">
        {points.map((p) => (
          <span key={p.label}>{p.label}</span>
        ))}
      </div>
    </div>
  )
}
