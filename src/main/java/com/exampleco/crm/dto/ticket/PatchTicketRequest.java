package com.exampleco.crm.dto.ticket;

// Request body for the M2M PATCH /api/tickets/{id} — legacy-app's jobs write back
// exactly these three fields after MCF/tracking outcomes. Each is optional: a null field means
// "leave this alone", not "clear it" — this endpoint has no way to explicitly null out a field
// that's already set, which none of legacy-app's retained jobs need to do.
//
// status is legacy-app's own enum constant name (e.g. "ProcessFailed"), translated via
// TicketStatus.valueOf the same way GET /api/tickets/search's status/action filters are.
public record PatchTicketRequest(String status, String trackingNumber, String replacementOrderNumber) {
}
