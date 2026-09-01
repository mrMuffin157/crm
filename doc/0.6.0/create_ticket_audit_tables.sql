-- ============================================================================
-- "Use Hibernate Envers to track Ticket update/create events"
--
-- Adds Hibernate Envers auditing to Ticket, TicketComment, and TicketLineItem
-- (@Audited on each entity — see those classes). ddl-auto=none means Hibernate
-- never creates the audit schema itself, same as every other table in this
-- app (see app_user_role's own comment in schema.sql) -- these 4 tables are
-- hand-written here and in src/main/resources/schema.sql (CREATE TABLE IF NOT
-- EXISTS, so a fresh crm_dev/crm_test/prod database picks them up on next
-- boot without a separate manual step).
--
-- revinfo: one row per revision (Hibernate's unit of "a transaction touched
-- an audited entity"). rev is the PK Envers joins every *_aud table back to;
-- revtstamp is Envers' own default column (epoch millis); username is this
-- app's own addition (CrmRevisionEntity/CrmRevisionListener), populated from
-- AuditActorContext -- a ThreadLocal set by the mutating controller endpoint
-- right before it calls into the repository/service layer (see
-- TicketController's create/update/close/fail/comment/patch endpoints and
-- TicketCloseImportController). This works because every one of those
-- endpoints already resolves the caller's Authentication in a reactive
-- flatMap and then runs the actual JPA work inside a single synchronous
-- Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic()) block (the
-- reactive-shell pattern CLAUDE.md documents) -- the ThreadLocal set/cleared
-- around that same callable body never crosses request threads.
--
-- ticket_aud / ticket_comment_aud / ticket_line_item_aud: one row per
-- revision a given ticket/ticket_comment/ticket_line_item row was inserted,
-- updated, or deleted in, carrying every @Audited column's value at that
-- revision (revtype: 0=ADD/1=MOD/2=DEL) -- see application.properties'
-- org.hibernate.envers.audit_table_suffix (explicitly "_aud", not Envers'
-- own default "_AUD") and TicketHistoryService, which diffs consecutive
-- revisions per entity id into the human-readable "who/property/old
-- value/new value/when" rows GET /api/tickets/{id}/history returns.
--
-- ticket_line_item is delete-and-reinsert on every ticket edit
-- (TicketController#persistLineItems), so a ticket with line items will show
-- a DELETE+INSERT pair in its history on every save, even when a given line
-- item's own fields didn't change -- that's an accurate reflection of what
-- actually happens in the table, not a display artifact.
-- ============================================================================

CREATE TABLE IF NOT EXISTS revinfo (
    rev INT NOT NULL AUTO_INCREMENT,
    revtstamp BIGINT,
    username VARCHAR(255),
    PRIMARY KEY (rev)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ticket_aud (
    id BIGINT NOT NULL,
    rev INT NOT NULL,
    revtype TINYINT,
    legacy_id BIGINT,
    source_system VARCHAR(50),
    action INT,
    customer_id VARCHAR(255),
    customer_name VARCHAR(255),
    date_created DATETIME,
    description LONGTEXT,
    email VARCHAR(255),
    handler_id BIGINT,
    last_updated DATETIME,
    status INT,
    customer_address_id VARCHAR(255),
    order_number VARCHAR(255),
    reason INT,
    refund_type INT,
    replacement_order_number VARCHAR(255),
    tracking_number VARCHAR(255),
    starred BIT(1),
    search_text LONGTEXT,
    out_of_warrant BIT(1),
    PRIMARY KEY (id, rev),
    KEY idx_ticket_aud_id (id),
    CONSTRAINT fk_ticket_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ticket_comment_aud (
    id BIGINT NOT NULL,
    rev INT NOT NULL,
    revtype TINYINT,
    source_system VARCHAR(50),
    ticket_id BIGINT,
    comment LONGTEXT,
    created_by_id BIGINT,
    date_created DATETIME,
    last_updated DATETIME,
    PRIMARY KEY (id, rev),
    KEY idx_ticket_comment_aud_id (id),
    CONSTRAINT fk_ticket_comment_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ticket_line_item_aud (
    id BIGINT NOT NULL,
    rev INT NOT NULL,
    revtype TINYINT,
    source_system VARCHAR(50),
    ticket_id BIGINT,
    sku VARCHAR(255),
    channelsku VARCHAR(255),
    name VARCHAR(255),
    quantity DOUBLE,
    refund_amount DOUBLE,
    date_created DATETIME,
    last_updated DATETIME,
    PRIMARY KEY (id, rev),
    KEY idx_ticket_line_item_aud_id (id),
    CONSTRAINT fk_ticket_line_item_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
) ENGINE=InnoDB;
