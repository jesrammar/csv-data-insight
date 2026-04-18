import { useEffect, useRef, useState } from 'react'

type Props = {
  children: React.ReactNode
  className?: string
  delay?: 0 | 1 | 2 | 3
  once?: boolean
}

function delayClass(delay: Props['delay']) {
  switch (delay) {
    case 1:
      return 'reveal-delay-1'
    case 2:
      return 'reveal-delay-2'
    case 3:
      return 'reveal-delay-3'
    default:
      return 'reveal-delay-0'
  }
}

export default function Reveal({ children, className, delay = 0, once = true }: Props) {
  const elRef = useRef<HTMLDivElement | null>(null)
  const [inView, setInView] = useState(false)

  useEffect(() => {
    const el = elRef.current
    if (!el) return

    if (typeof window !== 'undefined' && 'matchMedia' in window) {
      const reduce = window.matchMedia('(prefers-reduced-motion: reduce)')
      if (reduce.matches) {
        setInView(true)
        return
      }
    }

    const io = new IntersectionObserver(
      (entries) => {
        const entry = entries[0]
        if (!entry) return
        if (entry.isIntersecting) {
          setInView(true)
          if (once) io.disconnect()
        } else if (!once) {
          setInView(false)
        }
      },
      { threshold: 0.14, rootMargin: '0px 0px -10% 0px' }
    )

    io.observe(el)
    return () => io.disconnect()
  }, [once])

  return (
    <div ref={elRef} className={`reveal ${delayClass(delay)} ${inView ? 'is-in' : ''} ${className || ''}`.trim()}>
      {children}
    </div>
  )
}

