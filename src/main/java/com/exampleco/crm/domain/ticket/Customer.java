package com.exampleco.crm.domain.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

// Minimal mapping over the real customer table — deliberately maps only id/name/email plus the
// handful of other NOT NULL columns (doc/0.2.0/migrate_tickets_to_crm_dev.sql), same reasoning as
// this package's own S2User: this entity's primary job is resolving Ticket.customerId -> the
// name/email pair updateSearchText() needs, which can differ from Ticket.customerName/email (a
// snapshot taken at ticket-creation time that isn't kept in sync with the customer record
// afterward). Not read-only: CustomerController#updateCustomerName writes `name` directly (the
// M2M PATCH /api/customers/{id}), TicketCloseService's own "n/a" placeholder backfill
// writes it too on close, and TicketController#resolveCustomerId inserts a brand-new row (e.g.
// seeded from a Sales Order's CustomerTO) when a requested customerId isn't in this table yet —
// dateCreated/lastUpdated are mapped only so that insert can satisfy this table's other NOT NULL
// columns; nothing in this app reads them back. is_new/is_review_disabled were the same kind of
// insert-only NOT NULL filler and have been dropped from this mapping entirely since nothing
// anywhere ever read them back either; see doc/0.7.0/drop_customer_is_new_is_review_disabled.sql
// for the phased ALTER TABLE that removes them from the table itself.
@Entity
@Table(name = "customer")
@Getter
@Setter
public class Customer {

    @Id
    private String id;

    private String name;

    private String email;

    @Column(name = "date_created")
    private LocalDateTime dateCreated;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;
}
