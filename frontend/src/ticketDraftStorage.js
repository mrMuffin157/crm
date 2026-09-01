// Best-effort per-browser draft persistence for the Create/Edit Ticket modals — never the
// backend, just localStorage, so a draft survives closing the modal (backdrop click / X) without
// submitting. localStorage can throw (private browsing, quota, disabled storage); a lost draft
// is an acceptable degradation, so every call swallows errors rather than breaking the form.
const PREFIX = 'ticket-draft:'

// A draft older than this is treated as abandoned rather than in-progress: reopening the same
// ticket/order long after walking away from it should start from the real current values (the
// ticket's own fields, or the sales order's seed) instead of resurrecting a forgotten edit.
const TTL_MS = 10 * 60 * 1000

export function loadTicketDraft(key) {
  try {
    const raw = localStorage.getItem(PREFIX + key)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed.savedAt !== 'number' || Date.now() - parsed.savedAt > TTL_MS) {
      localStorage.removeItem(PREFIX + key)
      return null
    }
    return parsed.form
  } catch {
    return null
  }
}

export function saveTicketDraft(key, form) {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify({ form, savedAt: Date.now() }))
  } catch {
    // ignore — best-effort only
  }
}

export function clearTicketDraft(key) {
  try {
    localStorage.removeItem(PREFIX + key)
  } catch {
    // ignore — best-effort only
  }
}
