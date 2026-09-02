function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// Shared by the inline "Ticket History" dropdown panel (App.jsx) and the TicketHistoryModal
// pop-out, so the two views can't drift apart — same columns, same full (wrapped, untruncated)
// values either way.
export default function TicketHistoryTable({ entries, loading, error }) {
  if (loading) return <p className="detail-empty">Loading history…</p>
  if (error) return <p className="create-ticket-message">{error}</p>
  if (entries.length === 0) return <p className="detail-empty">No history recorded for this ticket yet.</p>

  return (
    <div className="table-scroll">
      <table className="history-table">
        <thead>
          <tr>
            <th>When</th>
            <th>Actor</th>
            <th>Class</th>
            <th>Event</th>
            <th>Property</th>
            <th>Old Value</th>
            <th>New Value</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry, i) => (
            <tr key={i}>
              <td>{formatDate(entry.dateCreated)}</td>
              <td>{entry.actor || '—'}</td>
              <td>{entry.className}</td>
              <td>{entry.eventName}</td>
              <td>{entry.propertyName}</td>
              <td>{entry.oldValue || '—'}</td>
              <td>{entry.newValue || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
