export default function Skeleton({ style, className = '' }: { style?: React.CSSProperties; className?: string }) {
  return <div className={`skeleton ${className}`.trim()} style={style} aria-hidden="true" />
}

