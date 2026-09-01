import { useEffect, useState } from 'react'
import { fetchTicketHistory } from './api.js'
import TicketHistoryTable from './TicketHistoryTable.jsx'

// Hibernate Envers ticket/comment/line-item history, read-only. TicketHistoryService
// already flattens Envers' per-revision entity snapshots into one row per changed property
// (who/class/property/event/old/new/when), so this just renders what the API returns. Shares
// TicketHistoryTable with the inline dropdown panel in App.jsx so the pop-out never drifts from
// the drop-down's own columns/formatting.
export default function TicketHistoryModal({ ticket, onClose }) {
  const [entries, setEntries] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [matchWidth, setMatchWidth] = useState(null)

  // The Pop out button only ever renders inside the expanded ticket row, so a .detail-section is
  // always in the DOM at this point — .history-panel is 95% of that same box (see index.css), so
  // measuring .detail-section directly (rather than its .ticket-detail parent, which carries its
  // own padding) matches the drop-down's actual rendered width instead of just approximating it.
  useEffect(() => {
    const section = document.querySelector('.detail-section')
    if (section) setMatchWidth(section.getBoundingClientRect().width * 0.95)
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    fetchTicketHistory(ticket.id)
      .then((data) => {
        if (!cancelled) setEntries(data)
      })
      .catch((err) => {
        if (!cancelled) setError(err.message)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [ticket.id])

  return (
    <div className="modal-backdrop" onClick={() => onClose?.()}>
      <div
        className="modal modal-history"
        style={matchWidth ? { maxWidth: matchWidth } : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <h2>History — Ticket #{ticket.id}</h2>
          <button type="button" className="modal-close" onClick={() => onClose?.()}>×</button>
        </div>

        <TicketHistoryTable entries={entries} loading={loading} error={error} />

        <div className="modal-actions">
          <button type="button" className="modal-cancel" onClick={() => onClose?.()}>Close</button>
        </div>
      </div>
    </div>
  )
}
