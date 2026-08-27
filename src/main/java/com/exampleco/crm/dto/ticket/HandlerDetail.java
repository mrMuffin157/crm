package com.exampleco.crm.dto.ticket;

// Enrichment shape attached to Ticket.handler by TicketController#enrich when the "handler"
// include is requested (revised §2) — deliberately no id field: crm.s2user.id has no
// meaning to legacy-app (it's a fresh value minted by the migration's username-merge, unrelated to
// any id legacy-app ever had for that person), so username is the only identifier this API ever
// hands out for a person, matching HandleTicketTO.username on legacy-app's own side.
public record HandlerDetail(String username, String email, String name) {
}
