package com.exampleco.crm.exception.ticket;

// Distinct from IllegalArgumentException on purpose: NumberFormatException and other
// request-parameter conversion failures (bad handlerId, bad date, bad boolean) are also
// IllegalArgumentException subtypes, and would otherwise collide with this "not found"
// case in TicketController's exception handling, mislabeling a 400-worthy bad input as a
// 404 with a confusing low-level message.
public class TicketNotFoundException extends RuntimeException {
    public TicketNotFoundException(Long id) {
        super("Ticket not found: " + id);
    }

    // For lookups by an alternate key (e.g. GET /api/tickets/by-legacy), where a
    // plain ticket id isn't part of the miss.
    public TicketNotFoundException(String message) {
        super(message);
    }
}
