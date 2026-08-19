import { useState } from 'react'
import { createTicket } from './api.js'
import TicketFormFields, { buildTicketPayload, EMPTY_TICKET_FORM, validateTicketForm } from './TicketFormFields.jsx'
import { clearTicketDraft, loadTicketDraft, saveTicketDraft } from './ticketDraftStorage.js'

// Mirrors legacy's "new ticket" flow (TicketController#newTicket) — a modal popup (like the
// real app's), not an inline expanding panel. `initialForm` lets a caller prefill fields (e.g.
// the Sales Order page prefilling Order # and Channel) without changing the plain-list usage on
// the Ticket page, which omits it and gets EMPTY_TICKET_FORM as before. `withinSalesOrder` is
// forwarded to TicketFormFields — see its own comment (it hides the Order # field and locks the
// Channel picker to the prefilled channel), as is `orderLineItems`, the rows the item table
// seeds itself from once an item-bearing action is picked (see its own comment there).
export default function CreateTicketForm({ onCreated, initialForm = EMPTY_TICKET_FORM, withinSalesOrder = false, allowedChannels, orderLineItems }) {
  // Scoped per sales order (via its own order number) so the Sales Order page's own "+ New
  // Ticket" draft never bleeds into a different order's, or into the plain Ticket page's.
  const draftKey = withinSalesOrder ? `create:${initialForm.orderNumber || ''}` : 'create'

  const [open, setOpen] = useState(false)
  const [form, setForm] = useState(initialForm)
  const [errors, setErrors] = useState([])
  const [message, setMessage] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  function handleOpen() {
    setForm(loadTicketDraft(draftKey) ?? initialForm)
    setOpen(true)
  }

  function handleSubmit(e) {
    e.preventDefault()
    const validationErrors = validateTicketForm(form, 'create')
    if (validationErrors.length > 0) {
      setErrors(validationErrors)
      return
    }
    setErrors([])
    setSubmitting(true)
    setMessage(null)

    createTicket(buildTicketPayload(form))
      .then((created) => {
        clearTicketDraft(draftKey)
        setOpen(false)
        setForm(initialForm)
        onCreated?.(created)
      })
      .catch((err) => setMessage(err.message))
      .finally(() => setSubmitting(false))
  }

  // Fires on the modal's own close (X) / backdrop click — neither is a submit, so whatever was
  // typed is saved locally (never to the backend) and restored the next time this same draftKey
  // is opened, instead of being silently discarded.
  function handleClose() {
    saveTicketDraft(draftKey, form)
    setOpen(false)
    setForm(initialForm)
    setErrors([])
    setMessage(null)
  }

  return (
    <>
      <button type="button" className="create-ticket-toggle" onClick={handleOpen}>
        Create Ticket
      </button>

      {open && (
        <div className="modal-backdrop" onClick={handleClose}>
          <div className="modal modal-wide" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Create Ticket</h2>
              <button type="button" className="modal-close" onClick={handleClose}>×</button>
            </div>
            <form onSubmit={handleSubmit}>
              <TicketFormFields
                form={form}
                onChange={setForm}
                withinSalesOrder={withinSalesOrder}
                mode="create"
                allowedChannels={allowedChannels}
                orderLineItems={orderLineItems}
              />
              {errors.length > 0 && (
                <div className="error-banner">
                  <ul className="form-error-list">
                    {errors.map((error, i) => <li key={i}>{error}</li>)}
                  </ul>
                </div>
              )}
              {message && <div className="create-ticket-message">{message}</div>}
              {/* Legacy form has a single right-aligned Save button, no Cancel — the modal's
                  own close (X) / backdrop click cover dismissal. */}
              <div className="modal-actions">
                <button type="submit" className="create-ticket-submit" disabled={submitting}>
                  {submitting ? 'Saving…' : 'Save'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  )
}
