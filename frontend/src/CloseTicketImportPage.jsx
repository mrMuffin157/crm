import { useState } from 'react'
import { importCloseTickets } from './api.js'

// Close-ticket CSV bulk import (real format confirmed ) — the shipping-
// label vendor's export, the file CS actually gets back after uploading the Export Ticket
// Report CSV. Everyday CS work, reachable by any member, not just admins — see
// TicketCloseImportService's own comment for the full column layout and the row-by-row rules.
export default function CloseTicketImportPage() {
  const [file, setFile] = useState(null)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState(null)
  const [summary, setSummary] = useState(null)

  function handleSubmit(e) {
    e.preventDefault()
    if (!file) return
    setUploading(true)
    setError(null)
    setSummary(null)
    importCloseTickets(file)
      .then((result) => {
        setSummary(result)
        setFile(null)
        e.target.reset()
      })
      .catch((err) => setError(err.message))
      .finally(() => setUploading(false))
  }

  return (
    <>
      <header className="app-header">
        <h1>Close Ticket Import</h1>
      </header>

      <section className="create-ticket">
        <div className="create-ticket-form">
          <span className="detail-label">Upload the shipping-label vendor's CSV export</span>
          <p className="detail-empty">
            The file as downloaded from the label vendor — no reshaping needed. Rows that aren't
            Store US/CA/Deco/AU ticket rows are skipped automatically.
          </p>
          <form className="form-grid" onSubmit={handleSubmit}>
            <label>
              <input
                type="file"
                accept=".csv,text/csv"
                aria-label="CSV file"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                required
              />
            </label>
            <div className="checkbox-field" style={{ alignSelf: 'end' }}>
              <button type="submit" className="create-ticket-submit" disabled={uploading || !file}>
                {uploading ? 'Importing…' : 'Import'}
              </button>
            </div>
          </form>
          {error && <div className="error-banner">{error}</div>}
        </div>
      </section>

      {summary && (
        <section>
          <div className="empty-state-banner">
            {summary.totalRows} row(s): {summary.successCount} closed, {summary.failureCount} failed,{' '}
            {summary.skippedCount} not ticket rows.
          </div>
          {summary.rows.length > 0 && (
            <div className="table-scroll">
              <table className="detail-subtable">
                <thead>
                  <tr>
                    <th>Row</th>
                    <th>Ticket</th>
                    <th>Result</th>
                  </tr>
                </thead>
                <tbody>
                  {summary.rows.map((row) => (
                    <tr key={row.rowNumber}>
                      <td>{row.rowNumber}</td>
                      <td>{row.ticketId ?? '—'}</td>
                      <td>{outcomeLabel(row)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </>
  )
}

function outcomeLabel(row) {
  if (row.outcome === 'SUCCESS') return '✅ Closed'
  if (row.outcome === 'SKIPPED') return `⏭️ Skipped — ${row.message}`
  return `❌ ${row.message}`
}
