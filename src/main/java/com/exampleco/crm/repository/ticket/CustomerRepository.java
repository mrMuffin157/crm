package com.exampleco.crm.repository.ticket;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.exampleco.crm.domain.ticket.Customer;

@Repository("ticketCustomerRepository")
public interface CustomerRepository extends JpaRepository<Customer, String> {

    // Backs linking a new/edited ticket to an already-migrated real customer row by email,
    // now that the 4 legacy DBs' customer data (and this table's own migration) is real rather
    // than placeholder. email is a DB-enforced unique key here (uk_email,
    // doc/0.2.0/migrate_tickets_to_crm_dev.sql) — "first" is just Spring Data's naming for a
    // single-result email lookup, not a hedge against duplicates.
    Optional<Customer> findFirstByEmailIgnoreCase(String email);
}
