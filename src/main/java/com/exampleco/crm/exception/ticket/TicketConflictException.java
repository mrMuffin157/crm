package com.exampleco.crm.exception.ticket;

// Thrown when the M2M PATCH /api/tickets/{id}'s If-Match header doesn't match the ticket's
// current lastUpdated — a CS agent edited the ticket in CRM after legacy-app's job
// last read it. Mapped to 412 Precondition Failed, the standard HTTP status for a conditional
// request whose precondition didn't hold, distinct from the 409 Conflict the locked-ticket
// checks elsewhere in TicketController use for IllegalStateException.
public class TicketConflictException extends RuntimeException {
    public TicketConflictException(Long id) {
        super("Ticket " + id + " was modified since it was last read (If-Match mismatch)");
    }
}
