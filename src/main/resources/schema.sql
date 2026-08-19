-- This app's own table (see AppUserRole.java for why role assignment lives separately from
-- the real "s2user" table, which this app authenticates against but doesn't own). Runs via
-- spring.sql.init.mode=always in application.properties — ddl-auto=none there deliberately
-- never touches the real legacy-app-owned tables, so this is the one table Hibernate doesn't create.
CREATE TABLE IF NOT EXISTS app_user_role (
    s2user_id BIGINT PRIMARY KEY,
    role VARCHAR(20) NOT NULL,
    date_created DATETIME NOT NULL
);

-- Hibernate Envers (ticket update/create history) — same reasoning as app_user_role
-- above: ddl-auto=none never creates these, so they're hand-written here. revinfo is one row
-- per revision (transaction) with "who" (username, populated by CrmRevisionListener from
-- AuditActorContext) and "when" (revtstamp, epoch millis, Envers' own default column); the
-- three *_aud tables are one row per revision a Ticket/TicketComment/TicketLineItem row was
-- touched in, carrying every @Audited column's value at that revision plus revtype
-- (0=INSERT/1=UPDATE/2=DELETE). Column names/types mirror the live ticket/ticket_comment/
-- ticket_line_item tables (doc/0.2.0/migrate_tickets_to_crm_dev.sql, as later widened by
-- doc/0.7.0/convert_ticket_status_action_reason_refund_type_to_string.sql) but nullable
-- throughout —
-- a DELETE revision carries no column data (see hibernate.envers.store_data_at_delete, left at
-- its default false), and see application.properties' org.hibernate.envers.audit_table_suffix
-- for why the suffix here is lowercase "_aud" rather than Envers' own default "_AUD" (MySQL
-- table names can be case-sensitive depending on the server's lower_case_table_names setting).
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
    action VARCHAR(20),
    customer_id VARCHAR(255),
    customer_name VARCHAR(255),
    date_created DATETIME,
    description LONGTEXT,
    email VARCHAR(255),
    handler_id BIGINT,
    last_updated DATETIME,
    status VARCHAR(20),
    customer_address_id VARCHAR(255),
    order_number VARCHAR(255),
    reason VARCHAR(30),
    refund_type VARCHAR(20),
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

-- Which ticket channels a MEMBER may see/create tickets for — see
-- doc/0.6.0/create_app_user_channel.sql and ChannelAccessService for the full design. No rows
-- for a given s2user_id means no channel access at all, not "every channel".
CREATE TABLE IF NOT EXISTS app_user_channel (
    s2user_id BIGINT NOT NULL,
    source_system VARCHAR(32) NOT NULL,
    PRIMARY KEY (s2user_id, source_system)
);
