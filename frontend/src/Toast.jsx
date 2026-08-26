import { createContext, useCallback, useContext, useState } from 'react'

const ToastContext = createContext(null)

const TOAST_DURATION_MS = 2500

// Shared success/failure feedback for one-off actions (copy address, add comment, close
// ticket) that don't otherwise have a place to show a message — a plain modal already shows
// its own inline error, but these fire-and-forget row actions don't have a modal to put one in.
export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])

  const showToast = useCallback((message) => {
    const id = `${Date.now()}-${Math.random()}`
    setToasts((current) => [...current, { id, message }])
    setTimeout(() => {
      setToasts((current) => current.filter((t) => t.id !== id))
    }, TOAST_DURATION_MS)
  }, [])

  return (
    <ToastContext.Provider value={showToast}>
      {children}
      <div className="toast-stack">
        {toasts.map((t) => (
          <div key={t.id} className="toast">{t.message}</div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  return useContext(ToastContext)
}
