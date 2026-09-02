package com.exampleco.crm.exception.ticket;

// Thrown when a session member tries to create a ticket under a channel they haven't been
// granted (see ChannelAccessService). Distinct from IllegalArgumentException (a
// malformed/unknown sourceSystem value) since this is a real, valid channel the caller simply
// isn't authorized for — mapped to 403 Forbidden in TicketController's own exception handler,
// not 400.
public class ChannelNotAuthorizedException extends RuntimeException {
    public ChannelNotAuthorizedException(String sourceSystem) {
        super("You are not authorized to create tickets for channel: " + sourceSystem);
    }
}
