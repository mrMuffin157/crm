package com.exampleco.crm.dto.ticket;


// Enrichment shape attached to Ticket.lineItems by TicketController#enrich. `refundAmount`
// is 0 for every non-Refund row; Ticket#totalRefundAmount sums this same field across
// a ticket's lineItems in the same enrich() pass rather than a separate query. `id` is the
// underlying ticket_line_item row's own auto-increment id, serialized to a CRM session caller
// as part of the raw Ticket entity — exampleco-core's CrmTicketLineItemTO has deliberately no
// such field, so CrmTicketMapper never reads it.
public record LineItemSummary(Long id, String sku, String name, Double quantity, String channelSku, Double refundAmount) {
}
