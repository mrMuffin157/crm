import { useEffect, useState } from 'react'
import { changeStatus, fetchTicketHistory, setOutOfWarrant, setStarred } from './api.js'
import { useToast } from './Toast.jsx'
import CopyButton from './CopyButton.jsx'
import TicketHistoryTable from './TicketHistoryTable.jsx'
import {
  ACTION_REFUND,
  actionLabel,
  isTicketLocked,
  reasonLabel,
  refundTypeLabel,
  sourceSystemShortLabel,
  stateAbbreviation,
  STATUS_CANCELLED,
} from './ticketEnums.js'
import { statusTableText } from './tableControls.jsx'
import {
  ColumnContextMenu,
  LEADING_COLUMN_WIDTHS,
  TicketColumnGroup,
  TicketColumnHeader,
} from './ticketColumns.jsx'
import { PREF_KEYS, usePersistedToggle } from './viewPreferences.js'

// off -> ascending -> descending -> off. Clicking a different column always starts it at
// ascending and drops whatever column was previously active — only one sort at a time. Shared
// by every page that owns its own sort state (the Tickets page drives a server refetch from
// it; the Sales Order page's Linked Tickets sorts its already-fully-loaded list locally — see
// sortTicketsLocally below).
export function nextSortState(prev, field) {
  if (prev.field !== field) return { field, dir: 'asc' }
  if (prev.dir === 'asc') return { field, dir: 'desc' }
  return { field: null, dir: null }
}

// Client-side counterpart of TicketRepositoryImpl.SORT_COLUMNS, for a table whose tickets are
// already fully loaded rather than paginated from the server (the Sales Order page's Linked
// Tickets table) — keyed by the same sortField values TICKET_COLUMNS itself uses.
const SORT_ACCESSORS = {
  id: (t) => t.id,
  reason: (t) => t.reason,
  orderNumber: (t) => t.orderNumber || '',
  state: (t) => t.state || '',
  action: (t) => t.action,
  status: (t) => t.status,
  lastUpdated: (t) => (t.lastUpdated ? new Date(t.lastUpdated).getTime() : 0),
}

export function sortTicketsLocally(tickets, sort) {
  const accessor = sort?.field && SORT_ACCESSORS[sort.field]
  if (!accessor) return tickets
  const sorted = [...tickets].sort((a, b) => {
    const av = accessor(a)
    const bv = accessor(b)
    return typeof av === 'string' || typeof bv === 'string'
      ? String(av).localeCompare(String(bv))
      : (av ?? 0) - (bv ?? 0)
  })
  return sort.dir === 'desc' ? sorted.reverse() : sorted
}

// Shortened action text for the ticket table's narrow Action column — display only, doesn't
// affect the Action filter dropdown (which still shows the real ACTION_LABELS values).
const ACTION_TABLE_TEXT = {
  Replacement: 'Replace',
  ManualHandling: 'Manual',
}

function actionTableText(action) {
  return ACTION_TABLE_TEXT[action] ?? actionLabel(action)
}

// Color scheme moved here from the Status column (which is now plain text) — per-Action
// pill colors: Replacement=blue, Refund=red, ManualHandling=green, NoAction=black/neutral.
const ACTION_BADGE_CLASS = {
  NoAction: 'badge-action-none',
  Replacement: 'badge-action-replacement',
  Refund: 'badge-action-refund',
  ManualHandling: 'badge-action-manual',
}

// State-plus-channel, in one cell. Pulled out of TicketRow's cell switch purely for
// readability — the layout decision below is the only real logic in it.
function SourceCell({ ticket }) {
  const sourceCodeRaw = sourceSystemShortLabel(ticket.sourceSystem)
  const sourceCode = sourceCodeRaw || '—'
  const stateAbbr = stateAbbreviation(ticket.state)
  const stateText = stateAbbr || '—'
  // One line (tab-separated) when each side is either 2 letters or empty (rendered as "—") —
  // anything longer (e.g. "Deco", "Taipei City") wraps/truncates too easily on one line, so it
  // drops to a stacked, state-on-top layout instead.
  const sourceFitsOneLine = !sourceCodeRaw || sourceCodeRaw.length === 2
  const stateFitsOneLine = !stateAbbr || stateAbbr.length === 2
  const compact = sourceFitsOneLine && stateFitsOneLine

  return (
    <span className={`source-cell${compact ? ' source-cell-compact' : ' source-cell-stacked'}`}>
      <span className="source-state">{stateText}</span>
      {compact && <span className="source-tab">{'\t'}</span>}
      <span className="source-code">{sourceCode}</span>
    </span>
  )
}

function ActionBadge({ action }) {
  const label = actionTableText(action)
  if (!label) return <span className="badge badge-neutral">—</span>
  const badgeClass = ACTION_BADGE_CLASS[action] || 'badge-neutral'
  return <span className={`badge ${badgeClass}`}>{label}</span>
}

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

// mm.dd.yy hh:mm:ss, 24-hour — compact format for the ticket table's Updated column.
function formatDateCompact(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n) => String(n).padStart(2, '0')
  const mm = pad(date.getMonth() + 1)
  const dd = pad(date.getDate())
  const yy = pad(date.getFullYear() % 100)
  const hh = pad(date.getHours())
  const min = pad(date.getMinutes())
  const ss = pad(date.getSeconds())
  return `${mm}.${dd}.${yy} ${hh}:${min}:${ss}`
}

// Refund figures render as "$4.00" everywhere a line item or its ticket total is shown, so
// the line-items cell and the expanded detail view can't drift into two different money
// formats. Not locale-aware on purpose: the amount is whatever legacy-app's own sales-order line
// carried, and this table mixes US/CA/AU/MX channels in one view, so a currency symbol chosen
// per browser locale would be a confident lie about which currency the number actually is.
function formatMoney(value) {
  const amount = Number(value)
  return Number.isFinite(amount) ? `$${amount.toFixed(2)}` : null
}

// A Refund line has no quantity to show, whatever the column happens to hold: a refund is an
// amount, not a count of things, and legacy-app's own list view renders a Refund line as
// "channelSKU : $amount" with no quantity anywhere. Gated on the ticket's **action**, the same
// axis the refund amounts themselves are gated on above — all 5,202 migrated Refund rows store a
// literal 0 (legacy-app's refund path never wrote one), and the handful CRM's own form sent a real
// quantity for before it dropped its Qty column are just as irrelevant to read. The > 0 guard
// still covers every other action, none of which has ever stored a 0.
function lineItemQuantity(item, isRefund) {
  if (isRefund) return null
  const quantity = Number(item.quantity)
  return Number.isFinite(quantity) && quantity > 0 ? quantity : null
}

function commentCount(ticket) {
  const count = ticket.comments?.length ?? 0
  return count > 0 ? String(count) : '—'
}

// Address line 1, address line 2 (if present), then city/state/zip on one line —
// shared by the copy-address button (which prepends a name line) and the detail view.
function formatAddressLines(address) {
  if (!address) return []
  const lines = []
  if (address.addressLine1) lines.push(address.addressLine1)
  if (address.addressLine2) lines.push(address.addressLine2)
  const cityStateZip = [address.city, address.state].filter(Boolean).join(', ')
  const lastLine = [cityStateZip, address.zipCode].filter(Boolean).join(' ')
  if (lastLine) lines.push(lastLine)
  return lines.filter(Boolean)
}

// Mirrors legacy-app's copyAddress button (_list.gsp addressArray): name, address line 1,
// address line 2 (if present), then city/state/zip on one line — one clipboard line each.
function buildAddressLines(ticket) {
  const address = ticket.address
  if (!address) return []
  const name = address.name || ticket.customerName
  return [name, ...formatAddressLines(address)].filter(Boolean)
}

function TicketRow({ ticket, columns, rowIndex, expanded, onToggle, onEdit, onToggled, onCloseTicket, onAddComment, onHistory, onOpenSalesOrder }) {
  const locked = isTicketLocked(ticket.status)
  // A Refund ticket is the only one whose line items carry money: every other action's rows
  // always store a 0 refundAmount (see TicketLineItem#refundAmount), so gating on the action —
  // the same test the detail view's Refund Type row already uses — rather than on "amount > 0"
  // keeps a genuinely-zero Refund line visible instead of silently dropping its price.
  const isRefund = ticket.action === ACTION_REFUND
  const showToast = useToast()

  const [historyOpen, setHistoryOpen] = useState(false)
  const [historyLoaded, setHistoryLoaded] = useState(false)
  const [historyEntries, setHistoryEntries] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState(null)

  useEffect(() => {
    if (!historyOpen || historyLoaded) return
    let cancelled = false
    setHistoryLoading(true)
    setHistoryError(null)
    fetchTicketHistory(ticket.id)
      .then((data) => {
        if (cancelled) return
        setHistoryEntries(data)
        setHistoryLoaded(true)
      })
      .catch((err) => {
        if (!cancelled) setHistoryError(err.message)
      })
      .finally(() => {
        if (!cancelled) setHistoryLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [historyOpen, historyLoaded, ticket.id])

  // Mirrors legacy-app's Cancel button: a direct status change (no modal, unlike Close, which
  // collects a tracking number/comment first — see CloseTicketModal), but still confirms
  // first since it's a one-way move to a locked status.
  function handleCancelClick(e) {
    e.stopPropagation()
    if (!window.confirm(`Cancel ticket #${ticket.id}? This can't be undone.`)) return
    changeStatus(ticket.id, STATUS_CANCELLED)
      .then(() => {
        onToggled?.()
        showToast('Ticket cancelled.')
      })
      .catch((err) => showToast(err.message))
  }

  function handleCloseClick(e) {
    e.stopPropagation()
    onCloseTicket(ticket)
  }

  function handleAddCommentClick(e) {
    e.stopPropagation()
    onAddComment(ticket)
  }

  function handleHistoryClick(e) {
    e.stopPropagation()
    onHistory(ticket)
  }

  function handleHistoryToggle(e) {
    e.stopPropagation()
    setHistoryOpen((open) => !open)
  }

  function handleStarClick(e) {
    e.stopPropagation()
    setStarred(ticket.id, !ticket.starred)
      .then(() => onToggled?.())
      .catch((err) => window.alert(err.message))
  }

  // Plain boolean marker — "this item's warranty has expired" — no other automatic
  // behavior tied to it (no emails, no status changes), same toggle pattern as starred.
  function handleOutOfWarrantClick(e) {
    e.stopPropagation()
    setOutOfWarrant(ticket.id, !ticket.outOfWarrant)
      .then(() => onToggled?.())
      .catch((err) => window.alert(err.message))
  }

  function handleEditClick(e) {
    e.stopPropagation()
    onEdit(ticket)
  }

  function handleOpenSalesOrderClick(e) {
    e.stopPropagation()
    onOpenSalesOrder(ticket.orderNumber)
  }

  const totalColumnCount = LEADING_COLUMN_WIDTHS.length + columns.length + 1

  // One cell body per reorderable column key (see TICKET_COLUMNS in ticketColumns.jsx). The
  // cells are rendered from the live column order rather than written out in fixed source
  // order, so a reorder can't leave a header pointing at someone else's content — the <td>'s
  // own class/width comes from the same column definition the <th> used.
  function renderCell(key) {
    switch (key) {
      case 'id':
        return (
          <>
            {ticket.id}
          </>
        )
      case 'reason':
        return reasonLabel(ticket.reason) || '—'
      case 'description':
        return <span className="description-clamp">{ticket.description || '—'}</span>
      case 'orderNumber':
        return (
          <>
            <div className="order-number">
              {ticket.orderNumber && onOpenSalesOrder ? (
                <button
                  type="button"
                  className="link-button"
                  onClick={handleOpenSalesOrderClick}
                  title="Look up this order on the Sales Order page"
                >
                  {ticket.orderNumber}
                </button>
              ) : (ticket.orderNumber || '—')}
            </div>
            <div className="order-customer-line">
              {ticket.address && (
                <CopyButton
                  value={buildAddressLines(ticket).join('\r\n')}
                  title="Copy customer address"
                />
              )}
              <span className="order-customer-name">{ticket.customerName || '—'}</span>
            </div>
          </>
        )
      case 'lineItems':
        if (!(ticket.lineItems?.length > 0)) return '—'
        return (
          <>
            {ticket.lineItems.map((item, i) => {
              const quantity = lineItemQuantity(item, isRefund)
              return (
                <div key={i} className="line-item-summary">
                  {quantity != null && <span className="line-item-summary-qty">{quantity}x</span>}
                  {isRefund && (
                    <span className="line-item-summary-price">{formatMoney(item.refundAmount) ?? '—'}</span>
                  )}
                  <span className="line-item-summary-sku">{item.sku || '—'}</span>
                  <span className="line-item-summary-name">{item.name || '—'}</span>
                </div>
              )
            })}
            {isRefund && (
              <div className="line-item-summary-total">
                (Total Refund: {formatMoney(ticket.totalRefundAmount) ?? '—'})
              </div>
            )}
          </>
        )
      case 'source':
        return <SourceCell ticket={ticket} />
      case 'action':
        return <ActionBadge action={ticket.action} />
      case 'status':
        return statusTableText(ticket.status)
      case 'lastUpdated':
        return (
          <>
            <div className="id-updated-line">{ticket.handlerUsername || '—'}</div>
            <div className="id-updated-line">{formatDateCompact(ticket.lastUpdated)}</div>
          </>
        )
      default:
        return null
    }
  }

  return (
    <>
      <tr
        className={`ticket-row${locked ? ' ticket-row-locked' : ''}${rowIndex % 2 === 1 ? ' ticket-row-odd' : ''}`}
        onClick={() => onToggle(ticket.id)}
      >
        <td className="col-quick-actions" onClick={(e) => e.stopPropagation()}>
          <button
            type="button"
            className="edit-button"
            onClick={handleEditClick}
            title="Edit ticket"
            disabled={locked}
          >
            ✎
          </button>
          <span className="star-toggle" onClick={handleStarClick} title="Toggle starred">
            {ticket.starred ? '★' : '☆'}
          </span>
          <span
            className={`lightbulb-toggle${ticket.outOfWarrant ? ' lightbulb-lit' : ' lightbulb-dim'}`}
            onClick={handleOutOfWarrantClick}
            title="Toggle out of warranty"
          >
            💡
          </span>
        </td>
        <td className="col-lock-actions">
          {locked ? (
            <span className="lock-indicator" title="Closed/cancelled tickets are read-only">🔒</span>
          ) : (
            <span className="status-actions">
              <button type="button" className="status-close-button" onClick={handleCloseClick} title="Close ticket">
                ✓
              </button>
              <button type="button" className="status-cancel-button" onClick={handleCancelClick} title="Cancel ticket">
                ✕
              </button>
            </span>
          )}
        </td>
        <td className="col-comments">
          <span className="comment-count-cell">
            <span className={ticket.comments?.length > 0 ? 'comment-count-number' : undefined}>
              {commentCount(ticket)}
            </span>
            <button type="button" className="add-comment-button" onClick={handleAddCommentClick} title="Add comment">
              💬
            </button>
          </span>
        </td>
        {columns.map((column) => (
          <td
            key={column.key}
            className={column.cellClassName}
            title={column.key === 'description' ? (ticket.description || '') : undefined}
          >
            {renderCell(column.key)}
          </td>
        ))}
        <td className="col-spacer"></td>
      </tr>
      {expanded && (
        <tr className={`ticket-detail-row${locked ? ' ticket-row-locked' : ''}`}>
          <td colSpan={totalColumnCount}>
            <div className="ticket-detail">
              {ticket.description && (
                <p>
                  <span className="detail-label">Description</span>
                  {ticket.description}
                </p>
              )}
              <div className="detail-grid">
                <div><span className="detail-label">Customer Name</span>{ticket.customerName || '—'}</div>
                <div><span className="detail-label">Email</span>{ticket.email || '—'}</div>
                <div>
                  <span className="detail-label">Order #</span>
                  {ticket.orderNumber && onOpenSalesOrder ? (
                    <button
                      type="button"
                      className="link-button"
                      onClick={handleOpenSalesOrderClick}
                      title="Look up this order on the Sales Order page"
                    >
                      {ticket.orderNumber}
                    </button>
                  ) : (ticket.orderNumber || '—')}
                </div>
                {isRefund && (
                  <>
                    <div><span className="detail-label">Refund Type</span>{refundTypeLabel(ticket.refundType) || '—'}</div>
                    <div><span className="detail-label">Total Refund Amount</span>{formatMoney(ticket.totalRefundAmount) ?? '—'}</div>
                  </>
                )}
                <div><span className="detail-label">Tracking #</span>{ticket.trackingNumber || '—'}</div>
                <div><span className="detail-label">Date Created</span>{formatDate(ticket.dateCreated)}</div>
                <div><span className="detail-label">Out of Warranty</span>{ticket.outOfWarrant ? 'Yes' : 'No'}</div>
                <div>
                  <span className="detail-label">Address</span>
                  {ticket.address ? (
                    formatAddressLines(ticket.address).map((line, i) => <div key={i}>{line}</div>)
                  ) : '—'}
                </div>
                <div><span className="detail-label">Replacement Order #</span>{ticket.replacementOrderNumber || '—'}</div>
              </div>

              {ticket.lineItems?.length > 0 && (
                <div className="detail-section">
                  <span className="detail-label">Line Items</span>
                  <ul className="line-item-list">
                    {ticket.lineItems.map((item, i) => {
                      const quantity = lineItemQuantity(item, isRefund)
                      return (
                        <li key={i}>
                          {isRefund && (
                            <span className="line-item-refund">{formatMoney(item.refundAmount) ?? '—'}</span>
                          )}
                          <span className="line-item-sku">{item.sku || '—'}</span>
                          <span className="line-item-name">{item.name || '—'}</span>
                          {quantity != null && <span className="line-item-qty">× {quantity}</span>}
                        </li>
                      )
                    })}
                  </ul>
                </div>
              )}

              <div className="detail-section">
                <div className="detail-section-header">
                  <span className="detail-label">Comments</span>
                  <button type="button" className="add-comment-detail-button" onClick={handleAddCommentClick} title="Add comment">
                    💬 Add comment
                  </button>
                </div>
                {ticket.comments?.length > 0 ? (
                  <ul className="comment-list">
                    {ticket.comments.map((comment, i) => (
                      <li key={i}>
                        <div className="comment-meta">
                          <strong>{comment.authorUsername || 'Unknown'}</strong> · {formatDate(comment.dateCreated)}
                        </div>
                        <div className="comment-text">{comment.comment}</div>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="detail-empty">No comments yet.</p>
                )}
              </div>

              <div className="detail-section">
                <div className="detail-section-header">
                  <button type="button" className="history-disclosure" onClick={handleHistoryToggle} aria-expanded={historyOpen}>
                    <span className="detail-label">Ticket History</span>
                    <span className={`history-disclosure-arrow${historyOpen ? ' history-disclosure-arrow-open' : ''}`}>▼</span>
                  </button>
                  <button type="button" className="add-comment-detail-button" onClick={handleHistoryClick} title="Open history in a separate window">
                    📄 View history
                  </button>
                </div>
                {historyOpen && (
                  <div className="history-panel">
                    <TicketHistoryTable entries={historyEntries} loading={historyLoading} error={historyError} />
                  </div>
                )}
              </div>
            </div>
          </td>
        </tr>
      )}
    </>
  )
}

// The full interactive ticket table shared by the Tickets page (App.jsx) and the Sales Order
// page's Linked Tickets section (SalesOrdersPage.jsx): the same resizable/reorderable columns
// (ticketColumns.jsx, sharing their own localStorage keys, so dragging a column on one page
// carries over to the other), the same row actions (edit/star/out-of-warranty/close/cancel/
// comment/history) and expandable detail row. `columnLayout` (useTicketColumnLayout) and
// `sort`/`onSort` are owned by the caller rather than this component, since the caller decides
// whether sort drives a server refetch (Tickets page) or a client-side sort of an
// already-fully-loaded list (Linked Tickets — see sortTicketsLocally above).
export default function TicketTable({
  tickets,
  loading,
  error,
  loadingMessage = 'Loading tickets…',
  emptyMessage = 'No tickets found.',
  columnLayout,
  sort,
  onSort,
  onEdit,
  onCloseTicket,
  onAddComment,
  onHistory,
  onToggled,
  // Optional: only the Tickets page (App.jsx) wires this up today. Without it the Order #
  // column/detail field renders as plain text, same as before this existed.
  onOpenSalesOrder,
  // Controlled rather than internal state, unlike the other toggles below: a caller with its
  // own pagination (the Tickets page) hides it entirely while scroll-locked, so it needs to
  // read this value back out.
  scrollLocked,
  onScrollLockedChange,
}) {
  // expandedId stays plain useState — which row happens to be open is a transient bit of
  // interaction state, not a preference worth restoring on the next visit. The three display
  // toggles below are the opposite: they're persisted so navigating away and back (which
  // unmounts this component, see viewPreferences.js) doesn't flip them back to their defaults.
  const [expandedId, setExpandedId] = useState(null)
  const [stripedRows, setStripedRows] = usePersistedToggle(PREF_KEYS.stripedRows, true)
  const [lineItemsCollapsed, setLineItemsCollapsed] = usePersistedToggle(PREF_KEYS.lineItemsCollapsed, false)
  const [descriptionCollapsed, setDescriptionCollapsed] = usePersistedToggle(PREF_KEYS.descriptionCollapsed, false)
  const [columnMenu, setColumnMenu] = useState(null)
  const totalColumnCount = LEADING_COLUMN_WIDTHS.length + columnLayout.columns.length + 1

  // Rendered inside whichever header cell the Line Items column currently sits in — the
  // column is reorderable, so the toggle travels with it rather than living at a fixed
  // position in the header row.
  const lineItemsToggle = (
    <label
      className="table-toggle"
      title="Toggle collapsed (truncated) vs. wrapped line item text"
      onClick={(e) => e.stopPropagation()}
    >
      <input
        type="checkbox"
        checked={lineItemsCollapsed}
        onChange={(e) => setLineItemsCollapsed(e.target.checked)}
      />
      <span>collapse</span>
    </label>
  )

  // Same idea, rendered inside whichever header cell the Description column currently sits in.
  const descriptionToggle = (
    <label
      className="table-toggle"
      title="Toggle wrapped (full) vs. collapsed (2-line) description text"
      onClick={(e) => e.stopPropagation()}
    >
      <input
        type="checkbox"
        checked={descriptionCollapsed}
        onChange={(e) => setDescriptionCollapsed(e.target.checked)}
      />
      <span>collapse</span>
    </label>
  )

  return (
    <>
      {columnMenu && (
        <ColumnContextMenu
          menu={columnMenu}
          columns={columnLayout.columns}
          layout={columnLayout}
          onClose={() => setColumnMenu(null)}
        />
      )}

      <div
        ref={columnLayout.containerRef}
        className={`table-container${scrollLocked ? ' table-container-scroll-locked' : ''}`}
      >
        <table
          className={`ticket-table${stripedRows ? ' ticket-table-striped' : ''}${lineItemsCollapsed ? ' ticket-table-line-items-collapsed' : ''}${descriptionCollapsed ? ' ticket-table-description-collapsed' : ''}`}
          style={{ minWidth: columnLayout.totalWidth }}
        >
          <TicketColumnGroup columns={columnLayout.columns} />
          <thead>
            <tr>
              <th className="col-quick-actions">
                <label className="table-toggle" title="Toggle alternating row stripes">
                  <input
                    type="checkbox"
                    checked={stripedRows}
                    onChange={(e) => setStripedRows(e.target.checked)}
                  />
                  <span>🦓</span>
                </label>
              </th>
              <th className="col-lock-actions">
                <label className="table-toggle" title="Toggle table-only scrolling with a frozen header">
                  <input
                    type="checkbox"
                    checked={scrollLocked}
                    onChange={(e) => onScrollLockedChange(e.target.checked)}
                  />
                  <span>↕️🔒</span>
                </label>
              </th>
              <th className="col-comments"></th>
              {columnLayout.columns.map((column) => (
                <TicketColumnHeader
                  key={column.key}
                  column={column}
                  sort={sort}
                  onSort={onSort}
                  layout={columnLayout}
                  onOpenMenu={setColumnMenu}
                  extra={
                    column.key === 'lineItems'
                      ? lineItemsToggle
                      : column.key === 'description'
                        ? descriptionToggle
                        : null
                  }
                />
              ))}
              <th className="col-spacer"></th>
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr><td colSpan={totalColumnCount} className="empty-state">{loadingMessage}</td></tr>
            )}
            {!loading && !error && tickets.length === 0 && (
              <tr><td colSpan={totalColumnCount} className="empty-state">{emptyMessage}</td></tr>
            )}
            {!loading && tickets.map((ticket, rowIndex) => (
              <TicketRow
                key={ticket.id}
                ticket={ticket}
                columns={columnLayout.columns}
                rowIndex={rowIndex}
                expanded={expandedId === ticket.id}
                onToggle={(id) => setExpandedId((current) => (current === id ? null : id))}
                onEdit={onEdit}
                onCloseTicket={onCloseTicket}
                onAddComment={onAddComment}
                onHistory={onHistory}
                onToggled={onToggled}
                onOpenSalesOrder={onOpenSalesOrder}
              />
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
