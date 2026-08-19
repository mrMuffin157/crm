import { useEffect, useRef, useState } from 'react'
import { fetchTickets } from './api.js'
import { lookupSalesOrder, markOrderReviewDisabled } from './salesOrderApi.js'
import {
  asinUrl,
  sellerCentralUrl,
  sourceSystemForOrder,
} from './salesOrderEnums.js'
import { useToast } from './Toast.jsx'
import CopyButton from './CopyButton.jsx'
import CustomerView from './CustomerView.jsx'
import CreateTicketForm from './CreateTicketForm.jsx'
import { EMPTY_TICKET_FORM } from './TicketFormFields.jsx'
import EditTicketModal from './EditTicketModal.jsx'
import CloseTicketModal from './CloseTicketModal.jsx'
import AddCommentModal from './AddCommentModal.jsx'
import TicketHistoryModal from './TicketHistoryModal.jsx'
import TicketTable, { nextSortState, sortTicketsLocally } from './TicketTable.jsx'
import { ResetColumnsButton, useTicketColumnLayout } from './ticketColumns.jsx'
import { PREF_KEYS, usePersistedToggle } from './viewPreferences.js'

const DEFAULT_SORT = { field: 'id', dir: 'desc' }

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

function formatMoney(value) {
  if (value == null) return '—'
  return `$${Number(value).toFixed(2)}`
}

// One field of the Order / Buyer blocks below: its label, its value, and whatever trails the
// value (a copy button, the ↗ Seller Central link, the buyer's order-count link).
//
// `area` is the field's own grid-area name — the two blocks lay their fields out by name
// (grid-template-areas in index.css) rather than by source order, so which cell a field sits in
// and how wide it is are both stated in one place and can't drift from the markup here.
//
// The value and its trailing controls are a flex row so the two never render glued together and
// the buttons stay put when the value is long: as plain inline content the icon sits hard
// against the value's last character, and a value that wraps drops the icon onto a line of its
// own.
function DetailField({ area, label, value, children }) {
  return (
    <div className={`so-field so-field-${area}`}>
      <span className="detail-label">{label}</span>
      <div className="so-field-value">
        {/* The full value on the tooltip, so a column too narrow for some other order's data
            still gives it up on hover instead of just showing an ellipsis. */}
        <span className="so-field-text" title={typeof value === 'string' ? value : undefined}>{value}</span>
        {children}
      </div>
    </div>
  )
}

// crmLink is populated only when a ticket already exists in crm_dev for this order/tracking
// number (SalesOrderCrmLinkService) — additive to the order's own live buyer/ship-to fields,
// not a replacement, since crm_dev has no data at all for an order with no ticket yet.
function CrmLinkSection({ crmLink }) {
  if (!crmLink || (!crmLink.customerName && !crmLink.customerEmail && !crmLink.address)) {
    return null
  }
  const address = crmLink.address
  return (
    <div className="detail-section so-section">
      <span className="detail-label so-section-title">Linked CRM Customer</span>
      <div className="detail-grid">
        <div><span className="detail-label">Customer Name</span>{crmLink.customerName || '—'}</div>
        <div><span className="detail-label">Customer Email</span>{crmLink.customerEmail || '—'}</div>
        {address && (
          <div>
            <span className="detail-label">Address</span>
            {[address.name, address.addressLine1, address.addressLine2, address.city,
              address.state, address.zipCode, address.countryCode].filter(Boolean).join(', ') || '—'}
          </div>
        )}
      </div>
    </div>
  )
}

function SalesOrderResult({ order, onOpenCustomer }) {
  const showToast = useToast()
  const externalUrl = sellerCentralUrl(order)
  const sourceSystem = sourceSystemForOrder(order)
  const buyerEmail = order.customer?.email
  // Mirrors the flag locally after a successful disable so the button stops offering an action
  // already taken; the authoritative value still comes from legacy-app on the next lookup.
  const [reviewDisabled, setReviewDisabled] = useState(Boolean(order.reviewDisabled))
  const [disablingReview, setDisablingReview] = useState(false)

  function handleDisableReview() {
    if (!window.confirm(
      `Disable review for order ${order.amazonOrderId || order.merchantOrderId}?\n\n`
      + 'This blocks Amazon review solicitation for this order. It cannot be undone from CRM.')) {
      return
    }
    setDisablingReview(true)
    markOrderReviewDisabled({ sourceSystem, orderNumber: order.amazonOrderId || order.merchantOrderId })
      .then(() => {
        setReviewDisabled(true)
        showToast('Review disabled for this order.')
      })
      .catch((err) => showToast(err.message))
      .finally(() => setDisablingReview(false))
  }

  return (
    <div className="ticket-detail">
      {/* Both blocks are fixed-pixel grids, not the auto-fit .detail-grid the rest of the app
          uses: the fields are known, their values have known lengths, and the widths below were
          chosen against real orders so an email and a full ship-to address each fit on one line.
          Their own scroll container, because the columns sum to more than the page has at
          1750px with the sidebar open — the block scrolls sideways rather than the whole page. */}
      <div className="detail-section">
        <span className="detail-label so-section-title">Order</span>
        <div className="so-fields-scroll">
          <div className="so-fields so-fields-order">
            <DetailField area="amazonOrderId" label="Amazon Order ID" value={order.amazonOrderId || '—'}>
              <CopyButton value={order.amazonOrderId} title="Copy Amazon order ID" />
              {externalUrl && (
                <a
                  href={externalUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="external-link-icon"
                  title="Open in Amazon Seller Central"
                >
                  ↗
                </a>
              )}
            </DetailField>
            <DetailField area="merchantOrderId" label="Merchant Order ID" value={order.merchantOrderId || '—'} />
            <DetailField area="status" label="Status" value={order.status || '—'} />
            <DetailField area="salesChannel" label="Sales Channel" value={order.salesChannel || '—'} />
            <DetailField area="fulfillmentType" label="Fulfillment Type" value={order.fulfillmentType || '—'} />
            <DetailField area="datePurchased" label="Date Purchased" value={formatDate(order.datePurchased)} />
            <DetailField area="dateShipped" label="Date Shipped" value={formatDate(order.dateShipped)} />
            <DetailField area="grandTotal" label="Grand Total" value={formatMoney(order.grandTotalPrice)} />
            <DetailField area="shippingFee" label="Shipping Fee" value={formatMoney(order.shippingFee)} />
            <DetailField area="taxFee" label="Tax Fee" value={formatMoney(order.taxFee)} />
          </div>
        </div>
      </div>

      <div className="detail-section so-section">
        <span className="detail-label so-section-title">Buyer / Recipient</span>
        <div className="so-fields-scroll">
          <div className="so-fields so-fields-buyer">
            <DetailField area="buyerName" label="Buyer Name" value={order.customer?.name || '—'}>
              {/* The buyer's own order history, the count included, lives on legacy-app — this is
                  the one link into it (Customer View). Shown even at 1, since "1
                  order" is itself the useful signal that this is a first-time buyer. Needs an
                  email to look the buyer up by, so an order with no linked customer renders the
                  count as plain text rather than a link that could not resolve. */}
              {buyerEmail ? (
                <button
                  type="button"
                  className="link-button buyer-order-count"
                  onClick={() => onOpenCustomer(buyerEmail, sourceSystem)}
                  title="View this buyer’s orders"
                >
                  {order.customerOrderCount} order{order.customerOrderCount === 1 ? '' : 's'}
                </button>
              ) : (
                <span className="buyer-order-count">{order.customerOrderCount} orders</span>
              )}
            </DetailField>
            <DetailField area="buyerEmail" label="Buyer Email" value={buyerEmail || '—'}>
              <CopyButton value={buyerEmail} title="Copy buyer email" />
            </DetailField>
            <DetailField area="reviewDisabled" label="Review Disabled" value={reviewDisabled ? 'true' : 'false'} />
            <DetailField area="recipientName" label="Recipient Name" value={order.recipientName || '—'} />
            <DetailField area="shipToAddress" label="Ship-To Address" value={order.shipToAddress || '—'}>
              <CopyButton value={order.shipToAddress} title="Copy ship-to address" />
            </DetailField>
            {/* Sits in the grid directly under Review Disabled — the field it acts on — rather
                than in the section heading row. Only offered while this order's reviews are
                still solicited: once blocked there is nothing left to do, and legacy-app would
                reject a second call anyway. Disabled outright for a channel with no legacy-app host
                configured (4 of the 8 az-* channels — see LegacyApiProperties.Hosts), where the
                call could never be routed anywhere. */}
            {!reviewDisabled && (
              <div className="so-field so-field-disableReview">
                <button
                  type="button"
                  className="create-ticket-toggle"
                  disabled={disablingReview || !sourceSystem}
                  title={sourceSystem
                    ? 'Block Amazon review solicitation for this order'
                    : 'This order’s channel has no legacy-app host configured'}
                  onClick={handleDisableReview}
                >
                  {disablingReview ? 'Disabling…' : 'Disable Review'}
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="detail-section so-section">
        <span className="detail-label so-section-title">Fulfillment Packages</span>
        {order.fulfillmentPackages?.length ? (
          <div className="table-scroll">
            <table className="detail-subtable">
              <thead>
                <tr>
                  <th>Carrier</th>
                  <th>Tracking Number</th>
                  <th>Est. Arrival</th>
                </tr>
              </thead>
              <tbody>
                {order.fulfillmentPackages.map((pkg, i) => (
                  <tr key={i}>
                    <td>{pkg.carrier || '—'}</td>
                    <td>{pkg.trackingNumber || '—'}</td>
                    <td>{formatDate(pkg.estimateArrivalDate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : order.trackingNumbers?.length ? (
          <ul className="line-item-list">
            {order.trackingNumbers.map((tracking, i) => <li key={i}>{tracking}</li>)}
          </ul>
        ) : (
          <p className="detail-empty">No tracking numbers recorded.</p>
        )}
      </div>

      <div className="detail-section so-section">
        <span className="detail-label so-section-title">Line Items</span>
        {order.lineItems?.length ? (
          <div className="table-scroll">
            <table className="detail-subtable">
              <thead>
                <tr>
                  <th>SKU</th>
                  <th>Amazon SKU</th>
                  <th>ASIN</th>
                  <th>Name</th>
                  <th>Amazon Order Item Code</th>
                  <th>Qty</th>
                  <th>Unit Price</th>
                  <th>Unit Tax</th>
                  <th>Shipment Date</th>
                </tr>
              </thead>
              <tbody>
                {order.lineItems.map((item, i) => (
                  <tr key={i}>
                    <td>{item.productsku || '—'}</td>
                    <td>{item.amazonsku || '—'}</td>
                    <td>{asinLink(order, item.asin)}</td>
                    <td title={item.name}>{item.name || '—'}</td>
                    <td>{item.amazonOrderItemCode || '—'}</td>
                    <td>{item.quantity}</td>
                    <td>{formatMoney(item.unitPrice)}</td>
                    <td>{formatMoney(item.unitTax)}</td>
                    <td>{formatDate(item.shipmentDate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="detail-empty">No line items recorded.</p>
        )}
      </div>

      {/* legacy-app's own Order Events tab (SalesOrderInfoTO.orderEvents): what has already
          been done for this order — review blocked, refunded, replaced — so an agent doesn't
          have to open legacy-app alongside the ticket to find out. Already newest-first from legacy-app;
          not re-sorted here, so the order shown is the order legacy-app considers authoritative.
          `username` is null for events written by a job rather than a person. */}
      <div className="detail-section so-section">
        <span className="detail-label so-section-title">Order Events</span>
        {order.orderEvents?.length ? (
          <div className="table-scroll">
            <table className="detail-subtable">
              <thead>
                <tr>
                  <th>Type</th>
                  <th>Comment</th>
                  <th>By</th>
                  <th>Date</th>
                </tr>
              </thead>
              <tbody>
                {order.orderEvents.map((event, i) => (
                  <tr key={i}>
                    <td>{event.eventType || '—'}</td>
                    <td>{event.comment || '—'}</td>
                    <td>{event.username || 'system'}</td>
                    <td>{formatDate(event.dateCreated)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="detail-empty">No events recorded for this order.</p>
        )}
      </div>
    </div>
  )
}

// An ASIN links out to that item's own public Amazon listing on the storefront the order was
// placed on — opened in a new tab, never navigating this page away from an order the
// agent is mid-way through handling. Falls back to plain text for a channel with no storefront
// domain mapped, rather than guessing one that might not carry the ASIN at all.
function asinLink(order, asin) {
  const url = asinUrl(order, asin)
  if (!url) return asin || '—'
  return (
    <a href={url} target="_blank" rel="noopener noreferrer" className="asin-link">
      {asin}
    </a>
  )
}

// The order's own line items, in the shape the create form's item rows use — handed to
// CreateTicketForm as `orderLineItems` so picking Replacement/Manual Handling/Refund fills the
// table with what was actually ordered instead of leaving an empty one the user has to click
// "Load Order Lines" to fill. Mirrors the legacy form, which seeded the Replacement/
// Manual-Handling tables straight from the order whenever the ticket was created from within
// that order's page. The rows stay fully editable and removable, and the picker's own "Load
// Order Lines" button is still there to re-add one that was removed.
//
// One shape covers all three blocks because they read the same `form.items`: `name` is what
// Manual Handling's Part Name column needs, `channelSku` comes from the line's own amazonsku
// (never hand-typed — see LineItemInput), and `refundAmount` is Refund's usual
// unitPrice * quantity default, each of which the other blocks simply ignore
// (buildTicketPayload drops them per action — including `quantity`, which it pins to 0 on a
// Refund row, since a refund is an amount rather than a count of items). Lines with no SKU are skipped — a SKU-less row
// can't pass validateTicketForm, so seeding one would only produce an error to clear by hand.
function orderLineItemsToFormItems(order) {
  return (order?.lineItems ?? [])
    .filter((line) => line.productsku)
    .map((line) => ({
      sku: line.productsku,
      name: line.name ?? '',
      quantity: line.quantity ?? 1,
      channelSku: line.amazonsku || null,
      refundAmount: line.unitPrice != null && line.quantity != null
        ? Math.round(line.unitPrice * line.quantity * 100) / 100
        : 0,
    }))
}

// A ticket's Order # can be stamped with either identifier legacy-app hands back for an order
// (amazonOrderId for Amazon-channel orders, merchantOrderId as the channel-agnostic id) — the
// two are deduped since Amazon orders sometimes carry the same value in both, and searched
// together so a ticket linked under either isn't missed.
function orderNumberCandidates(order) {
  return [...new Set([order?.amazonOrderId, order?.merchantOrderId].filter(Boolean))]
}

// The "+ New Ticket" button is the exact same CreateTicketForm used on the Ticket page — just
// prefilled with this order's number and channel, handed the order's own line items to seed the
// item table with (see orderLineItemsToFormItems), and with withinSalesOrder=true so Order # is
// omitted from the form (it's implied by this order) rather than shown redundantly empty, and
// the Channel picker is locked to the prefilled channel rather than left free to contradict the
// order it's being raised from — see TicketFormFields' own withinSalesOrder comment. The Action
// dropdown itself (including Refund) is the same regardless of withinSalesOrder.
//
// sourceSystem is '' for an order on a channel with no "az-*" code (see sourceSystemForOrder),
// which leaves the picker empty and editable exactly as it is on the Ticket page — the create
// still can't go through without a channel either way.
//
// The table itself is the exact same TicketTable the Tickets page uses (same columns, same
// resize/reorder/collapse toggles — sharing its localStorage keys via its own
// useTicketColumnLayout() instance below — and the same row actions/modals), just sorted
// client-side instead of server-side: this list is already fully loaded (fetchTickets is
// called with listAll: true), not paginated, so there's no server query for sort to drive.
function LinkedTicketsSection({ order, tickets, loading, error, onCreated }) {
  const showToast = useToast()
  // Line items are seeded from the order itself rather than left for the agent to
  // re-key or fetch through the form's own "Load Order Lines" picker: a ticket raised from
  // within an order is about what that order contained, and the lookup already has it in hand.
  // The picker stays — it is still the only way to add a line to a ticket raised from the
  // Tickets page, and the only way to recover a row removed from this seed.
  //
  // That seed is handed over as the `orderLineItems` prop below, deliberately NOT as `items`
  // here: it has to arrive through TicketFormFields' own action-driven seeding (seedsItemsFor),
  // which only fills a table that is still empty. Pre-filling `items` disabled that seeding
  // outright — the table was never empty, so the guard never fired — and with it the one action
  // it deliberately excludes, NoAction, whose form then showed the order's rows and submitted
  // them, putting a ticket that has nothing to do with those lines through legacy-app's channel-SKU
  // resolution on save.
  const initialForm = {
    ...EMPTY_TICKET_FORM,
    orderNumber: order.amazonOrderId || order.merchantOrderId || '',
    sourceSystem: sourceSystemForOrder(order),
    // Seed the buyer of record straight from the order's own CustomerTO so a ticket raised from
    // within an order doesn't start out unlinked from crm_dev.customer — the backend
    // (TicketController#resolveCustomerId) inserts the row if it isn't there yet. Works for both
    // a live legacy-app lookup and the sales_order_archive fallback, since both return the identical
    // SalesOrderInfoTO shape (see CLAUDE.md's Sales Order lookup section).
    customerId: order.customer?.id || '',
    customerName: order.customer?.name || '',
    email: order.customer?.email || '',
  }

  const columnLayout = useTicketColumnLayout()
  const [sort, setSort] = useState(DEFAULT_SORT)
  const [scrollLocked, setScrollLocked] = usePersistedToggle(PREF_KEYS.scrollLocked, true)
  const [editingTicket, setEditingTicket] = useState(null)
  const [closingTicket, setClosingTicket] = useState(null)
  const [commentingTicket, setCommentingTicket] = useState(null)
  const [historyTicket, setHistoryTicket] = useState(null)

  function toggleSort(field) {
    setSort((prev) => nextSortState(prev, field))
  }

  return (
    <div className="detail-section so-section">
      <div className="detail-section-header">
        <div className="detail-section-header-left">
          <span className="detail-label so-section-title">Linked Tickets</span>
          <ResetColumnsButton columnLayout={columnLayout} />
        </div>
        <CreateTicketForm
          onCreated={onCreated}
          initialForm={initialForm}
          orderLineItems={orderLineItemsToFormItems(order)}
          withinSalesOrder
        />
      </div>
      {error && <div className="error-banner">Couldn't load linked tickets: {error}</div>}
      <TicketTable
        tickets={sortTicketsLocally(tickets, sort)}
        loading={loading}
        error={error}
        loadingMessage="Loading linked tickets…"
        emptyMessage="No tickets linked to this order."
        columnLayout={columnLayout}
        sort={sort}
        onSort={toggleSort}
        scrollLocked={scrollLocked}
        onScrollLockedChange={setScrollLocked}
        onEdit={setEditingTicket}
        onCloseTicket={setClosingTicket}
        onAddComment={setCommentingTicket}
        onHistory={setHistoryTicket}
        onToggled={onCreated}
      />

      {editingTicket && (
        <EditTicketModal
          ticket={editingTicket}
          onClose={(saved) => {
            setEditingTicket(null)
            if (saved) onCreated()
          }}
        />
      )}

      {closingTicket && (
        <CloseTicketModal
          ticket={closingTicket}
          onClose={(saved) => {
            setClosingTicket(null)
            if (saved) onCreated()
          }}
        />
      )}

      {commentingTicket && (
        <AddCommentModal
          ticket={commentingTicket}
          onClose={(saved) => {
            setCommentingTicket(null)
            if (saved) {
              onCreated()
              showToast('Comment added.')
            }
          }}
        />
      )}

      {historyTicket && (
        <TicketHistoryModal
          ticket={historyTicket}
          onClose={() => setHistoryTicket(null)}
        />
      )}
    </div>
  )
}

export default function SalesOrdersPage({ initialOrderNumber, onInitialOrderConsumed }) {
  const [mode, setMode] = useState('amazonOrderId')
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  // undefined = no lookup performed yet, null = looked up but not found, object = found
  const [result, setResult] = useState(undefined)
  const [searchedFor, setSearchedFor] = useState(null)

  // Non-null while the Customer View is open over this page: the buyer's email plus
  // the channel of the order it was opened from, which is what routes its own disable-review
  // call. A sub-view rather than a sidebar page — see CustomerView's own comment.
  const [customer, setCustomer] = useState(null)

  const [linkedTickets, setLinkedTickets] = useState([])
  const [linkedTicketsLoading, setLinkedTicketsLoading] = useState(false)
  const [linkedTicketsError, setLinkedTicketsError] = useState(null)
  const [ticketReloadToken, setTicketReloadToken] = useState(0)

  useEffect(() => {
    const candidates = orderNumberCandidates(result?.order)
    if (candidates.length === 0) {
      setLinkedTickets([])
      setLinkedTicketsError(null)
      return
    }

    let cancelled = false
    setLinkedTicketsLoading(true)
    setLinkedTicketsError(null)

    Promise.all(candidates.map((orderNumber) =>
      fetchTickets({ orderNumber, listAll: true, sortField: 'id', sortDir: 'desc' })
    ))
      .then((pages) => {
        if (cancelled) return
        const byId = new Map()
        for (const page of pages) {
          for (const ticket of page.content) byId.set(ticket.id, ticket)
        }
        setLinkedTickets([...byId.values()].sort((a, b) => b.id - a.id))
      })
      .catch((err) => {
        if (!cancelled) setLinkedTicketsError(err.message)
      })
      .finally(() => {
        if (!cancelled) setLinkedTicketsLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [result, ticketReloadToken])

  function runLookup(term, lookupMode) {
    setLoading(true)
    setError(null)
    setSearchedFor(term)

    lookupSalesOrder(lookupMode === 'amazonOrderId' ? { amazonOrderId: term } : { trackingNumber: term })
      .then((data) => setResult(data))
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false))
  }

  function handleSubmit(e) {
    e.preventDefault()
    const trimmed = query.trim()
    if (!trimmed) return
    runLookup(trimmed, mode)
  }

  // Clicking an order number in the Customer View drops back onto this page showing that order,
  // with the search box updated to match — so the lookup form never disagrees with what is
  // rendered under it, and a refresh/re-submit finds the same order.
  function handleOpenOrder(orderNumber) {
    setCustomer(null)
    setMode('amazonOrderId')
    setQuery(orderNumber)
    runLookup(orderNumber, 'amazonOrderId')
  }

  // Same loop from the other direction: a ticket's Order # cell (TicketTable, via App.jsx)
  // lands here with initialOrderNumber set, so this page opens straight onto that order instead
  // of an empty search box. Ref-guarded so React StrictMode's double-invoked mount effect (and
  // any re-render before onInitialOrderConsumed's setState above takes effect) can't fire the
  // lookup twice — this page unmounts on every navigation away (App.jsx's currentView ternary),
  // so a fresh initialOrderNumber always arrives on a fresh mount/ref.
  const consumedInitialOrderRef = useRef(false)
  useEffect(() => {
    if (!initialOrderNumber || consumedInitialOrderRef.current) return
    consumedInitialOrderRef.current = true
    handleOpenOrder(initialOrderNumber)
    onInitialOrderConsumed?.()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialOrderNumber])

  if (customer) {
    return (
      <CustomerView
        email={customer.email}
        sourceSystem={customer.sourceSystem}
        onBack={() => setCustomer(null)}
        onOpenOrder={handleOpenOrder}
      />
    )
  }

  return (
    <>
      <header className="app-header">
        <h1>Sales Order Lookup</h1>
      </header>

      <form className="filter-bar" onSubmit={handleSubmit}>
        <label className="filter-field">
          <span>Look up by</span>
          <select value={mode} onChange={(e) => setMode(e.target.value)}>
            <option value="amazonOrderId">Amazon Order ID</option>
            <option value="trackingNumber">Tracking Number</option>
          </select>
        </label>

        <label className="filter-field">
          <span>{mode === 'amazonOrderId' ? 'Amazon Order ID' : 'Tracking Number'}</span>
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={mode === 'amazonOrderId' ? '123-4567890-1234567' : '1Z999AA10123456784'}
          />
        </label>

        <button type="submit" className="search-button" disabled={loading || !query.trim()}>
          {loading ? 'Looking up…' : 'Look Up'}
        </button>
      </form>

      {result === undefined && !error && (
        <div className="empty-state-banner">
          Enter an Amazon order ID or a tracking number above — this is a live, single-order
          lookup against legacy-app, fanned out across all 4 storefronts, not a browsable list.
        </div>
      )}

      {error && <div className="error-banner">Couldn't look up that order: {error}</div>}

      {!error && result === null && (
        <div className="empty-state-banner">No order found for "{searchedFor}".</div>
      )}

      {result && (
        <>
          {/* Keyed by the order itself: SalesOrderResult holds the review-disabled flag in
              local state (so the button can stop offering an action already taken), and without
              a key React would reuse the same instance across a second lookup and carry the
              previous order's flag onto the new one. */}
          <SalesOrderResult
            key={result.order.amazonOrderId || result.order.merchantOrderId || searchedFor}
            order={result.order}
            onOpenCustomer={(email, sourceSystem) => setCustomer({ email, sourceSystem })}
          />
          <CrmLinkSection crmLink={result.crmLink} />
          <LinkedTicketsSection
            order={result.order}
            tickets={linkedTickets}
            loading={linkedTicketsLoading}
            error={linkedTicketsError}
            onCreated={() => setTicketReloadToken((t) => t + 1)}
          />
        </>
      )}
    </>
  )
}
