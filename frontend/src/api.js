import { apiFetch } from './httpClient.js'

export async function fetchTickets({
  status,
  action,
  starred,
  handlerUsername,
  search,
  orderNumber,
  sourceSystem,
  createdAfter,
  createdBefore,
  sortField,
  sortDir,
  page = 0,
  size = 100,
  listAll = false,
} = {}) {
  const params = new URLSearchParams()
  if (status) params.set('status', status)
  if (action) params.set('action', action)
  if (starred === 'true' || starred === 'false') params.set('starred', starred)
  if (handlerUsername) params.set('handlerUsername', handlerUsername)
  if (search) params.set('search', search)
  if (orderNumber) params.set('orderNumber', orderNumber)
  if (sourceSystem) params.set('sourceSystem', sourceSystem)
  if (createdAfter) params.set('createdAfter', createdAfter)
  if (createdBefore) params.set('createdBefore', createdBefore)
  if (sortField) params.set('sortField', sortField)
  if (sortDir) params.set('sortDir', sortDir)
  params.set('page', String(page))
  params.set('size', String(size))
  if (listAll) params.set('listAll', 'true')

  const response = await apiFetch(`/api/tickets?${params.toString()}`)

  if (!response.ok) {
    throw new Error(`Failed to load tickets (${response.status})`)
  }

  return response.json()
}

// Excel export (client-side, "Excel export" button next to "Export ticket report" — unlike
// that CSV report, this exports exactly what's currently on screen: the same filters/sort the
// Tickets page has applied, every matching row across every page). Loops the real paginated
// endpoint at its own MAX_PAGE_SIZE (1000, see TicketController) rather than listAll=true,
// which is deliberately capped at 100 rows server-side to keep a broad "Show All Records" pull
// cheap — that cap would silently truncate a large export.
const EXPORT_PAGE_SIZE = 1000

export async function fetchAllTicketsForExport(params = {}) {
  const all = []
  let page = 0
  for (;;) {
    const data = await fetchTickets({ ...params, page, size: EXPORT_PAGE_SIZE })
    all.push(...data.content)
    if (data.content.length < EXPORT_PAGE_SIZE || all.length >= data.totalElements) break
    page += 1
  }
  return all
}

// Backs the deep link in NotifyFailedTicketsService's digest email —
// App.jsx's ?ticketId= handling opens this ticket's edit modal directly on load.
export async function fetchTicketById(id) {
  const response = await apiFetch(`/api/tickets/${id}`)
  if (!response.ok) {
    throw new Error(`Failed to load ticket ${id} (${response.status})`)
  }
  return response.json()
}

// Reads the backend's { "error": "..." } body when available (see TicketController's
// @ExceptionHandler methods) so failures show the real reason (e.g. "Cannot edit a Closed
// or Cancelled ticket") instead of just a status code.
async function readError(response, fallback) {
  try {
    const body = await response.json()
    if (body && body.error) return body.error
  } catch {
    // response wasn't JSON — fall through to the generic message
  }
  return `${fallback} (${response.status})`
}

export async function createTicket(ticket) {
  const response = await apiFetch('/api/tickets', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(ticket),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to create ticket'))
  return response.json()
}

export async function updateTicket(id, ticket) {
  const response = await apiFetch(`/api/tickets/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(ticket),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to update ticket'))
  return response.json()
}

export async function setStarred(id, value) {
  const response = await apiFetch(`/api/tickets/${id}/starred?value=${value}`, { method: 'PATCH' })
  if (!response.ok) throw new Error(await readError(response, 'Failed to update ticket'))
  return response.json()
}

export async function setOutOfWarrant(id, value) {
  const response = await apiFetch(`/api/tickets/${id}/out-of-warrant?value=${value}`, { method: 'PATCH' })
  if (!response.ok) throw new Error(await readError(response, 'Failed to update ticket'))
  return response.json()
}

export async function changeStatus(id, value) {
  const response = await apiFetch(`/api/tickets/${id}/status?value=${value}`, { method: 'PATCH' })
  if (!response.ok) throw new Error(await readError(response, 'Failed to update ticket'))
  return response.json()
}

export async function closeTicket(id, { trackingNumber, comment } = {}) {
  const response = await apiFetch(`/api/tickets/${id}/close`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ trackingNumber: trackingNumber || null, comment: comment || null }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to close ticket'))
  return response.json()
}

// Backs the Refund ticket action's line-item picker (TicketFormFields' RefundItemRows)
// — proxies legacy-app's own GET /api/ticketApi/salesOrderLines, fanned out across its 4 storefront
// hosts server-side (see LegacyApiClient#salesOrderLines), same "don't know which storefront"
// situation as lookupSalesOrder in salesOrderApi.js. Returns [] on a 404 (order not found on any
// host) rather than throwing, since "no lines to pick from yet" is a normal picker state, not
// an error banner.
export async function fetchSalesOrderLines(orderNumber) {
  const params = new URLSearchParams({ orderNumber })
  const response = await apiFetch(`/api/ticketApi/salesOrderLines?${params.toString()}`)
  if (response.status === 404) return []
  if (!response.ok) throw new Error(await readError(response, 'Failed to load sales order lines'))
  return response.json()
}

export async function addComment(id, comment) {
  const response = await apiFetch(`/api/tickets/${id}/comments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ comment }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to add comment'))
  return response.json()
}

// Hibernate Envers revision history, flattened server-side into one row per changed
// property (see TicketHistoryService).
export async function fetchTicketHistory(id) {
  const response = await apiFetch(`/api/tickets/${id}/history`)
  if (!response.ok) throw new Error(await readError(response, 'Failed to load ticket history'))
  return response.json()
}

// "Export ticket report" CSV — a plain <a href> can't tell the empty-export case (204, see
// TicketController#exportTicketReport) apart from a real file, so this fetches it instead and
// hands the caller either a Blob to download or null to report "nothing to export".
export async function exportTicketReport() {
  const response = await apiFetch('/api/tickets/report-export')
  if (response.status === 204) return null
  if (!response.ok) throw new Error(await readError(response, 'Failed to export ticket report'))
  return response.blob()
}

// Bulk close-ticket CSV import (real format confirmed ) — everyday CS
// work, the same reconciliation POST /api/tickets/{id}/close already does one row at a time, so
// this lives here rather than in adminApi.js. No Content-Type header set here on purpose: the
// browser fills in the multipart boundary itself from the FormData body — setting it manually
// would break the upload.
export async function importCloseTickets(file) {
  const formData = new FormData()
  formData.append('file', file)
  const response = await apiFetch('/api/tickets/close-import', {
    method: 'POST',
    body: formData,
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to import close-ticket CSV'))
  return response.json()
}

// Dashboard's channel breakdown widgets — a single grouped-count call rather than
// dashboardStats.js's per-status fetch loop, since this needs status/action x channel
// combinations, not just one total per status.
export async function fetchChannelBreakdown() {
  const response = await apiFetch('/api/tickets/stats/channel-breakdown')
  if (!response.ok) throw new Error(await readError(response, 'Failed to load channel breakdown'))
  return response.json()
}
