import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import Icon from './Icon'

export type ToastTone = 'info' | 'success' | 'warning' | 'danger'

export type Toast = {
  id: string
  tone: ToastTone
  title?: string
  message: string
  createdAt: number
}

type ToastApi = {
  push: (t: Omit<Toast, 'id' | 'createdAt'> & { id?: string }) => void
  clear: () => void
}

const ToastContext = createContext<ToastApi | null>(null)

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used inside ToastProvider')
  return ctx
}

export default function ToastProvider({ children }: { children: React.ReactNode }) {
  const [items, setItems] = useState<Toast[]>([])

  const push = useCallback((t: Omit<Toast, 'id' | 'createdAt'> & { id?: string }) => {
    const id = t.id || `${Date.now()}-${Math.random().toString(16).slice(2)}`
    const toast: Toast = { id, tone: t.tone, title: t.title, message: t.message, createdAt: Date.now() }
    setItems((prev) => [toast, ...prev].slice(0, 4))
    window.setTimeout(() => {
      setItems((prev) => prev.filter((x) => x.id !== id))
    }, 4500)
  }, [])

  const clear = useCallback(() => setItems([]), [])

  const api = useMemo(() => ({ push, clear }), [push, clear])

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-stack" aria-live="polite" aria-relevant="additions">
        {items.map((t) => (
          <div key={t.id} className={`toast toast-${t.tone}`}>
            <div className={`toast-icon toast-icon-${t.tone}`.trim()} aria-hidden="true">
              <Icon
                name={t.tone === 'success' ? 'check' : t.tone === 'warning' ? 'warning' : t.tone === 'danger' ? 'danger' : 'info'}
                size={18}
              />
            </div>
            <div className="toast-content">
              {t.title ? <div className="toast-title">{t.title}</div> : null}
              <div className="toast-body">{t.message}</div>
            </div>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

