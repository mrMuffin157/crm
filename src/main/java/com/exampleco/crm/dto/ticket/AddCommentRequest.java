package com.exampleco.crm.dto.ticket;

// Request shape for TicketController#addTicketComment — mirrors legacy-app's #addcommentform
// modal (a single free-text comment body). `author` is M2M-only: legacy-app's
// scheduled jobs authenticate as the shared service principal, not a specific staff member, so
// they name the real comment author explicitly instead of it being inferred from the session.
// Ignored for ordinary session-authenticated callers — see addTicketComment().
public record AddCommentRequest(String comment, String author) {
}
