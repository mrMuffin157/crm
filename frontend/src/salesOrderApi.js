import { apiFetch } from './httpClient.js'

async function readError(response, fallback) {
  try {
    const body = await response.json()
    if (body?.error) return body.error
  } catch {
    // Not a JSON error body (a proxy's HTML error page, an empty 502) — fall through.
  }
  return `${fallback} (${response.status})`
}

// legacy-app's own endpoint is a single-record lookup by exact Amazon order id or tracking number,
// not a query API — this app just proxies that lookup, fanned out across legacy-app's 4 storefront
// systems server-side (see LegacyApiClient). Returns null on a 404 (no such order), and throws
// on any other non-OK status.
export async function lookupSalesOrder({ amazonOrderId, trackingNumber } = {}) {
  const params = new URLSearchParams()
  if (amazonOrderId) params.set('amazonOrderId', amazonOrderId)
  if (trackingNumber) params.set('trackingNumber', trackingNumber)

  const response = await apiFetch(`/api/salesApi/getSalesOrderInfo?${params.toString()}`)

  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(`Failed to look up sales order (${response.status})`)
  }

  return response.json()
}

// Every sales order one buyer has ever placed (Customer View), keyed by their email.
// Same null-on-404 contract as lookupSalesOrder above: a buyer legacy-app has never seen is "not
// found", not an empty list — the backend never answers 200 with an empty array.
export async function lookupSalesOrdersByEmail(email) {
  const params = new URLSearchParams({ email })
  const response = await apiFetch(`/api/salesApi/getSalesOrderInfoListByEmail?${params.toString()}`)

  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(await readError(response, 'Failed to look up this buyer’s orders'))
  }

  return response.json()
}

// Blocks Amazon's review solicitation for one order. `sourceSystem` is the order's own channel
// code — the backend routes the call to that single storefront rather than fanning it out (see
// LegacyApiClient#markOrderReviewDisabled). updateBy is filled in server-side from the session,
// so it is deliberately not sent from here.
export async function markOrderReviewDisabled({ sourceSystem, orderNumber }) {
  const response = await apiFetch('/api/ticketApi/markReviewDisabled', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceSystem, orderNumber }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to disable review for this order'))
}

// The customer-level counterpart: blocks review solicitation for every order this buyer places.
export async function markCustomerReviewDisabled({ sourceSystem, email }) {
  const response = await apiFetch('/api/customerApi/markReviewDisabled', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceSystem, email }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to disable review for this buyer'))
}
