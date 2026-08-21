package com.exampleco.crm.dto.ticket;

// Request shape for one row of TicketWriteRequest#lineItems. `name` is only meaningful for
// ManualHandling rows (free-text part description); Replacement rows leave it null.
// `channelSku` has no free-text CRM-side input to set it (see TicketLineItem#channelSku) —
// for Replacement/ManualHandling rows the frontend only ever round-trips whatever value the row
// already had; for Refund rows it's populated from the picked SalesOrderLineTO's own
// `amazonsku` instead, still never hand-typed.
// `refundAmount` is only meaningful for Refund rows (TicketFormFields' RefundItemRows) — null for
// every other action, which TicketController#saveLineItems treats as 0.
public record LineItemInput(String sku, String name, Double quantity, String channelSku, Double refundAmount) {
}
