package com.exampleco.crm.dto.ticket;

// Request body for PATCH /api/customers/{id} — legacy-app's "customer.name == 'n/a'"
// backfill, once it resolves a real name for a customer the migration only ever saw referenced
// by a ticket, never by a real order.
public record UpdateCustomerNameRequest(String name) {
}
