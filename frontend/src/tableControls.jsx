// Shared table chrome — the 3-state sort indicator, the status badge/label helpers, and the
// sliding pagination window — used by the Tickets page (App.jsx) and the Sales Orders page
// (SalesOrdersPage.jsx) so the two stay visually/behaviorally identical rather than drifting
// apart as separate copies. The ticket table's own header CELL lives in ticketColumns.jsx
// instead: it carries the resize handle and the reorder menu, which are specific to that
// table's user-adjustable column layout.

import { statusLabel } from './ticketEnums.js'

// Keyed by Ticket.status, matching ticketEnums.js STATUS_LABELS. Still used by the Sales
// Orders page (SalesOrdersPage.jsx) — the ticket table's own Status column dropped the color
// pill in favor of plain text (see statusTableText below), but that page keeps it.
const STATUS_BADGE_CLASS = {
  New: 'badge-new',
  Processing: 'badge-processing',
  Closed: 'badge-closed',
  Cancelled: 'badge-cancelled',
  ProcessFailed: 'badge-processfailed',
}

// Shortened badge text so the Status column stays narrow — display only, doesn't affect
// filtering/sorting (which still key off statusLabel/STATUS_LABELS).
const STATUS_BADGE_TEXT = {
  New: 'New',
  Processing: 'In Prog',
  Closed: 'Closed',
  Cancelled: 'Cancel',
  ProcessFailed: 'Failed',
}

export function StatusBadge({ status }) {
  const label = statusLabel(status)
  if (!label) return <span className="badge badge-neutral">—</span>
  const badgeClass = STATUS_BADGE_CLASS[status] || 'badge-neutral'
  const badgeText = STATUS_BADGE_TEXT[status] ?? label
  return <span className={`badge ${badgeClass}`}>{badgeText}</span>
}

// Plain-text equivalent of StatusBadge's abbreviated label, with no color/pill — used by the
// ticket table's own Status column now that the color scheme moved to the Action column.
export function statusTableText(status) {
  const label = statusLabel(status)
  if (!label) return '—'
  return STATUS_BADGE_TEXT[status] ?? label
}

export function SortIcon({ direction }) {
  if (direction === 'asc') return <span className="sort-icon" title="Sorted ascending">▲</span>
  if (direction === 'desc') return <span className="sort-icon" title="Sorted descending">▼</span>
  return <span className="sort-icon sort-icon-inactive" title="Not sorted">⇅</span>
}

// Ideally 5 pages before the current one + current + 4 after = 10 consecutive numbers, but
// the window SLIDES (not just clips) to stay a full 10 near either edge — e.g. page 1 still
// shows pages 1-10, just shifted over, rather than shrinking down to 1-5.
export function computePageWindow(currentPageNum, totalPages) {
  const size = Math.min(10, totalPages)
  let start = currentPageNum - 5
  let end = currentPageNum + 4
  if (start < 1) {
    start = 1
    end = start + size - 1
  } else if (end > totalPages) {
    end = totalPages
    start = end - size + 1
  }
  const pages = []
  for (let p = start; p <= end; p++) pages.push(p)
  return pages
}

// Shared by the top toolbar and the bottom-of-page copy so both stay in lockstep — same
// window/First/Last logic, same jump field, same in-progress typed value. `leading` is an
// optional slot rendered ahead of the "Showing …" summary, on its own row under the page-number
// window — the Tickets page puts its Reset columns button there.
export function PaginationControls({ page, totalPages, totalElements, pageSize, setPage, pageInput, setPageInput, onJumpSubmit, leading }) {
  const currentPageNum = page + 1
  const windowPages = computePageWindow(currentPageNum, totalPages)
  const showFirst = windowPages[0] > 1
  const showLast = windowPages[windowPages.length - 1] < totalPages

  return (
    <div className="pagination">
      <div className="pagination-row">
        <div className="pagination-buttons">
          <button
            type="button"
            className={`page-number${showFirst ? '' : ' page-number-hidden'}`}
            disabled={!showFirst}
            onClick={() => setPage(0)}
          >
            1
          </button>
          <span className={`page-ellipsis page-gap-outer${showFirst ? '' : ' page-number-hidden'}`}>…</span>
          {windowPages.map((p, i) => {
            const gapClass = i === 0 ? ' page-gap-outer' : ''
            return p === currentPageNum ? (
              <span key={p} className={`page-current${gapClass}`}>{p}</span>
            ) : (
              <button
                key={p}
                type="button"
                className={`page-number${gapClass}`}
                onClick={() => setPage(p - 1)}
              >
                {p}
              </button>
            )
          })}
          <span className={`page-ellipsis page-gap-outer${showLast ? '' : ' page-number-hidden'}`}>…</span>
          <button
            type="button"
            className={`page-number page-gap-outer${showLast ? '' : ' page-number-hidden'}`}
            disabled={!showLast}
            onClick={() => setPage(totalPages - 1)}
          >
            {totalPages}
          </button>
        </div>

        <form className="page-jump-form" onSubmit={onJumpSubmit}>
          <input
            type="number"
            min={1}
            max={totalPages}
            value={pageInput}
            onChange={(e) => setPageInput(e.target.value)}
            placeholder="Page #"
            className="page-jump-input"
          />
          <button type="submit" className="page-jump-submit">Go</button>
        </form>
      </div>

      <div className="pagination-summary-row">
        {leading}
        <span className="pagination-summary">
          Showing {totalElements === 0 ? 0 : page * pageSize + 1} to {Math.min((page + 1) * pageSize, totalElements)} of {totalElements} records
        </span>
      </div>
    </div>
  )
}
