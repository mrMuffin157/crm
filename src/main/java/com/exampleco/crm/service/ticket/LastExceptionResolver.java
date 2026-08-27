package com.exampleco.crm.service.ticket;

import java.util.List;

// Mirrors legacy-app's Ticket.groovy#getLastException(): the newest comment (by insertion order)
// whose text contains the exception marker. Extracted out of TicketController so
// NotifyFailedTicketsService can derive the same value without duplicating the
// scan logic — TicketController's own lastException field (surfaced on every ticket the M2M API
// returns, per ) and the daily failed-ticket digest must agree on what "the last
// exception" means.
public final class LastExceptionResolver {

    public static final String EXCEPTION_MARKER = "[Exception]";

    private LastExceptionResolver() {
    }

    // `comments` must be ordered oldest-first (see TicketCommentRepository#
    // findByTicketIdInOrderByDateCreatedAsc) — the last match in iteration order is then the
    // most recent one.
    public static String resolve(List<String> comments) {
        String lastException = null;
        for (String comment : comments) {
            if (comment != null && comment.contains(EXCEPTION_MARKER)) {
                lastException = comment;
            }
        }
        return lastException;
    }
}
