import { useState } from 'react'
import { updateTicket } from './api.js'
import TicketFormFields, { buildTicketPayload, ticketToForm, validateTicketForm } from './TicketFormFields.jsx'
import { clearTicketDraft, loadTicketDraft, saveTicketDraft } from './ticketDraftStorage.js'

// Mirrors legacy's edit modal (TicketController#getTicket / #updateTicket): a pencil icon
// on each editable row opens this pre-filled form. Only ever opened for unlocked tickets
// (see the disabled edit button in App.jsx) — the backend also enforces this and rejects
// edits to Closed/Cancelled tickets with a 409, surfaced below via the error message.
export default function EditTicketModal({ ticket, onClose }) {
  // Keyed per ticket id so drafts for different tickets can't clobber each other. This modal is
  // only ever mounted while its ticket is being edited (App.jsx), so a stored draft from last
  // time this ticket was opened — and abandoned via close instead of submit — is picked up here
  // in place of the server's own current field values.
  const draftKey = `edit:${ticket.id}`
  const [form, setForm] = useState(() => loadTicketDraft(draftKey) ?? ticketToForm(ticket))
  const [errors, setErrors] = useState([])
  const [message, setMessage] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  function handleSubmit(e) {
    e.preventDefault()
    const validationErrors = validateTicketForm(form)
    if (validationErrors.length > 0) {
      setErrors(validationErrors)
      return
    }
    setErrors([])
    setSubmitting(true)
    setMessage(null)

    updateTicket(ticket.id, buildTicketPayload(form))
      .then(() => {
        clearTicketDraft(draftKey)
        onClose?.(true)
      })
      .catch((err) => setMessage(err.message))
      .finally(() => setSubmitting(false))
  }

  // Fires on the modal's own close (X) / backdrop click — neither is a submit, so whatever was
  // typed is saved locally (never to the backend) and restored next time this same ticket is
  // reopened, instead of being silently discarded.
  function handleClose() {
    saveTicketDraft(draftKey, form)
    onClose?.(false)
  }

  return (
    <div className="modal-backdrop" onClick={handleClose}>
      <div className="modal modal-wide" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Edit Ticket #{ticket.id}</h2>
          <button type="button" className="modal-close" onClick={handleClose}>×</button>
        </div>
        <form onSubmit={handleSubmit}>
          <TicketFormFields form={form} onChange={setForm} />
          {errors.length > 0 && (
            <div className="error-banner">
              <ul className="form-error-list">
                {errors.map((error, i) => <li key={i}>{error}</li>)}
              </ul>
            </div>
          )}
          {message && <div className="create-ticket-message">{message}</div>}
          {/* Legacy form has a single right-aligned submit button, no Cancel — the modal's
              own close (X) / backdrop click cover dismissal. */}
          <div className="modal-actions">
            <button type="submit" className="create-ticket-submit" disabled={submitting}>
              {submitting ? 'Updating…' : 'Update'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
