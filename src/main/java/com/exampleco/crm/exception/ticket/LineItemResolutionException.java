package com.exampleco.crm.exception.ticket;

// Thrown when legacy-app's own POST /api/ticketApi/resolveLineItems can't resolve a
// ticket's line items into channel SKUs — legacy-app itself rejecting the request, an unreachable
// legacy-app host, or no legacy-app host at all for the ticket's own sourceSystem. Always blocks the
// ticket save that triggered it (see TicketController#saveLineItems): a line item is never
// persisted with a null/stale channelSku, since legacy-app's own SalesOrderService/
// FbaOutboundApiService key a real MCF submission off that value.
//
// Distinct from IllegalArgumentException on purpose: that handler prefixes its response with
// "Invalid request: ", which would mangle legacy-app's own error message; this type's own
// @ExceptionHandler in TicketController surfaces this exception's message verbatim instead, per
// 's "resolution failure blocks the save (400), showing legacy-app's message verbatim"
// rule.
public class LineItemResolutionException extends RuntimeException {
    public LineItemResolutionException(String message) {
        super(message);
    }
}
