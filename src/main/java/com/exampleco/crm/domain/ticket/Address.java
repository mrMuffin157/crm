package com.exampleco.crm.domain.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// Read-write now (was read-only): backs the ticket list's "State" column and the sales-order
// list/detail's Buyer/Ship-To fields as before, but TicketController's Create/Edit ticket
// form also creates/updates rows here directly when its Customer Address block is filled in
// (id is a plain varchar with no DB-side generation, so new rows get an app-generated UUID —
// see TicketController#saveAddress).
@Entity
@Table(name = "address")
@Getter
@Setter
public class Address {

    @Id
    private String id;

    // NOT NULL on the real table with no DB-side default, same reasoning as Ticket.sourceSystem
    // — only ever set on brand-new address rows (TicketController#saveAddress); an existing
    // address being edited keeps whichever legacy storefront it actually migrated from.
    @Column(name = "source_system")
    private String sourceSystem;

    private String state;

    private String name;

    private String phone;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    private String city;

    @Column(name = "zip_code")
    private String zipCode;

    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "date_created")
    private LocalDateTime dateCreated;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;
}
