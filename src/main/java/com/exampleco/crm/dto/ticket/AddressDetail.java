package com.exampleco.crm.dto.ticket;


// Enrichment shape attached to Ticket.address by TicketController#enrich — the full address
// fields the edit form needs to prefill its Customer Address block (Ticket.state alone,
// already used by the list column, isn't enough for that).
public record AddressDetail(String name, String addressLine1, String addressLine2, String city,
                             String state, String zipCode, String countryCode, String phone) {
}
