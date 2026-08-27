package com.exampleco.crm.dto.ticket;

// Enrichment shape attached to Ticket.customer by TicketController#enrich when the "customer"
// include is requested — the live customer record, as opposed to
// Ticket.customerName/email, which are a snapshot taken at ticket-creation time and can go
// stale (see domain.ticket.Customer's own class comment).
public record CustomerDetail(String id, String name, String email) {
}
