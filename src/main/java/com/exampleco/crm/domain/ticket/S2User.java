package com.exampleco.crm.domain.ticket;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

// Read-only, minimal mapping over the real s2user (Spring Security) table. The real table also
// has a password column; never add it (or any other credential/account-state field) here, since
// this entity's only job is resolving handler_id/created_by_id -> a display name for the ticket
// list and comments, and (email/name, added for revised §2) the {username, email,
// name} shape the M2M API's include=handler must return — id is deliberately never part of that
// shape (see TicketController#enrich and Ticket#handler): crm.s2user.id has no meaning to
// legacy-app, which only ever identifies a person by username.
@Entity
@Table(name = "s2user")
@Getter
@Setter
public class S2User {

    @Id
    private Long id;

    private String username;

    private String email;

    private String name;
}
