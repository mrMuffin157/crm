package com.exampleco.crm.service.ticket;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

// The single shared monitor guarding every non-@GeneratedValue id generation in the ticket
// domain (Ticket.id, TicketComment.id — neither is a real auto-increment column; see their own
// field comments). Was a private `createLock` field on TicketController alone; pulled out into
// its own bean so TicketCommentService (and, by extension, TicketCloseService and the CSV bulk
// import) can share the exact same lock instance instead of each call site getting its own —
// CLAUDE.md's single-instance-assumptions section is explicit that a new id-generation site
// should reuse this lock, not add a new one.
@Component
public class TicketIdLock {

    private final Object lock = new Object();

    public <T> T withLock(Supplier<T> action) {
        synchronized (lock) {
            return action.get();
        }
    }
}
