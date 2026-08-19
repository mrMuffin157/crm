import { useRef, useState } from 'react'
import { fetchSalesOrderLines } from './api.js'
import {
  ACTION_OPTIONS,
  REASON_OPTIONS,
  REFUND_TYPE_OPTIONS,
  SOURCE_SYSTEM_OPTIONS,
} from './ticketEnums.js'

const EMPTY_ADDRESS = {
  name: '',
  addressLine1: '',
  addressLine2: '',
  city: '',
  state: '',
  zipCode: '',
  countryCode: '',
  phone: '',
}

export const EMPTY_TICKET_FORM = {
  // Only ever set/shown on the Create form (mode: 'create') — see the Channel picker below and
  // its own comment. Not part of ticketToForm()/EditTicketModal: channel is fixed at creation
  // and never editable afterward.
  sourceSystem: '',
  action: '',
  status: '',
  reason: '',
  // Only shown as a control when Action is Refund (see showsRefundBlock below). Kept in state
  // regardless so editing a pre-existing ticket doesn't silently null out a refundType it
  // already had.
  refundType: '',
  customerId: '',
  customerName: '',
  email: '',
  orderNumber: '',
  description: '',
  trackingNumber: '',
  replacementOrderNumber: '',
  handlerUsername: '',
  starred: false,
  outOfWarrant: false,
  address: EMPTY_ADDRESS,
  items: [],
}

// The form no longer renders an input for status/customerId/trackingNumber/
// replacementOrderNumber/handlerUsername (legacy-app's edit form has none), but they stay
// in form state so buildTicketPayload sends each ticket's existing value straight back and the
// save leaves them exactly as they were, same carry-through rule refundType already followed.
export function ticketToForm(ticket) {
  return {
    action: ticket.action ?? '',
    status: ticket.status ?? '',
    reason: ticket.reason ?? '',
    refundType: ticket.refundType ?? '',
    customerId: ticket.customerId ?? '',
    customerName: ticket.customerName ?? '',
    email: ticket.email ?? '',
    orderNumber: ticket.orderNumber ?? '',
    description: ticket.description ?? '',
    trackingNumber: ticket.trackingNumber ?? '',
    replacementOrderNumber: ticket.replacementOrderNumber ?? '',
    handlerUsername: ticket.handlerUsername ?? '',
    starred: Boolean(ticket.starred),
    outOfWarrant: Boolean(ticket.outOfWarrant),
    address: ticket.address
      ? {
        name: ticket.address.name ?? '',
        addressLine1: ticket.address.addressLine1 ?? '',
        addressLine2: ticket.address.addressLine2 ?? '',
        city: ticket.address.city ?? '',
        state: ticket.address.state ?? '',
        zipCode: ticket.address.zipCode ?? '',
        countryCode: ticket.address.countryCode ?? '',
        phone: ticket.address.phone ?? '',
      }
      : EMPTY_ADDRESS,
    items: (ticket.lineItems ?? []).map((item) => ({
      sku: item.sku ?? '',
      name: item.name ?? '',
      quantity: item.quantity ?? 1,
      // No input in this form sets channelSku by hand (see LineItemInput's own comment) —
      // carried through untouched for Replacement/ManualHandling rows so editing one doesn't
      // wipe out a value migrated data already had; Refund rows only ever get one from the
      // RefundItemRows picker below, same non-hand-typed rule.
      channelSku: item.channelSku ?? null,
      // Only meaningful for Refund rows (RefundItemRows) — 0 for every other row, same as the
      // backend's LineItemSummary#refundAmount.
      refundAmount: item.refundAmount ?? 0,
    })),
  }
}

function showsReplacementBlock(action) {
  return action === 'NoAction' || action === 'Replacement'
}

function showsManualHandleBlock(action) {
  return action === 'ManualHandling'
}

function showsAddressBlock(action) {
  return action === 'Replacement' || action === 'ManualHandling'
}

function showsRefundBlock(action) {
  return action === 'Refund'
}

// Which actions seed their item table from the sales order the form was opened from. NoAction
// shares Replacement's visible table (showsReplacementBlock covers both) but is deliberately
// left out: an action of "none" has nothing to do with those items, so filling the table for it
// would only give the user rows to delete. Replacement/Manual Handling/Refund all act on what
// was ordered, which is what makes seeding worth it there.
function seedsItemsFor(action) {
  return action === 'Replacement' || showsManualHandleBlock(action) || showsRefundBlock(action)
}

// Builds the API request body from form state — the null-vs-present contract matches
// TicketWriteRequest on the backend: null means "don't touch," a value means "set to this."
export function buildTicketPayload(form) {
  const refundBlock = showsRefundBlock(form.action)
  const itemsRelevant = showsReplacementBlock(form.action) || showsManualHandleBlock(form.action) || refundBlock
  const addressRelevant = showsAddressBlock(form.action)
  return {
    // Ignored by the backend on PUT (TicketWriteRequest#sourceSystem's own comment) — sent as
    // null here for an edit form (form.sourceSystem is never populated by ticketToForm), which
    // is a no-op, matching this payload's existing null-means-don't-touch contract.
    sourceSystem: form.sourceSystem || null,
    customerName: form.customerName || null,
    email: form.email || null,
    customerId: form.customerId || null,
    orderNumber: form.orderNumber || null,
    action: form.action || null,
    status: form.status || null,
    reason: form.reason || null,
    refundType: form.refundType || null,
    trackingNumber: form.trackingNumber || null,
    replacementOrderNumber: form.replacementOrderNumber || null,
    handlerUsername: form.handlerUsername || null,
    starred: form.starred,
    outOfWarrant: form.outOfWarrant,
    description: form.description || null,
    address: addressRelevant ? form.address : null,
    lineItems: itemsRelevant
      ? form.items.map((item) => ({
        sku: item.sku || null,
        name: showsManualHandleBlock(form.action) ? (item.name || null) : null,
        // A Refund row has no quantity of its own — legacy-app's refund table is SKU + Refund
        // Amount only and its refund path never wrote one, so every migrated Refund row stores
        // a literal 0. Pinned here rather than at each row-building site (RefundItemRows'
        // addFromLine, SalesOrdersPage's orderLineItemsToFormItems, an edited ticket's existing
        // rows) so the one shared item shape can't leak an ordered quantity onto a refund.
        quantity: refundBlock ? 0 : (Number(item.quantity) || 0),
        channelSku: item.channelSku || null,
        refundAmount: refundBlock ? (Number(item.refundAmount) || 0) : null,
      }))
      : null,
  }
}

// Mirrors the real form's checkSubmit(): item rows (with a non-blank SKU and qty > 0) are
// required whenever the corresponding items block is showing, and the address block's
// required fields (everything but line2/phone) must be filled in whenever it's showing.
// `mode` matches TicketFormFields' own prop and now only gates the Channel rule — every other
// check applies to both forms, since legacy-app's create and edit modals are the same form.
export function validateTicketForm(form, mode = 'edit') {
  const errors = []
  // Only required on create — channel is fixed at creation and not shown/editable on 'edit'
  // (see the Channel picker's own comment below).
  if (mode === 'create' && !form.sourceSystem) errors.push('Channel is required.')
  // Action is deliberately NOT required in either mode: the real form's Action <select> has no
  // `required` attribute and checkSubmit() has a blank-action fallthrough that raises no error —
  // legacy-app genuinely allows saving a ticket with no action picked, and its edit screen is the
  // same form. Clearing an existing ticket's action really does clear it (ticket.action
  // is a nullable column and applyFields writes the request's value straight through), which is
  // legacy-app's own behavior for the same edit — unlike status/handler_id, which are NOT NULL and
  // are preserved instead (TicketController#applyFields).
  if (!form.reason) errors.push('Reason is required.')
  if (!form.description.trim()) errors.push('Description is required.')

  const replacementBlock = showsReplacementBlock(form.action)
  const manualHandleBlock = showsManualHandleBlock(form.action)
  const addressBlock = showsAddressBlock(form.action)
  const refundBlock = showsRefundBlock(form.action)

  // checkSubmit() only hard-requires a row for Action=="Replacement" — NoAction shares the same
  // visible table (showsReplacementBlock covers both) but 0 rows is fine for it, and Refund's
  // rows are never row-count-checked either (only refundType + each row's amount, below).
  const requiresItemRow = form.action === 'Replacement' || manualHandleBlock

  if (replacementBlock || manualHandleBlock || refundBlock) {
    if (requiresItemRow && form.items.length === 0) {
      errors.push('At least one item row is required.')
    }
    form.items.forEach((item, i) => {
      if (!item.sku || !item.sku.trim()) errors.push(`Item ${i + 1}: SKU is required.`)
      if (manualHandleBlock && (!item.name || !item.name.trim())) errors.push(`Item ${i + 1}: Part name is required.`)
      // Refund rows are deliberately exempt: legacy-app's checkSubmit() only alerts on an empty
      // Qty for Replacement/ManualHandling, and its refund table has no Qty column at all —
      // a refund is an amount, not a count of things (see RefundItemRows below).
      if (!refundBlock && (!Number(item.quantity) || Number(item.quantity) <= 0)) errors.push(`Item ${i + 1}: Quantity must be greater than 0.`)
      if (refundBlock && !(Number(item.refundAmount) > 0)) errors.push(`Item ${i + 1}: Refund amount must be greater than 0.`)
    })
  }

  // checkSubmit() alerts "Refund Type is empty." before allowing a Refund-action save.
  if (refundBlock && !form.refundType) errors.push('Refund Type is required.')

  if (addressBlock) {
    if (!form.address.name.trim()) errors.push('Address Name is required.')
    if (!form.address.addressLine1.trim()) errors.push('Address Line 1 is required.')
    if (!form.address.city.trim()) errors.push('City is required.')
    if (!form.address.state.trim()) errors.push('State is required.')
    if (!form.address.zipCode.trim()) errors.push('Zip Code is required.')
    if (!form.address.countryCode.trim()) errors.push('Country Code is required.')
  }

  return errors
}

// The real form seeds the Replacement/ManualHandling tables straight from the sales order's own
// line items (readonly SKU, qty defaulted to 1) whenever the ticket is created from within that
// order's page. A form opened from a sales order does that seeding itself the moment an
// item-bearing action is picked (see seedsItemsFor and changeAction below), since the ticket is
// almost always about what was actually ordered — so this "Load Order Lines" picker is the
// re-add path (a row removed by hand, or a row for an order the form was opened without) rather
// than the only way in, and stays available regardless of the action.
function ItemRows({ items, onChange, showName, orderNumber }) {
  const [availableLines, setAvailableLines] = useState([])
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState(null)

  function updateRow(index, field, value) {
    const next = items.slice()
    next[index] = { ...next[index], [field]: value }
    onChange(next)
  }

  function removeRow(index) {
    onChange(items.filter((_, i) => i !== index))
  }

  function addRow() {
    onChange([...items, { sku: '', name: '', quantity: 1, channelSku: null }])
  }

  function addFromLine(line) {
    onChange([...items, {
      sku: line.sku || '',
      name: '',
      quantity: line.quantity ?? 1,
      channelSku: line.amazonsku || null,
    }])
  }

  function handleLoadLines() {
    const trimmed = (orderNumber || '').trim()
    if (!trimmed) {
      setLoadError('Enter an Order Number above first.')
      return
    }
    setLoading(true)
    setLoadError(null)
    fetchSalesOrderLines(trimmed)
      .then(setAvailableLines)
      .catch((err) => setLoadError(err.message))
      .finally(() => setLoading(false))
  }

  return (
    <div className="item-rows">
      <div className="refund-picker-toolbar">
        <button type="button" className="member-action-button" onClick={handleLoadLines} disabled={loading}>
          {loading ? 'Loading…' : 'Load Order Lines'}
        </button>
      </div>
      {loadError && <div className="create-ticket-message">{loadError}</div>}

      {availableLines.length > 0 && (
        <div className="table-scroll">
          <table className="detail-subtable">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Amazon SKU</th>
                <th>Name</th>
                <th>Qty</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {availableLines.map((line) => (
                <tr key={line.id}>
                  <td>{line.sku}</td>
                  <td>{line.amazonsku}</td>
                  <td>{line.name}</td>
                  <td>{line.quantity}</td>
                  <td>
                    <button type="button" className="member-action-button" onClick={() => addFromLine(line)}>
                      + Add
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {items.length > 0 && (
        <div className="table-scroll">
          <table className="detail-subtable">
            <thead>
              <tr>
                <th>SKU</th>
                {showName && <th>Part Name</th>}
                <th>Qty</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {items.map((row, i) => (
                <tr key={i}>
                  <td><input value={row.sku} onChange={(e) => updateRow(i, 'sku', e.target.value)} placeholder="SKU" /></td>
                  {showName && (
                    <td><input value={row.name} onChange={(e) => updateRow(i, 'name', e.target.value)} placeholder="Part name" /></td>
                  )}
                  <td>
                    <input
                      type="number"
                      min={1}
                      value={row.quantity}
                      onChange={(e) => updateRow(i, 'quantity', e.target.value)}
                      className="item-qty-input"
                    />
                  </td>
                  <td>
                    <button type="button" className="remove-item-button" title="Remove item" onClick={() => removeRow(i)}>
                      🗑️ Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <button type="button" className="member-action-button" onClick={addRow}>+ Add item</button>
    </div>
  )
}

// Refund's own item picker — distinct from ItemRows above because a Refund row isn't
// hand-typed the way a Replacement/ManualHandling row is: it starts from one of the current
// order's own sales-order lines (already seeded into `items` on picking the Refund action when
// the form was opened from a sales order page — see ItemRows' comment above) (GET /api/ticketApi/salesOrderLines?orderNumber=, see
// LegacyApiClient#salesOrderLines / SalesOrderLineTO), so the user picks from what legacy-app says was
// actually ordered rather than typing a SKU from scratch. `orderNumber` is `form.orderNumber` —
// present even withinSalesOrder (it's just not rendered as its own input there, see
// TicketFormFields' own contact-row comment) so the picker still has an order to look lines up
// for either way.
//
// The row is SKU + Amazon SKU + Refund Amount — no Qty column, mirroring legacy-app's own refund
// table (`_form.gsp`'s `.refundItems`, whose only editable cell is `.refundamount`) and its
// checkSubmit(), which validates a refund row's amount and never its quantity. A refund is an
// amount, not a count of things; the Replacement/ManualHandling tables above still have a Qty
// because those really do ship a number of items.
function RefundItemRows({ items, onChange, orderNumber }) {
  const [availableLines, setAvailableLines] = useState([])
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState(null)

  function updateRow(index, field, value) {
    const next = items.slice()
    next[index] = { ...next[index], [field]: value }
    onChange(next)
  }

  function removeRow(index) {
    onChange(items.filter((_, i) => i !== index))
  }

  // Seeds a new row from a picked sales order line: sku/name for display, channelSku from the
  // line's own amazonsku (never hand-typed — see LineItemInput's own comment), and a default
  // refundAmount (unitPrice * quantity) the user can still adjust before saving.
  //
  // The row itself carries no quantity — legacy-app's refund table is SKU + Refund Amount only, and
  // its refund path never wrote one (every migrated Refund row stores a literal 0; the payload
  // pins it to 0 for every Refund row, see buildTicketPayload). The ordered quantity still feeds
  // the default amount, exactly as legacy-app seeds that field
  // (`(soli.quantity * soli.unitPrice).round(2)`), it just isn't a value of its own afterward.
  function addFromLine(line) {
    const defaultAmount = line.unitPrice != null && line.quantity != null
      ? Math.round(line.unitPrice * line.quantity * 100) / 100
      : 0
    onChange([...items, {
      sku: line.sku || '',
      name: line.name || '',
      channelSku: line.amazonsku || null,
      refundAmount: defaultAmount,
    }])
  }

  function handleLoadLines() {
    const trimmed = (orderNumber || '').trim()
    if (!trimmed) {
      setLoadError('Enter an Order Number above first.')
      return
    }
    setLoading(true)
    setLoadError(null)
    fetchSalesOrderLines(trimmed)
      .then(setAvailableLines)
      .catch((err) => setLoadError(err.message))
      .finally(() => setLoading(false))
  }

  return (
    <div className="item-rows">
      <div className="refund-picker-toolbar">
        <button type="button" className="member-action-button" onClick={handleLoadLines} disabled={loading}>
          {loading ? 'Loading…' : 'Load Order Lines'}
        </button>
      </div>
      {loadError && <div className="create-ticket-message">{loadError}</div>}

      {availableLines.length > 0 && (
        <div className="table-scroll">
          <table className="detail-subtable">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Amazon SKU</th>
                <th>Name</th>
                <th>Qty</th>
                <th>Unit Price</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {availableLines.map((line) => (
                <tr key={line.id}>
                  <td>{line.sku}</td>
                  <td>{line.amazonsku}</td>
                  <td>{line.name}</td>
                  <td>{line.quantity}</td>
                  <td>{line.unitPrice}</td>
                  <td>
                    <button type="button" className="member-action-button" onClick={() => addFromLine(line)}>
                      + Add
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {items.length > 0 && (
        <div className="table-scroll">
          <table className="detail-subtable">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Amazon SKU</th>
                <th>Refund Amount</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {items.map((row, i) => (
                <tr key={i}>
                  <td><input value={row.sku} onChange={(e) => updateRow(i, 'sku', e.target.value)} placeholder="SKU" /></td>
                  <td>{row.channelSku || '—'}</td>
                  <td>
                    <input
                      type="number"
                      min={0}
                      step="0.01"
                      value={row.refundAmount}
                      onChange={(e) => updateRow(i, 'refundAmount', e.target.value)}
                      className="item-amount-input"
                    />
                  </td>
                  <td>
                    <button type="button" className="remove-item-button" title="Remove item" onClick={() => removeRow(i)}>
                      🗑️ Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function AddressFields({ address, onChange }) {
  function update(field, value) {
    onChange({ ...address, [field]: value })
  }

  return (
    <>
      <div className="address-row-split">
        <label>
          <span>Address Name*</span>
          <input value={address.name} onChange={(e) => update('name', e.target.value)} required />
        </label>
        <label>
          <span>Address Line1*</span>
          <input value={address.addressLine1} onChange={(e) => update('addressLine1', e.target.value)} required />
        </label>
      </div>
      <div className="form-grid">
        <label>
          <span>Address Line2</span>
          <input value={address.addressLine2} onChange={(e) => update('addressLine2', e.target.value)} />
        </label>
        <label>
          <span>City*</span>
          <input value={address.city} onChange={(e) => update('city', e.target.value)} required />
        </label>
        <label>
          <span>State*</span>
          <input value={address.state} onChange={(e) => update('state', e.target.value)} required />
        </label>
      </div>
      <div className="form-grid">
        <label>
          <span>Zip Code*</span>
          <input value={address.zipCode} onChange={(e) => update('zipCode', e.target.value)} required />
        </label>
        <label>
          <span>Country Code*</span>
          <input value={address.countryCode} onChange={(e) => update('countryCode', e.target.value)} required />
        </label>
        <label>
          <span>Phone</span>
          <input value={address.phone} onChange={(e) => update('phone', e.target.value)} />
        </label>
      </div>
    </>
  )
}

// Shared field set for both the "Create Ticket" and "Edit Ticket" forms — mirrors the real
// legacy form's checkAction()-driven blocks: which of the Replacement/Manual-Handling/Refund item
// table and the Customer Address block show up depends entirely on the selected Action. Refund
// was excluded from this form's own Action dropdown until added the sales-order
// line-item picker below (RefundItemRows) — it's now offered everywhere, same as every other
// action, since it no longer needs a sales-order-page context to be usable.
// `withinSalesOrder`: true when this form was opened from within a specific sales order's own
// page — matching the legacy form's own `<g:if test="${!salesOrder}">` gate, Order # is omitted
// entirely rather than shown empty, since it's implied by the order this ticket was created
// from (RefundItemRows still reads its value off `form.orderNumber`, just not as a rendered
// input in that case). It also locks the Channel picker to the channel that order is on — see
// the picker's own comment below.
// Both modes render the same legacy layout, because legacy-app's own Edit Ticket modal IS its Create
// Ticket modal — verified field-for-field against the live staging form: identical
// labels in identical order, identical item tables, differing only in title and submit copy.
// So there is no create-vs-edit layout branching here at all; `mode: 'create'` only adds the
// Channel picker (CRM-only, since channel is fixed at creation — see its own comment below).
// withinSalesOrder also locks that picker to the channel the order was prefilled with.
// Customer ID/Status/Tracking #/Replacement Order #/Handler Username have no input in either
// mode now: legacy-app's form has none, and an edit carries their existing values through untouched
// (see ticketToForm) rather than dropping them.
export default function TicketFormFields({ form, onChange, withinSalesOrder = false, mode = 'edit', allowedChannels, orderLineItems }) {
  // Seeded at most once per opening of the form, and only into an empty table: re-seeding would
  // either duplicate rows the user already has or resurrect ones they deliberately removed
  // while switching actions. Absent on the Edit form and the Ticket page's own Create button —
  // neither has an order to seed from — which leaves the Action picker below a plain field
  // there, exactly as before.
  const seededRef = useRef(false)

  function updateField(field, value) {
    onChange({ ...form, [field]: value })
  }

  function changeAction(action) {
    if (orderLineItems?.length && !seededRef.current && seedsItemsFor(action) && form.items.length === 0) {
      seededRef.current = true
      onChange({ ...form, action, items: orderLineItems })
      return
    }
    updateField('action', action)
  }

  const isCreate = mode === 'create'
  // A ticket raised from a sales order belongs to that order's own channel — SalesOrdersPage
  // prefills it (sourceSystemForOrder) and the picker only displays it, so it can't be changed
  // into a channel that contradicts the order. An order on a channel with no "az-*" code
  // prefills nothing and stays a normal editable picker, same as on the Ticket page.
  const channelLocked = isCreate && withinSalesOrder && Boolean(form.sourceSystem)
  // restrict the picker to this user's authorized channels when the caller supplies
  // that list (the plain Ticket page's Create Ticket button always does); omitted entirely
  // (undefined) means "don't restrict" rather than "restrict to nothing", so any other caller of
  // this shared component that doesn't yet pass allowedChannels keeps today's full-list behavior
  // — real enforcement is server-side (TicketController) regardless.
  const channelOptions = allowedChannels
    ? SOURCE_SYSTEM_OPTIONS.filter((o) => allowedChannels.includes(o.value))
    : SOURCE_SYSTEM_OPTIONS
  const replacementBlock = showsReplacementBlock(form.action)
  const manualHandleBlock = showsManualHandleBlock(form.action)
  const addressBlock = showsAddressBlock(form.action)
  const refundBlock = showsRefundBlock(form.action)

  const itemRows = (showName) => (
    <ItemRows items={form.items} onChange={(items) => updateField('items', items)} showName={showName} orderNumber={form.orderNumber} />
  )
  const refundItemRows = (
    <RefundItemRows items={form.items} onChange={(items) => updateField('items', items)} orderNumber={form.orderNumber} />
  )
  const addressFields = <AddressFields address={form.address} onChange={(address) => updateField('address', address)} />

  return (
    <>
      {/* Channel is fixed at creation and never editable afterward — only ever
          rendered on the Create form, never on Edit (mode: 'edit', the EditTicketModal default).
          Sets Ticket.sourceSystem to a real legacy-app channel code instead of the old "crm"
          placeholder every fresh ticket used to get. */}
      {isCreate && (
        <label className="full-field">
          <span>{channelLocked ? 'Channel* (from this sales order)' : 'Channel*'}</span>
          <select
            value={form.sourceSystem}
            onChange={(e) => updateField('sourceSystem', e.target.value)}
            disabled={channelLocked}
          >
            <option value="">Select…</option>
            {channelOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
      )}

      <label className="full-field">
        <span>Reason*</span>
        <select value={form.reason} onChange={(e) => updateField('reason', e.target.value)}>
          <option value="">Select…</option>
          {REASON_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </label>

      <label className="full-field">
        <span>Description*</span>
        <textarea rows={4} value={form.description} onChange={(e) => updateField('description', e.target.value)} />
      </label>

      <div className="checkbox-row">
        <label className="checkbox-field">
          <input type="checkbox" checked={form.starred} onChange={(e) => updateField('starred', e.target.checked)} />
          <span>Starred</span>
        </label>
        <label className="checkbox-field">
          <input type="checkbox" checked={form.outOfWarrant} onChange={(e) => updateField('outOfWarrant', e.target.checked)} />
          <span>Out-of-Warranty</span>
        </label>
      </div>

      <label className="full-field">
        <span>Action</span>
        <select value={form.action} onChange={(e) => changeAction(e.target.value)}>
          <option value="">Select…</option>
          {ACTION_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </label>

      {refundBlock && (
        <label className="full-field">
          <span>Refund Type</span>
          <select value={form.refundType} onChange={(e) => updateField('refundType', e.target.value)}>
            <option value="">Select…</option>
            {REFUND_TYPE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
      )}

      <div className="full-field">
        <span>Items</span>
        {replacementBlock && itemRows(false)}
        {manualHandleBlock && itemRows(true)}
        {refundBlock && refundItemRows}
      </div>

      {/* Fixed 3-column grid (not the auto-fit .form-grid) so that omitting Order # within
          a sales order leaves Customer Name/Email in their usual 1st/2nd column positions
          instead of stretching to fill the row — matches the legacy form's own layout. */}
      <div className="contact-row">
        {!withinSalesOrder && (
          <label>
            <span>Order Number</span>
            <input value={form.orderNumber} onChange={(e) => updateField('orderNumber', e.target.value)} />
          </label>
        )}
        <label>
          <span>Customer Name</span>
          <input value={form.customerName} onChange={(e) => updateField('customerName', e.target.value)} />
        </label>
        <label>
          <span>Email</span>
          <input type="email" value={form.email} onChange={(e) => updateField('email', e.target.value)} />
        </label>
      </div>

      {addressBlock && addressFields}
    </>
  )
}
