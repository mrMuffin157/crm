package com.exampleco.crm.dto.salesorder;

// Frontend-facing shape for GET /api/ticketApi/salesOrderLines?orderNumber= — see
// LegacyApiClient#salesOrderLines. legacy-app's own response (confirmed live as of exampleco-core
// 5.4.7) is com.exampleco.core.dto.ticket.SalesOrderLinesResultTO, a wrapper object ({"lines": [...],
// "asinMismatchSkus": [...]}), not a bare array — LegacyApiClient maps each
// SalesOrderLineOptionTO into this narrower record rather than exposing that shape (or its
// asinMismatchSkus warning, not yet surfaced by CRM) directly to the frontend. `id` — legacy-app's
// own SalesOrderLineItem row id — is what the Refund line-item picker (TicketFormFields'
// RefundItemRows) needs to reference a specific line when it eventually asks legacy-app to process
// the refund, plus `amazonsku` (per the ticket's own wording) and enough of the rest (sku/name/
// quantity/unitPrice) to render the picker and let the user derive a default refund amount
// (unitPrice * quantity) before it's saved into LineItemInput#refundAmount.
public record SalesOrderLineTO(
        Long id,
        String sku,
        String amazonsku,
        String name,
        Double quantity,
        Double unitPrice
) {
}
