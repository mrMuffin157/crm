package com.exampleco.crm.dto.ticket;

import java.time.LocalDateTime;

// Enrichment shape attached to Ticket.comments by TicketController#enrich.
public record CommentSummary(String comment, String authorUsername, LocalDateTime dateCreated) {
}
