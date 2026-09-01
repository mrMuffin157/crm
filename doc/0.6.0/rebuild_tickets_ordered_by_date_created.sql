-- ============================================================================
-- "New Ticket Id Rebuild"
--
-- Problem: doc/0.2.0/migrate_tickets_to_crm_dev.sql inserts crm_dev.ticket in
-- 4 separate, sequential INSERT INTO ... SELECT statements, one per legacy
-- storefront DB (store1_dev, store3_dev, store4_dev, store2_dev), and none
-- of those 4 SELECTs has an ORDER BY. Since ticket.id is BIGINT
-- AUTO_INCREMENT, ids get handed out in per-source BLOCKS -- every
-- store1_dev ticket gets an id before any store3_dev ticket does, regardless
-- of which one was actually created first. So MAX(ticket.id) today reflects
-- "whichever source ran last", not "the most recently created ticket" --
-- which breaks the product requirement that sorting the ticket list by id
-- desc should show the newest ticket first.
--
-- Fix: this script performs the exact same logical import as doc/0.2.0's
-- script (same column mapping, same crm_dev.customer/address/s2user lookups),
-- but merges all 4 sources with UNION ALL and ORDERs BY date_created BEFORE
-- the single INSERT INTO crm_dev.ticket -- so AUTO_INCREMENT hands out ids in
-- true chronological order across all 4 storefronts in one pass. This is a
-- NEW file, not an edit to doc/0.2.0/migrate_tickets_to_crm_dev.sql -- that
-- file is a historical record of what actually ran against production and
-- must not be rewritten after the fact.
--
-- Scope: only rebuilds crm_dev.ticket / ticket_comment / ticket_line_item /
-- ticket_report. It assumes crm_dev.customer, crm_dev.address, and
-- crm_dev.s2user are already populated exactly as doc/0.2.0's script (its
-- sections 1-2) left them -- this script does not touch those 3 tables.
--
-- source_system is written directly in the az-* channel format (az-us /
-- az-au / az-ca / az-deco), matching the mapping in
-- doc/0.5.0/normalize_ticket_source_system.sql and the convention
-- doc/0.6.0/resync_customer_address_from_legacy.sql already follows for new
-- rows -- NOT the raw legacy schema name doc/0.2.0's original script used, so
-- there is no separate normalization pass to run afterward.
--
-- ============================================================================
-- WARNING -- destructive, and a breaking change to every externally-held
-- ticket id
-- ============================================================================
-- This script TRUNCATEs crm_dev.ticket_line_item, crm_dev.ticket_comment,
-- crm_dev.ticket_report, and crm_dev.ticket before reimporting, then
-- reassigns every ticket a brand new id. That means:
--   - Any ticket, comment, or line item created or edited directly in CRM
--     since doc/0.2.0's script ran (i.e. everything with no counterpart in
--     one of the 4 legacy DBs) is PERMANENTLY LOST by this script -- it only
--     knows how to reconstruct rows that still exist in a legacy source DB.
--   - Every existing ticket id changes. Any bookmarked CRM ticket URL, any
--     link a support agent has saved, and any legacy-app ROLE_SERVICE caller
--     that has cached a numeric ticket id (rather than looking it up fresh
--     by legacy id / order number / sales channel each time) breaks.
-- Do NOT run this against crm_dev once real production ticket activity has
-- happened there -- it is only safe against a schema you are willing to wipe
-- and rebuild from the 4 legacy DBs from scratch (e.g. a fresh crm_test/QA
-- setup, or crm_dev itself only before any post-migration CRM-native ticket
-- activity exists). Take a backup of the destination schema's ticket* tables
-- before running this regardless.
--
-- Run the same way as doc/0.2.0/migrate_tickets_to_crm_dev.sql: against the
-- MySQL server that hosts all 5 schemas (db-stage.example.internal), as a user with
-- SELECT on the 4 source schemas and INSERT/TRUNCATE on the destination
-- schema (crm_dev/crm_test -- referred to below as crm_dev; adjust the
-- schema-qualified table names if targeting crm_test instead).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Section 1: wipe the tables this script rebuilds. TRUNCATE refuses to run
-- against crm_dev.ticket while fk_comment_ticket/fk_lineitem_ticket still
-- declare it as their referenced table -- InnoDB checks the constraint
-- declaration itself, not whether the referencing tables currently hold any
-- rows, so truncating children first doesn't avoid it. FOREIGN_KEY_CHECKS is
-- disabled for this section only, then re-enabled before Section 2's inserts
-- (which already run parent-then-child, so the constraints hold naturally
-- from that point on). ticket_report has no real FK to ticket (its ticket_id
-- is a loose VARCHAR, per doc/0.2.0's own note) so it isn't affected either
-- way, but it's still wiped since it's rebuilt from the same 4 sources below.
-- ----------------------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE crm_dev.ticket_line_item;
TRUNCATE TABLE crm_dev.ticket_comment;
TRUNCATE TABLE crm_dev.ticket_report;
TRUNCATE TABLE crm_dev.ticket;
SET FOREIGN_KEY_CHECKS = 1;

-- ----------------------------------------------------------------------------
-- Section 2: single INSERT INTO crm_dev.ticket, sourced from a UNION ALL of
-- all 4 legacy DBs, ORDERed BY date_created (source_system, legacy_id as a
-- deterministic tiebreaker for exact-same-timestamp rows). MySQL preserves
-- the SELECT's row order as AUTO_INCREMENT insertion order for a top-level
-- INSERT ... SELECT ... ORDER BY, which is what actually fixes the id
-- ordering -- everything else about each per-source SELECT (handler lookup
-- by username, customer lookup by id) is unchanged from doc/0.2.0's script.
-- ----------------------------------------------------------------------------
INSERT INTO crm_dev.ticket
(legacy_id, source_system, action, customer_id, customer_name, date_created, description, email, handler_id, last_updated, status, customer_address_id, order_number, reason, refund_type, replacement_order_number, tracking_number, starred, search_text, out_of_warrant)
SELECT legacy_id, source_system, action, customer_id, customer_name, date_created, description, email, handler_id, last_updated, status, customer_address_id, order_number, reason, refund_type, replacement_order_number, tracking_number, starred, search_text, out_of_warrant
FROM (
    SELECT t.id AS legacy_id, 'az-us' AS source_system, t.action, cu.id AS customer_id, t.customer_name, t.date_created, t.description, t.email, nu.id AS handler_id, t.last_updated, t.status, t.customer_address_id, t.order_number, t.reason, t.refund_type, t.replacement_order_number, t.tracking_number, t.starred, t.search_text, t.out_of_warrant
    FROM store1_dev.ticket t
             JOIN store1_dev.s2user ou ON ou.id = t.handler_id
             LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
             LEFT JOIN crm_dev.customer cu ON cu.id = t.customer_id
    UNION ALL
    SELECT t.id, 'az-au', t.action, cu.id, t.customer_name, t.date_created, t.description, t.email, nu.id, t.last_updated, t.status, t.customer_address_id, t.order_number, t.reason, t.refund_type, t.replacement_order_number, t.tracking_number, t.starred, t.search_text, t.out_of_warrant
    FROM store3_dev.ticket t
             JOIN store3_dev.s2user ou ON ou.id = t.handler_id
             LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
             LEFT JOIN crm_dev.customer cu ON cu.id = t.customer_id
    UNION ALL
    SELECT t.id, 'az-ca', t.action, cu.id, t.customer_name, t.date_created, t.description, t.email, nu.id, t.last_updated, t.status, t.customer_address_id, t.order_number, t.reason, t.refund_type, t.replacement_order_number, t.tracking_number, t.starred, t.search_text, t.out_of_warrant
    FROM store4_dev.ticket t
             JOIN store4_dev.s2user ou ON ou.id = t.handler_id
             LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
             LEFT JOIN crm_dev.customer cu ON cu.id = t.customer_id
    UNION ALL
    SELECT t.id, 'az-deco', t.action, cu.id, t.customer_name, t.date_created, t.description, t.email, nu.id, t.last_updated, t.status, t.customer_address_id, t.order_number, t.reason, t.refund_type, t.replacement_order_number, t.tracking_number, t.starred, t.search_text, t.out_of_warrant
    FROM store2_dev.ticket t
             JOIN store2_dev.s2user ou ON ou.id = t.handler_id
             LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
             LEFT JOIN crm_dev.customer cu ON cu.id = t.customer_id
) merged
ORDER BY date_created, source_system, legacy_id;

-- ----------------------------------------------------------------------------
-- Section 3: ticket_comment / ticket_line_item, joined back to the just
-- rebuilt crm_dev.ticket by (source_system, legacy_id) -- same shape as
-- doc/0.2.0's script, since these children's own id order was never the
-- problem this ticket describes; only the parent ticket.id ordering was.
-- ticket_comment.created_by_id needs the same two-hop LEFT JOIN via username
-- that handler_id uses above: the source id is a legacy s2user.id, not a
-- crm_dev.s2user.id, and fk_comment_user references crm_dev.s2user -- passing
-- the raw source id straight through 23000/1452s against that constraint.
-- Both hops stay LEFT JOIN (unlike handler_id's first hop) because
-- created_by_id is nullable: a comment with no author, or an author this
-- join can't resolve, must still load, just with created_by_id ending up
-- NULL, exactly as doc/0.2.0's script does it.
-- ----------------------------------------------------------------------------
INSERT INTO crm_dev.ticket_comment
(legacy_id, source_system, comment, created_by_id, date_created, last_updated, ticket_id)
SELECT tc.id, 'az-us', tc.comment, nu.id, tc.date_created, tc.last_updated, nt.id
FROM store1_dev.ticket_comment tc
         JOIN crm_dev.ticket nt ON nt.source_system = 'az-us' AND nt.legacy_id = tc.ticket_id
         LEFT JOIN store1_dev.s2user ou ON ou.id = tc.created_by_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username;

INSERT INTO crm_dev.ticket_comment
(legacy_id, source_system, comment, created_by_id, date_created, last_updated, ticket_id)
SELECT tc.id, 'az-au', tc.comment, nu.id, tc.date_created, tc.last_updated, nt.id
FROM store3_dev.ticket_comment tc
         JOIN crm_dev.ticket nt ON nt.source_system = 'az-au' AND nt.legacy_id = tc.ticket_id
         LEFT JOIN store3_dev.s2user ou ON ou.id = tc.created_by_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username;

INSERT INTO crm_dev.ticket_comment
(legacy_id, source_system, comment, created_by_id, date_created, last_updated, ticket_id)
SELECT tc.id, 'az-ca', tc.comment, nu.id, tc.date_created, tc.last_updated, nt.id
FROM store4_dev.ticket_comment tc
         JOIN crm_dev.ticket nt ON nt.source_system = 'az-ca' AND nt.legacy_id = tc.ticket_id
         LEFT JOIN store4_dev.s2user ou ON ou.id = tc.created_by_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username;

INSERT INTO crm_dev.ticket_comment
(legacy_id, source_system, comment, created_by_id, date_created, last_updated, ticket_id)
SELECT tc.id, 'az-deco', tc.comment, nu.id, tc.date_created, tc.last_updated, nt.id
FROM store2_dev.ticket_comment tc
         JOIN crm_dev.ticket nt ON nt.source_system = 'az-deco' AND nt.legacy_id = tc.ticket_id
         LEFT JOIN store2_dev.s2user ou ON ou.id = tc.created_by_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username;

INSERT INTO crm_dev.ticket_line_item
(legacy_id, source_system, channelsku, date_created, last_updated, name, quantity, refund_amount, sku, ticket_id)
SELECT tl.id, 'az-us', tl.channelsku, tl.date_created, tl.last_updated, tl.name, tl.quantity, tl.refund_amount, tl.sku, nt.id
FROM store1_dev.ticket_line_item tl
         JOIN crm_dev.ticket nt ON nt.source_system = 'az-us' AND nt.legacy_id = tl.ticket_id;

INSERT INTO crm_dev.ticket_line_item
(legacy_id, source_system, channelsku, date_created, last_updated, name, quantity, refund_amount, sku, ticket_id)
SELECT tl.id, 'az-au', tl.channelsku, tl.date_created, tl.last_updated, tl.name, tl.quantity, tl.refund_amount, tl.sku, nt.id
FROM store3_dev.ticket_line_item tl
         JOIN crm_dev.ticket nt ON nt.source_system = 'az-au' AND nt.legacy_id = tl.ticket_id;

INSERT INTO crm_dev.ticket_line_item
(legacy_id, source_system, channelsku, date_created, last_updated, name, quantity, refund_amount, sku, ticket_id)
SELECT tl.id, 'az-ca', tl.channelsku, tl.date_created, tl.last_updated, tl.name, tl.quantity, tl.refund_amount, tl.sku, nt.id
FROM store4_dev.ticket_line_item tl
         JOIN crm_dev.ticket nt ON nt.source_system = 'az-ca' AND nt.legacy_id = tl.ticket_id;

INSERT INTO crm_dev.ticket_line_item
(legacy_id, source_system, channelsku, date_created, last_updated, name, quantity, refund_amount, sku, ticket_id)
SELECT tl.id, 'az-deco', tl.channelsku, tl.date_created, tl.last_updated, tl.name, tl.quantity, tl.refund_amount, tl.sku, nt.id
FROM store2_dev.ticket_line_item tl
         JOIN crm_dev.ticket nt ON nt.source_system = 'az-deco' AND nt.legacy_id = tl.ticket_id;

-- ----------------------------------------------------------------------------
-- Section 4: ticket_report -- ticket_id here is a loose VARCHAR, never a real
-- FK to crm_dev.ticket (see doc/0.2.0's own note on this), so it carries no
-- ordering concern and is rebuilt per-source exactly as doc/0.2.0 did it.
-- ----------------------------------------------------------------------------
INSERT INTO crm_dev.ticket_report
(legacy_id, source_system, phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource)
SELECT id, 'az-us', phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource
FROM store1_dev.ticket_report;

INSERT INTO crm_dev.ticket_report
(legacy_id, source_system, phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource)
SELECT id, 'az-au', phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource
FROM store3_dev.ticket_report;

INSERT INTO crm_dev.ticket_report
(legacy_id, source_system, phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource)
SELECT id, 'az-ca', phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource
FROM store4_dev.ticket_report;

INSERT INTO crm_dev.ticket_report
(legacy_id, source_system, phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource)
SELECT id, 'az-deco', phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource
FROM store2_dev.ticket_report;

-- ----------------------------------------------------------------------------
-- Section 5: verification -- confirm the ordering fix actually took, and
-- that no rows were lost relative to the 4 sources.
-- ----------------------------------------------------------------------------

-- 5a. Row counts must match the 4 sources' combined totals.
SELECT (SELECT COUNT(*) FROM crm_dev.ticket) AS crm_dev_ticket_count,
       (SELECT COUNT(*) FROM store1_dev.ticket) + (SELECT COUNT(*) FROM store3_dev.ticket)
           + (SELECT COUNT(*) FROM store4_dev.ticket) + (SELECT COUNT(*) FROM store2_dev.ticket) AS source_total;

-- 5b. The core fix: id order must now match date_created order. This should
-- return 0 rows -- any row returned is a ticket whose id is smaller than an
-- earlier-dated ticket's id (i.e. still out of order).
SELECT a.id AS earlier_id, a.date_created AS earlier_date, b.id AS later_id, b.date_created AS later_date
FROM crm_dev.ticket a
         JOIN crm_dev.ticket b ON a.date_created > b.date_created AND a.id < b.id
LIMIT 20;

-- 5c. The newest ticket by date_created must be the max id -- directly
-- confirms the ticket's own acceptance test ("Ticket index defaults to id
-- desc, which shows the most recently processed ticket first").
SELECT (SELECT id FROM crm_dev.ticket ORDER BY date_created DESC, id DESC LIMIT 1) AS newest_by_date_id,
       (SELECT MAX(id) FROM crm_dev.ticket) AS max_id;
