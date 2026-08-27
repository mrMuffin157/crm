package com.exampleco.crm.exception.ticket;

// Mirrors TicketNotFoundException — thrown by CustomerController when PATCH /api/customers/{id}
// is called with an id that doesn't exist.
public class CustomerNotFoundException extends RuntimeException {
    public CustomerNotFoundException(String id) {
        super("Customer not found: " + id);
    }
}
