// "Excel export" button next to "Export ticket report" — builds an .xlsx from whatever set of
// tickets the caller hands it (App.jsx fetches every page matching the Tickets page's current
// filters/sort via fetchAllTicketsForExport before calling downloadTicketsExcel). Always the
// same fixed set of ticket fields, independent of the user's own resizable/reorderable table
// column layout (ticketColumns.jsx) — this is a data export, not a screenshot of the table.
import * as XLSX from 'xlsx'
import { ACTION_REFUND, actionLabel, reasonLabel, refundTypeLabel, sourceSystemLabel, statusLabel } from './ticketEnums.js'

// Same per-action split the Tickets table itself makes (TicketTable's lineItemQuantity /
// isRefund): a Refund line is an amount, not a count of things, so it leads with its own
// refundAmount and carries no quantity — every Refund row stores a literal 0 there, which this
// column used to spell out as a meaningless "x0". Every other action keeps the quantity and has
// no per-line amount to show (their refundAmount is always 0 — see TicketLineItem#refundAmount).
function formatLineItems(lineItems, isRefund) {
  if (!lineItems || lineItems.length === 0) return ''
  return lineItems
    .map((li) => (isRefund
      ? [formatMoney(li.refundAmount), li.sku, li.name]
      : [li.sku, li.name, li.quantity != null ? `x${li.quantity}` : null]))
    .map((parts) => parts.filter(Boolean).join(' '))
    .join('; ')
}

// Mirrors TicketTable's own formatMoney — not locale-aware for the same reason: this export
// mixes US/CA/AU/MX channels in one sheet, so a locale-chosen currency symbol would be a
// confident lie about which currency the number actually is.
function formatMoney(value) {
  const amount = Number(value)
  return Number.isFinite(amount) ? `$${amount.toFixed(2)}` : null
}

function formatAddress(address) {
  if (!address) return ''
  const cityStateZip = [address.city, address.state].filter(Boolean).join(', ')
  const lastLine = [cityStateZip, address.zipCode].filter(Boolean).join(' ')
  return [address.name, address.addressLine1, address.addressLine2, lastLine, address.countryCode]
    .filter(Boolean)
    .join(', ')
}

function yesNo(value) {
  if (value == null) return ''
  return value ? 'Yes' : 'No'
}

// header: the column's spreadsheet title. value: how to pull it off an enriched Ticket (the
// same shape the Tickets page's own search response already returns — see TicketController's
// enrich(), which always loads address/handler/lineItems/customer/comments for that endpoint).
const EXPORT_COLUMNS = [
  { header: 'Ticket #', value: (t) => t.id },
  { header: 'Legacy ID', value: (t) => t.legacyId },
  { header: 'Channel', value: (t) => sourceSystemLabel(t.sourceSystem) },
  { header: 'State', value: (t) => t.state },
  { header: 'Action', value: (t) => actionLabel(t.action) },
  { header: 'Status', value: (t) => statusLabel(t.status) },
  { header: 'Reason', value: (t) => reasonLabel(t.reason) },
  { header: 'Refund Type', value: (t) => refundTypeLabel(t.refundType) },
  { header: 'Order #', value: (t) => t.orderNumber },
  { header: 'Replacement Order #', value: (t) => t.replacementOrderNumber },
  { header: 'Tracking #', value: (t) => t.trackingNumber },
  { header: 'Customer ID', value: (t) => t.customerId },
  { header: 'Customer Name', value: (t) => t.customerName },
  { header: 'Email', value: (t) => t.email },
  { header: 'Description', value: (t) => t.description },
  { header: 'Handler', value: (t) => t.handler?.username || t.handlerUsername },
  { header: 'Starred', value: (t) => yesNo(t.starred) },
  { header: 'Out of Warranty', value: (t) => yesNo(t.outOfWarrant) },
  { header: 'Date Created', value: (t) => t.dateCreated },
  { header: 'Last Updated', value: (t) => t.lastUpdated },
  { header: 'Last Exception', value: (t) => t.lastException },
  { header: 'Total Refund Amount', value: (t) => t.totalRefundAmount },
  { header: 'Line Items', value: (t) => formatLineItems(t.lineItems, t.action === ACTION_REFUND) },
  { header: 'Address', value: (t) => formatAddress(t.address) },
]

function buildExportRows(tickets) {
  return tickets.map((ticket) => {
    const row = {}
    for (const column of EXPORT_COLUMNS) {
      row[column.header] = column.value(ticket) ?? ''
    }
    return row
  })
}

export function downloadTicketsExcel(tickets, filename = 'tickets-export.xlsx') {
  const rows = buildExportRows(tickets)
  const sheet = XLSX.utils.json_to_sheet(rows, { header: EXPORT_COLUMNS.map((c) => c.header) })
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, sheet, 'Tickets')
  XLSX.writeFile(workbook, filename)
}
