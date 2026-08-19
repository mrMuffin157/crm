// Mirrors the displayString labels from exampleco-core's shared TicketStatus/TicketAction/
// TicketReason/RefundType enums (com.exampleco.core.dto.ticket), keyed by the enum's own name() —
// the wire format Ticket's default JSON serialization now uses (see TicketEnumWireFormatTest),
// replacing the legacy numeric id this file used to key by. Keep this in sync with those enums.

export const STATUS_LABELS = {
  New: 'New',
  Processing: 'Processing',
  Closed: 'Closed',
  Cancelled: 'Cancelled',
  ProcessFailed: 'ProcessFailed',
}

export const ACTION_LABELS = {
  NoAction: 'NoAction',
  Replacement: 'Replacement',
  Refund: 'Refund',
  ManualHandling: 'ManualHandling',
}

export const REASON_LABELS = {
  Defective: 'Defective',
  MissingParts: 'Missing parts',
  QualityUnacceptable: 'Quality Unacceptable',
  NotAsDescribed: 'Not As\nDescribed',
  PartNotCompatible: 'Part Not Compatible',
  NotCompatible: 'Not Compatible',
  ShippingDamage: 'Shipping\nDamage',
  UnwantedItem: 'Unwanted Item',
  MissedEstimatedDelivery: 'Missed Estimated Delivery',
}

export const REFUND_TYPE_LABELS = {
  CustomerReturn: 'Customer Return',
  GeneralAdjustment: 'General Adjustment',
}

// Every real legacy-app channel code a brand-new ticket's source_system must be one of — the full
// "az-*" set of exampleco-core's SalesChannel, which is exactly what TicketEnums.
// SOURCE_SYSTEM_CHANNELS derives on the backend and validates a create against. Offered via the
// Create Ticket form's Channel picker so a fresh ticket gets a real channel instead
// of the old "crm" placeholder. Not editable after creation, so EditTicketModal/
// TicketFormFields' 'edit' mode never renders this.
//
// The backend derives its set from SalesChannel, but there's no endpoint serving it, so this
// mirrors those codes by hand like the other enums here — keep it in sync with SalesChannel's
// own "az-*" constants. The first 4 labels are TicketReportCsvService.ORDER_SOURCE_LABELS's, the
// same strings the ticket report CSV's Order Source column carries; that map has no entry for
// the other 4, so those get a display label in the same Store* family (exampleco-core's own names
// for them are
// AmazonCT/AmazonAT/AmazonMX/AmazonDecoCA). The 4 BRANDE/MX/DecoCA channels have no
// legacy.api.hosts.* host configured yet, so a ticket created on one of them can't have its line
// items resolved against legacy-app and won't send a ticketClosed notification — see
// LegacyApiProperties.Hosts#hostForSourceSystem.
export const SOURCE_SYSTEM_LABELS = {
  'az-us': 'StoreUS',
  'az-deco': 'StoreDeco',
  'az-ca': 'StoreCA',
  'az-au': 'StoreAU',
  'az-brande-ca': 'StoreCA-BRANDE',
  'az-brande-au': 'StoreAU-BRANDE',
  'az-mx': 'StoreMX',
  'az-decoca': 'StoreDecoCA',
}

function toOptions(labels) {
  return Object.entries(labels).map(([value, label]) => ({ value, label }))
}

// Static option lists for filter/form dropdowns — the full enum, not just values seen in
// the currently-loaded page of data (matching the real "ALL" + fixed list behavior).
//
// STATUS/REASON/ACTION all need an explicit order array rather than toOptions()'s plain
// Object.entries(labels) — these used to be keyed by legacy-app's legacy numeric id, and
// Object.entries on an all-numeric-string-keyed object coerces those keys to ascending integer
// order for free (see the old STATUS_LABELS/REASON_LABELS), which happened to already match the
// dropdown order CS is used to. Now that the keys are the enum's own name, toOptions() would
// sort alphabetically instead — a silent reshuffle (e.g. "Missed Estimated Delivery" jumping
// from last to second in the Reason list) — so each gets the same explicit-order treatment
// ACTION_OPTIONS already needed for its own non-declaration-order ids.
const STATUS_ORDER = ['New', 'Processing', 'Closed', 'Cancelled', 'ProcessFailed']
export const STATUS_OPTIONS = STATUS_ORDER.map((value) => ({ value, label: STATUS_LABELS[value] }))

// The real legacy-app form's dropdown comes from Groovy's TicketAction.values(), which returns
// declaration order — Replacement, Refund, ManualHandling, NoAction — so the CRM dropdown uses
// that explicit order to match rather than ACTION_LABELS' own key order.
const ACTION_ORDER = ['Replacement', 'Refund', 'ManualHandling', 'NoAction']
export const ACTION_OPTIONS = ACTION_ORDER.map((value) => ({ value, label: ACTION_LABELS[value] }))

const REASON_ORDER = [
  'Defective', 'MissingParts', 'QualityUnacceptable', 'NotAsDescribed', 'PartNotCompatible',
  'NotCompatible', 'ShippingDamage', 'UnwantedItem', 'MissedEstimatedDelivery',
]
export const REASON_OPTIONS = REASON_ORDER.map((value) => ({ value, label: REASON_LABELS[value] }))

// RefundType's legacy ids (CustomerReturn 1, GeneralAdjustment 2) already sorted alphabetically,
// so toOptions()'s Object.entries order is unaffected by the numeric-id -> name key change.
export const REFUND_TYPE_OPTIONS = toOptions(REFUND_TYPE_LABELS)
export const SOURCE_SYSTEM_OPTIONS = toOptions(SOURCE_SYSTEM_LABELS)

export const CLOSED_STATUS_IDS = new Set(['Closed', 'Cancelled'])

// Mirrors TicketController's changeStatus endpoint — the value the Cancel button in the Status
// column sends to PATCH /api/tickets/{id}/status.
export const STATUS_CANCELLED = 'Cancelled'

// Named handles for the other status values referenced directly in app logic (not just
// displayed via STATUS_LABELS) — same treatment as STATUS_CANCELLED above. Used by the
// dashboard stat cards/sidebar summary to filter the Tickets page by status.
export const STATUS_NEW = 'New'
export const STATUS_PROCESSING = 'Processing'
export const STATUS_PROCESS_FAILED = 'ProcessFailed'

// Matches TicketFormFields.jsx's own showsRefundBlock(): Refund Type is only ever meaningful
// for a Refund ticket — NoAction, Replacement, and ManualHandling tickets never populate it —
// so the ticket detail view hides the row entirely outside of Refund rather than showing an
// empty "—".
export const ACTION_REFUND = 'Refund'

export function statusLabel(status) {
  if (status == null || status === '') return null
  return STATUS_LABELS[status] ?? status
}

export function actionLabel(action) {
  if (action == null || action === '') return null
  return ACTION_LABELS[action] ?? action
}

export function reasonLabel(reason) {
  if (reason == null || reason === '') return null
  return REASON_LABELS[reason] ?? reason
}

export function refundTypeLabel(refundType) {
  if (refundType == null || refundType === '') return null
  return REFUND_TYPE_LABELS[refundType] ?? refundType
}

export function sourceSystemLabel(sourceSystem) {
  if (sourceSystem == null || sourceSystem === '') return null
  return SOURCE_SYSTEM_LABELS[sourceSystem] ?? sourceSystem
}

// Short form of sourceSystemLabel for narrow-column display (the ticket table's Source
// column) — strips the "Store" prefix every SOURCE_SYSTEM_LABELS entry shares, e.g.
// "StoreUS" -> "US", "StoreCA-BRANDE" -> "CA-BRANDE".
export function sourceSystemShortLabel(sourceSystem) {
  const label = sourceSystemLabel(sourceSystem)
  return label ? label.replace(/^Store/, '') : label
}

export function isTicketLocked(status) {
  return CLOSED_STATUS_IDS.has(String(status))
}

// Full US state/Canadian province/territory name -> postal abbreviation, keyed lowercase for
// case-insensitive lookup. Addresses in ticket.state come from legacy-app's legacy per-storefront
// data, which isn't consistently normalized to the 2-letter form.
const STATE_NAME_TO_ABBR = {
  alabama: 'AL', alaska: 'AK', arizona: 'AZ', arkansas: 'AR', california: 'CA',
  colorado: 'CO', connecticut: 'CT', delaware: 'DE', 'district of columbia': 'DC',
  florida: 'FL', georgia: 'GA', hawaii: 'HI', idaho: 'ID', illinois: 'IL',
  indiana: 'IN', iowa: 'IA', kansas: 'KS', kentucky: 'KY', louisiana: 'LA',
  maine: 'ME', maryland: 'MD', massachusetts: 'MA', michigan: 'MI', minnesota: 'MN',
  mississippi: 'MS', missouri: 'MO', montana: 'MT', nebraska: 'NE', nevada: 'NV',
  'new hampshire': 'NH', 'new jersey': 'NJ', 'new mexico': 'NM', 'new york': 'NY',
  'north carolina': 'NC', 'north dakota': 'ND', ohio: 'OH', oklahoma: 'OK', oregon: 'OR',
  pennsylvania: 'PA', 'rhode island': 'RI', 'south carolina': 'SC', 'south dakota': 'SD',
  tennessee: 'TN', texas: 'TX', utah: 'UT', vermont: 'VT', virginia: 'VA',
  washington: 'WA', 'west virginia': 'WV', wisconsin: 'WI', wyoming: 'WY',
  'puerto rico': 'PR', guam: 'GU', 'virgin islands': 'VI',
  alberta: 'AB', 'british columbia': 'BC', manitoba: 'MB', 'new brunswick': 'NB',
  'newfoundland and labrador': 'NL', 'northwest territories': 'NT', 'nova scotia': 'NS',
  nunavut: 'NU', ontario: 'ON', 'prince edward island': 'PE', quebec: 'QC',
  saskatchewan: 'SK', yukon: 'YT',
}

// Normalizes a state/province value to its postal abbreviation regardless of case or
// whether it was already stored abbreviated — falls back to the raw value untouched for
// anything not in the map (e.g. non-US/CA addresses) rather than guessing.
export function stateAbbreviation(state) {
  if (state == null) return null
  const trimmed = String(state).trim()
  if (!trimmed) return null
  const abbr = STATE_NAME_TO_ABBR[trimmed.toLowerCase()]
  if (abbr) return abbr
  return trimmed.length <= 3 ? trimmed.toUpperCase() : trimmed
}
