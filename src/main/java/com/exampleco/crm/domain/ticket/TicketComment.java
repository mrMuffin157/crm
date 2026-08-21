package com.exampleco.crm.domain.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.Audited;

import java.time.LocalDateTime;

// Read-write now (was read-only): still backs the ticket list's "Comments" column/detail
// view, but TicketController#addComment also writes new rows here (Close and standalone
// Add Comment).
// @Audited: tracked into ticket_comment_aud, correlated back to its ticket via
// ticketId — see TicketHistoryService.
@Entity
@Table(name = "ticket_comment")
@Getter
@Setter
@Audited
public class TicketComment {

    @Id
    private Long id;

    // NOT NULL on the real table with no DB-side default — the brand/tenant tag carried over
    // from the 4-storefront migration (see doc/0.2.0/migrate_tickets_to_crm_dev.sql). Rows
    // this app creates itself (as opposed to migrated ones) are tagged "crm" — see
    // TicketController#addComment.
    @Column(name = "source_system")
    private String sourceSystem;

    @Column(name = "ticket_id")
    private Long ticketId;

    private String comment;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "date_created")
    private LocalDateTime dateCreated;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;
}
