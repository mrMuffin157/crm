// legacy-app's getSalesOrderInfo endpoint (SalesOrderInfoTO) returns status/salesChannel/
// fulfillmentType as the enum constant NAME itself (e.g. "Shipped", "AmazonBrandD"), not the
// numeric legacy id the rest of this app's DB-backed tables use — so there's nothing to decode
// here. This file only builds the Amazon Seller Central link, keyed off that channel name.
const SELLER_CENTRAL_DOMAINS = {
  AmazonBrandD: 'Amazon.com',
  AmazonDeco: 'Amazon.com',
  AmazonCA: 'Amazon.ca',
  AmazonDecoCA: 'Amazon.ca',
  AmazonCT: 'Amazon.ca',
  AmazonAU: 'Amazon.com.au',
  AmazonAT: 'Amazon.com.au',
  AmazonMX: 'Amazon.com.mx',
}

// legacy-app's SalesChannel enum name -> that channel's own "az-*" channelCode, i.e. exactly what
// Ticket.sourceSystem carries and what the Create Ticket form's Channel picker offers
// (ticketEnums.js SOURCE_SYSTEM_LABELS). A ticket raised from a sales order belongs to that
// order's channel by definition, so the picker is prefilled and locked from this instead of
// being asked again — see SalesOrdersPage's LinkedTicketsSection and TicketFormFields' own
// Channel picker.
//
// Only the 8 "az-*" channels are mapped, because those are exactly the codes the backend's
// TicketEnums.SOURCE_SYSTEM_CHANNELS validates a create against: a non-Amazon channel
// (Walmart/Chalore/SI/RMA) deliberately maps to nothing, leaving the picker empty and editable
// rather than prefilling a code the create would reject.
const SOURCE_SYSTEM_BY_SALES_CHANNEL = {
  AmazonBrandD: 'az-us',
  AmazonDeco: 'az-deco',
  AmazonCA: 'az-ca',
  AmazonAU: 'az-au',
  AmazonCT: 'az-brande-ca',
  AmazonAT: 'az-brande-au',
  AmazonMX: 'az-mx',
  AmazonDecoCA: 'az-decoca',
}

export function sourceSystemForOrder(order) {
  return SOURCE_SYSTEM_BY_SALES_CHANNEL[order?.salesChannel] || ''
}

export function sellerCentralUrl(order) {
  if (!order?.amazonOrderId) return null
  const domain = SELLER_CENTRAL_DOMAINS[order.salesChannel]
  if (!domain) return null
  return `https://sellercentral.${domain.toLowerCase()}/orders-v3/order/${order.amazonOrderId}`
}

// legacy-app's SalesChannel enum name -> the Amazon storefront domain a listing page lives on, so
// a line item's ASIN can be opened directly. Deliberately separate from
// SELLER_CENTRAL_DOMAINS above: that maps to the *seller* console
// (sellercentral.amazon.ca/orders-v3/...), this to the public *buyer* listing
// (amazon.ca/dp/<ASIN>), and the two are only coincidentally similar for some channels — AU is
// sellercentral.amazon.com.au but amazon.com.au, and a future channel could diverge further.
// Only channels with a real storefront are listed; anything unmapped renders as plain text
// rather than a link to a domain that might not carry that ASIN at all.
const ASIN_DOMAINS = {
  AmazonBrandD: 'amazon.com',
  AmazonDeco: 'amazon.com',
  AmazonCA: 'amazon.ca',
  AmazonDecoCA: 'amazon.ca',
  AmazonCT: 'amazon.ca',
  AmazonAU: 'amazon.com.au',
  AmazonAT: 'amazon.com.au',
  AmazonMX: 'amazon.com.mx',
}

export function asinUrl(order, asin) {
  if (!asin) return null
  const domain = ASIN_DOMAINS[order?.salesChannel]
  if (!domain) return null
  return `https://www.${domain}/dp/${asin}`
}
