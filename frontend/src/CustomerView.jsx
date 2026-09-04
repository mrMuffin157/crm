import { useEffect, useState } from 'react'
import { lookupSalesOrdersByEmail, markCustomerReviewDisabled } from './salesOrderApi.js'
import { sourceSystemForOrder } from './salesOrderEnums.js'
import { useToast } from './Toast.jsx'
import CopyButton from './CopyButton.jsx'

// Customer View, reached from the Sales Order page's buyer order count. Renders the
// same thing legacy-app's own customer page does: who the buyer is, whether their reviews are
// blocked, and every order they have ever placed.
//
// Deliberately a sub-view of the Sales Order page rather than its own sidebar entry: it is only
// ever meaningful for a specific buyer, and this app has no router to carry an email through a
// top-level nav item (App.jsx switches on a bare view-id string). Every order number listed
// here is a link back into the lookup that opened it, so the two views form one loop.
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

// legacy-app's own "Purchased SKU" column: every line on the order as "SKU x qty", comma-separated.
function purchasedSkus(order) {
  const lines = (order.lineItems ?? [])
    .map((item) => {
      const sku = item.productsku || item.amazonsku
      return sku ? `${sku} x ${item.quantity ?? 0}` : null
    })
    .filter(Boolean)
  return lines.length > 0 ? lines.join(', ') : '—'
}

// The buyer's identity comes off the orders themselves rather than a separate customer lookup:
// legacy-app returns the same CustomerTO embedded in every one of their orders, so a second round
// trip would only re-fetch what is already here. The newest order wins for the review flag,
// which is a live per-customer setting rather than per-order state.
function customerOf(orders) {
  return orders.find((order) => order.customer)?.customer ?? null
}

export default function CustomerView({ email, sourceSystem, onBack, onOpenOrder }) {
  const showToast = useToast()
  const [orders, setOrders] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [disabling, setDisabling] = useState(false)
  // Reflects the flag locally after a successful disable, so the button doesn't sit there still
  // offering an action that has already been taken. The authoritative value still comes from
  // legacy-app on the next load — this never fabricates a state the server didn't confirm.
  const [reviewDisabled, setReviewDisabled] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    lookupSalesOrdersByEmail(email)
      .then((data) => {
        if (cancelled) return
        setOrders(data)
        setReviewDisabled(Boolean(customerOf(data ?? [])?.reviewDisabled))
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
  }, [email])

  const customer = customerOf(orders ?? [])
  // The channel to route the disable call to (see LegacyApiClient#markCustomerReviewDisabled on
  // why it is routed rather than fanned out). Normally the channel of the order this view was
  // opened from; falls back to the buyer's own newest order for the case where that order's
  // channel has no "az-*" code of its own.
  const channel = sourceSystem || sourceSystemForOrder(orders?.[0])

  function handleDisableReview() {
    setDisabling(true)
    markCustomerReviewDisabled({ sourceSystem: channel, email })
      .then(() => {
        setReviewDisabled(true)
        showToast('Review disabled for this buyer.')
      })
      .catch((err) => showToast(err.message))
      .finally(() => setDisabling(false))
  }

  return (
    <>
      <header className="app-header">
        <h1>Customer</h1>
      </header>

      <div className="filter-bar">
        <button type="button" className="member-action-button" onClick={onBack}>
          ← Back to order
        </button>
      </div>

      {loading && <div className="empty-state-banner">Loading this buyer’s orders…</div>}
      {error && <div className="error-banner">Couldn’t load this buyer: {error}</div>}
      {!loading && !error && orders === null && (
        <div className="empty-state-banner">No orders found for “{email}”.</div>
      )}

      {!loading && !error && orders !== null && (
        <div className="ticket-detail">
          <div className="detail-grid">
            <div><span className="detail-label">Name</span>{customer?.name || '—'}</div>
            <div>
              <span className="detail-label">Email</span>
              {email}
              <CopyButton value={email} title="Copy buyer email" />
            </div>
            <div><span className="detail-label">Disable Review</span>{reviewDisabled ? 'true' : 'false'}</div>
          </div>

          {/* Only offered while the buyer's reviews are still solicited — once blocked there is
              nothing left to do, and legacy-app would reject a second call anyway. Blocking every
              future order's review is not reversible from CRM, so it asks first. */}
          {!reviewDisabled && (
            <div className="detail-section">
              <button
                type="button"
                className="create-ticket-toggle"
                disabled={disabling || !channel}
                title={channel
                  ? 'Block Amazon review solicitation for every order this buyer places'
                  : 'This order’s channel has no legacy-app host configured'}
                onClick={() => {
                  if (window.confirm(
                    `Disable customer review for ${email}?\n\n`
                    + 'This blocks review solicitation for every order this buyer places, '
                    + 'not just the one you came from. It cannot be undone from CRM.')) {
                    handleDisableReview()
                  }
                }}
              >
                {disabling ? 'Disabling…' : 'Disable Customer Review'}
              </button>
            </div>
          )}

          <div className="detail-section">
            <span className="detail-label">Orders ({orders.length})</span>
            {orders.length > 0 ? (
              <div className="table-scroll">
                <table className="detail-subtable">
                  <thead>
                    <tr>
                      <th>Order Number</th>
                      <th>Purchase Date</th>
                      <th>Grand Total</th>
                      <th>Purchased SKU</th>
                    </tr>
                  </thead>
                  <tbody>
                    {orders.map((order, i) => {
                      const orderNumber = order.amazonOrderId || order.merchantOrderId
                      return (
                        <tr key={orderNumber || i}>
                          <td>
                            {orderNumber ? (
                              <button
                                type="button"
                                className="link-button"
                                onClick={() => onOpenOrder(orderNumber)}
                              >
                                {orderNumber}
                              </button>
                            ) : '—'}
                          </td>
                          <td>{formatDate(order.datePurchased)}</td>
                          <td>{formatMoney(order.grandTotalPrice)}</td>
                          <td>{purchasedSkus(order)}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="detail-empty">No orders recorded for this buyer.</p>
            )}
          </div>
        </div>
      )}
    </>
  )
}
