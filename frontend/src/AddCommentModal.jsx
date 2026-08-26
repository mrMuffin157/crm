import { useState } from 'react'
import { addComment } from './api.js'

// Mirrors legacy-app's #addcommentform modal (setTicket + AJAX POST /ticket/addComment).
// Viewing existing comments reuses the ticket list's own expandable detail row instead of
// legacy-app's separate showComments popover, since ticket.comments is already embedded in
// every ticket fetch (see TicketController#enrich) — nothing left to fetch there.
export default function AddCommentModal({ ticket, onClose }) {
  const [comment, setComment] = useState('')
  const [message, setMessage] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  function handleSubmit(e) {
    e.preventDefault()
    if (!comment.trim()) {
      setMessage('Comment text is required.')
      return
    }
    setSubmitting(true)
    setMessage(null)

    addComment(ticket.id, comment.trim())
      .then(() => onClose?.(true))
      .catch((err) => setMessage(err.message))
      .finally(() => setSubmitting(false))
  }

  return (
    <div className="modal-backdrop" onClick={() => onClose?.(false)}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Add Comment — Ticket #{ticket.id}</h2>
          <button type="button" className="modal-close" onClick={() => onClose?.(false)}>×</button>
        </div>
        <form onSubmit={handleSubmit}>
          <label className="description-field">
            <span>Comment</span>
            <textarea rows={4} value={comment} onChange={(e) => setComment(e.target.value)} autoFocus />
          </label>
          {message && <div className="create-ticket-message">{message}</div>}
          <div className="modal-actions">
            <button type="button" className="modal-cancel" onClick={() => onClose?.(false)}>Cancel</button>
            <button type="submit" className="create-ticket-submit" disabled={submitting}>
              {submitting ? 'Adding…' : 'Add Comment'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
