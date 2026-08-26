import { useState } from 'react'
import { closeTicket } from './api.js'

// Mirrors legacy-app's #closeticketform modal (doCloseTicket): tracking number + a closing
// comment, both optional, submitted together with the status change to Closed.
export default function CloseTicketModal({ ticket, onClose }) {
  const [trackingNumber, setTrackingNumber] = useState(ticket.trackingNumber || '')
  const [comment, setComment] = useState('')
  const [message, setMessage] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  function handleSubmit(e) {
    e.preventDefault()
    setSubmitting(true)
    setMessage(null)

    closeTicket(ticket.id, { trackingNumber, comment })
      .then(() => onClose?.(true))
      .catch((err) => setMessage(err.message))
      .finally(() => setSubmitting(false))
  }

  return (
    <div className="modal-backdrop" onClick={() => onClose?.(false)}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Close Ticket #{ticket.id}</h2>
          <button type="button" className="modal-close" onClick={() => onClose?.(false)}>×</button>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="form-grid">
            <label>
              <span>Tracking #</span>
              <input type="text" value={trackingNumber} onChange={(e) => setTrackingNumber(e.target.value)} />
            </label>
          </div>
          <label className="description-field">
            <span>Comment</span>
            <textarea rows={4} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Optional closing note…" />
          </label>
          {message && <div className="create-ticket-message">{message}</div>}
          <div className="modal-actions">
            <button type="button" className="modal-cancel" onClick={() => onClose?.(false)}>Cancel</button>
            <button type="submit" className="create-ticket-submit" disabled={submitting}>
              {submitting ? 'Closing…' : 'Close Ticket'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
