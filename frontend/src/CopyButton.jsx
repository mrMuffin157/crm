import { useToast } from './Toast.jsx'

// The one clipboard affordance in this app — the Ticket table's copy-address button and the
// Sales Order page's per-field copy icons are the same control with a different
// `value`, so the secure-context guard below lives here once rather than being re-derived at
// each call site.
//
// navigator.clipboard is undefined outside a secure context (plain HTTP), which this app's own
// docs treat as an accepted deployment mode — reading .writeText off it would throw
// synchronously, before any .catch() could attach, so it is checked before being touched.
//
// The click is always stopped from propagating: every current caller sits inside something
// clickable of its own (a ticket row that opens a detail panel), and copying a field is never
// also a request to open what surrounds it.
export default function CopyButton({ value, title = 'Copy', label = '📋' }) {
  const showToast = useToast()

  function handleClick(e) {
    e.stopPropagation()
    if (!value) return
    if (!navigator.clipboard) {
      showToast('Could not copy — clipboard is unavailable over plain HTTP.')
      return
    }
    navigator.clipboard.writeText(value)
      .then(() => showToast('Copied.'))
      .catch(() => showToast('Could not copy.'))
  }

  return (
    <button type="button" className="copy-address-button" onClick={handleClick} title={title}>
      {label}
    </button>
  )
}
