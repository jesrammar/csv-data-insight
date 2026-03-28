import React, { useMemo, useState } from 'react'

type Point = { label: string; value: number }

type Props = {
  title: string
  points: Point[]
  variant?: 'line' | 'area' | 'bar'
  valueSuffix?: string
}

export default function KpiChart({ title, points, variant = 'line', valueSuffix = '' }: Props) {
  if (!points.length) {
    return <div className="empty">Sin datos para graficar.</div>
  }

  const [hovered, setHovered] = useState<number | null>(null)
  const values = points.map((p) => p.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 1
  const avg = values.reduce((acc, v) => acc + v, 0) / values.length
  const padding = 16
  const width = 520
  const height = 180
  const innerW = width - padding * 2
  const innerH = height - padding * 2

  const coords = useMemo(() => {
    return points.map((p, i) => {
      const x = padding + (innerW * i) / (points.length - 1 || 1)
      const y = padding + innerH - ((p.value - min) / range) * innerH
      return { x, y }
    })
  }, [points, innerW, innerH, padding, min, range])

  const path = useMemo(() => {
    return coords.map((c, i) => `${i === 0 ? 'M' : 'L'} ${c.x} ${c.y}`).join(' ')
  }, [coords])

  const areaPath = `${path} L ${padding + innerW} ${padding + innerH} L ${padding} ${padding + innerH} Z`
  const avgY = padding + innerH - ((avg - min) / range) * innerH

  function handleMove(evt: React.MouseEvent<SVGSVGElement>) {
    const rect = evt.currentTarget.getBoundingClientRect()
    const x = evt.clientX - rect.left
    const idx = Math.round(((x - padding) / innerW) * (points.length - 1))
    const clamped = Math.max(0, Math.min(points.length - 1, idx))
    setHovered(clamped)
  }

  function formatValue(value: number) {
    const base = Number.isInteger(value) ? value.toString() : value.toFixed(2)
    return `${base}${valueSuffix}`
  }

  return (
    <div className="chart-wrap">
      <h4 style={{ margin: '0 0 8px' }}>{title}</h4>
      <svg
        width="100%"
        height={height}
        viewBox={`0 0 ${width} ${height}`}
        onMouseMove={handleMove}
        onMouseLeave={() => setHovered(null)}
      >
        <defs>
          <filter id="neonGlow" x="-40%" y="-40%" width="180%" height="180%">
            <feDropShadow dx="0" dy="0" stdDeviation="3" floodColor="#60a5fa" floodOpacity="0.45" />
            <feDropShadow dx="0" dy="0" stdDeviation="4" floodColor="#14b8a6" floodOpacity="0.22" />
          </filter>
          <linearGradient id="lineGrad" x1="0" x2="1" y1="0" y2="0">
            <stop offset="0%" stopColor="#14b8a6" />
            <stop offset="100%" stopColor="#60a5fa" />
          </linearGradient>
          <linearGradient id="areaGrad" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="rgba(20, 184, 166, 0.45)" />
            <stop offset="100%" stopColor="rgba(20, 184, 166, 0.02)" />
          </linearGradient>
        </defs>
        <rect
          x="0"
          y="0"
          width={width}
          height={height}
          fill="rgba(10, 16, 28, 0.72)"
          stroke="rgba(96, 165, 250, 0.18)"
          rx="14"
        />
        {[0.25, 0.5, 0.75].map((t, idx) => (
          <line
            key={idx}
            x1={padding}
            x2={padding + innerW}
            y1={padding + innerH * t}
            y2={padding + innerH * t}
            stroke="rgba(148, 163, 184, 0.2)"
            strokeDasharray="4 6"
          />
        ))}
        <line
          x1={padding}
          x2={padding + innerW}
          y1={avgY}
          y2={avgY}
          stroke="rgba(250, 204, 21, 0.55)"
          strokeDasharray="6 6"
        />
        {variant === 'bar' ? (
          coords.map((c, idx) => {
            const barW = innerW / (points.length || 1)
            const barH = padding + innerH - c.y
            return (
              <rect
                key={idx}
                x={c.x - barW / 2 + 1}
                y={c.y}
                width={barW - 2}
                height={barH}
                rx="6"
                fill="url(#lineGrad)"
                filter="url(#neonGlow)"
                opacity={hovered === idx ? 1 : 0.7}
              />
            )
          })
        ) : (
          <>
            {variant === 'area' && <path d={areaPath} fill="url(#areaGrad)" />}
            <path d={path} fill="none" stroke="url(#lineGrad)" strokeWidth="3" filter="url(#neonGlow)" />
            {coords.map((c, idx) => (
              <circle
                key={idx}
                cx={c.x}
                cy={c.y}
                r={hovered === idx ? 5 : 4}
                fill="#0ea5a4"
                opacity={hovered === idx ? 1 : 0.92}
              />
            ))}
          </>
        )}
        {hovered !== null ? (
          <g>
            <rect
              x={coords[hovered].x - 36}
              y={coords[hovered].y - 32}
              width="72"
              height="24"
              rx="6"
              fill="rgba(15, 23, 42, 0.95)"
              stroke="rgba(148, 163, 184, 0.35)"
            />
            <text
              x={coords[hovered].x}
              y={coords[hovered].y - 16}
              textAnchor="middle"
              fontSize="11"
              fill="#e2e8f0"
            >
              {formatValue(points[hovered].value)}
            </text>
          </g>
        ) : null}
      </svg>
      <div className="chart-labels">
        {points.map((p) => (
          <span key={p.label}>{p.label}</span>
        ))}
      </div>
      <div className="mini-row">
        <span>Min: {formatValue(min)}</span>
        <span>Media: {formatValue(avg)}</span>
        <span>Max: {formatValue(max)}</span>
      </div>
    </div>
  )
}
