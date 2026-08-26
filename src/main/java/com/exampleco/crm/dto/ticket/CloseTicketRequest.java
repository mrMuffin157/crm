package com.exampleco.crm.dto.ticket;

// Request shape for TicketController#closeTicket — mirrors legacy-app's #closeticketform modal
// (tracking number + an optional closing comment). Both fields are optional: a blank
// trackingNumber leaves the ticket's existing one alone, a blank comment adds none.
public record CloseTicketRequest(String trackingNumber, String comment) {
}
