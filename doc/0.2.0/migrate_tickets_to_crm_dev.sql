-- ============================================================================
-- Migration: combine ticket, ticket_comment, ticket_line_item, ticket_report
-- (plus the tables they reference: customer, address, s2user)
-- from store1_dev, store3_dev, store4_dev, store2_dev into crm_dev.
--
-- NOTE: this script is written to run against the REAL data, not just the
-- stage sandbox it was developed against. Stage's `customer`/`address`
-- tables are not representative (each source DB only has 1 placeholder row
-- there, because the real customer table was never copied down for stage --
-- too large). The customer-merge logic below does NOT depend on anything
-- about that stage-specific shape; it works off actual email/ticket
-- references and will behave correctly against real, populated data.
--
-- Run this against the MySQL server as a user with SELECT on the 4 source
-- schemas and CREATE/INSERT on crm_dev (all 5 schemas need to live on the
-- same server for the cross-database queries below to work, with grants on
-- all of them for the connecting user).
--
-- AMENDMENT: this version drops both
-- customer_legacy_map and s2user_legacy_map entirely, replacing every lookup
-- through them with a direct join instead -- neither table is referenced
-- anywhere in CRM's application code (only ever used within this script), and
-- keeping them serves no purpose the direct join below doesn't already serve
-- more simply. See "customer -- ids are 1:1, nothing to remap" and
-- "s2user -- consolidate by username, mint new ids, remap the FKs" below for
-- the reasoning behind each. This has NOT been run against a real database as
-- of this edit -- rehearse on stage before trusting it against production.
--
-- Design notes:
--   - ticket, ticket_comment, ticket_line_item, ticket_report all use plain
--     auto_increment ids that collide across the 4 source DBs (e.g.
--     ticket.id=1 means a different ticket in each system). These get fresh
--     auto_increment ids in crm_dev, with the original id preserved as
--     `legacy_id` alongside `source_system` for traceability and remapping.
--   - Real FK constraints are kept (ticket -> customer/address/s2user,
--     ticket_comment -> ticket/s2user, ticket_line_item -> ticket).
--   - ticket_report.ticket_id was already a loose varchar in every source
--     schema (not a real FK there either), so it's carried over as-is.
--   - customer / address / s2user are migrated selectively, NOT wholesale:
--     only rows actually referenced by that source DB's ticket / ticket_comment
--     rows come across (e.g. an s2user who never handled a ticket and never
--     wrote a ticket_comment in that source DB is left behind; a customer
--     who's never had a ticket is left behind).
--   - `legacy_id` is nullable (not NOT NULL) on ticket/ticket_comment/
--     ticket_line_item/ticket_report. Every row THIS SCRIPT inserts sets it,
--     but once the app starts creating brand-new rows directly in crm_dev
--     (ones that never existed in any of the 4 source DBs), there is no old
--     id to put there -- NOT NULL would reject those inserts outright. The
--     uk_source_legacy unique key still works fine with NULLs: InnoDB treats
--     every NULL as distinct, so many new rows can share source_system with
--     legacy_id = NULL without violating uniqueness.
--   - `source_system` stays NOT NULL on ticket/ticket_comment/ticket_line_item/
--     ticket_report, including for future new rows -- unlike legacy_id, it is
--     NOT a migration artifact, it is the ongoing brand/tenant tag every row
--     needs. There's no sensible default for it (a ticket has to know which
--     brand it belongs to), so the app's write path must always set it
--     explicitly going forward. customer and s2user do NOT have this column
--     at all anymore -- see below, they're identity-merged instead.
--
-- customer AND s2user are PERSON MERGES, not a per-brand copy like the other
-- tables. Both follow the same shape: pick a real-world identity key, merge
-- everyone who shares it into one row. An earlier version of this script
-- pushed the (source_system, legacy_id) -> merged-row mapping into a separate
-- *_legacy_map table for ticket/ticket_comment's FK remapping to join
-- through; per the AMENDMENT above, neither map table exists anymore --
-- ticket/ticket_comment now resolve the merged row directly (customer: the
-- source row's own customer_id, already equal to the merged id -- see below;
-- s2user: a join on username, the merge key itself).
--
-- s2user: merged by USERNAME.
--   Investigation found 43 staff accounts duplicated across 2-4 of the
--   source DBs by matching email, always with matching username and name
--   (0 mismatches) -- confirmed same real person, logging into multiple
--   brand systems. A further check for the reverse case (same username,
--   different email) found 6 more people, confirmed the same way (identical
--   legacy numeric id and name across every DB they appear in) -- most of
--   them simply use a different email domain in one brand's system than in
--   the other three (evidently that brand runs under a different company
--   name). No case of two DIFFERENT real people sharing a username was
--   found -- that's why username, not email, is the identity key here.
--   Per user decision:
--     - Each real person gets exactly ONE row in crm_dev.s2user.
--     - Any legacy account that was ever `enabled` in any source DB makes
--       the merged account `enabled` (having had access to any one brand's
--       system grants access to the unified one).
--     - account_locked / account_expired use the opposite, more conservative
--       rule: if locked/expired in ANY source DB, the merged account is
--       locked/expired too (only 1 person total is affected by each flag;
--       flagged here in case that specific case needs a manual second look).
--     - password / password_expired / email / name / telegram_chat_id /
--       last_show_version / page_render_option are taken from whichever of
--       that person's referenced rows has the latest last_updated ("their
--       most recently active brand account wins" for anything cosmetic).
--       30 of the 43 duplicated people had a DIFFERENT password hash per
--       brand -- per explicit user decision, the most-recently-updated
--       row's password hash is carried over as-is (not a forced reset).
--       9 of the 43 had a DIFFERENT telegram_chat_id per brand -- only one
--       survives the merge, so per-brand notification routing is lost for
--       those 9 unless the app is changed to track that separately.
--     - `version` resets to 0 (it's a Hibernate optimistic-lock counter with
--       no meaning carried over into a brand new row).
--     - date_created = earliest date_created across their rows (true first-
--       seen date); last_updated = latest.
--   crm_dev.s2user.username is UNIQUE (globally, across all merged people).
--
--   s2user -- consolidate by username, mint new ids, remap the FKs (
--   audit follow-up): crm_dev.s2user.id is a fresh AUTO_INCREMENT value, NOT
--   equal to any source DB's original s2user.id -- so ticket.handler_id and
--   ticket_comment.created_by_id cannot just pass the source id through the
--   way customer_id can (see below). Section 3/4 resolve this with a direct
--   two-hop join instead of s2user_legacy_map:
--     JOIN <src>.s2user      ou ON ou.id = old.handler_id
--     JOIN crm_dev.s2user    nu ON nu.username = ou.username
--   ticket.handler_id is NOT NULL on the real table -- section 3 uses LEFT
--   JOIN (never INNER JOIN) for the second hop so a dangling handler shows up
--   as an explicit pre-flight failure instead of silently dropping the whole
--   ticket the way an INNER JOIN's simple non-match would. Run the existing
--   ticket->s2user(handler) orphan check (section 0) first regardless --it
--   catches the source-side half of this same risk (old.handler_id pointing
--   at nothing in <src>.s2user at all). ticket_comment.created_by_id is
--   nullable, so a miss on the second hop there is tolerated silently (the
--   comment just ends up with no resolvable author), matching how a missing
--   created_by_id was already handled before this amendment.
--
-- customer: merged by EMAIL, per explicit user instruction ("if you find the
--   same email across different databases, assume they're the same person").
--   Unlike s2user, this could not be validated against real data when this
--   script was first written: stage's customer table only has one placeholder
--   row per source DB (see NOTE up top). It has SINCE been verified against
--   real production data (2026-08-27): no email appears in more than one
--   brand's ticket-referenced customers, so the email-merge below is a no-op
--   in practice, not just in theory -- every migrated customer keeps its own
--   single original id, never actually combining with another brand's row.
--     - Each distinct email gets exactly ONE row in crm_dev.customer, kept
--       under one of its original Hibernate-UUID ids (whichever referenced
--       row has the latest last_updated) rather than a freshly generated id
--       -- simpler than inventing a new id scheme, and keeps the same id
--       format the rest of the system already expects.
--     - name / consumer_group_icons / is_new / is_review_disabled are taken
--       from that same most-recently-updated row.
--     - date_created = earliest across their rows; last_updated = latest.
--   crm_dev.customer.email is UNIQUE (globally). address is NOT merged this
--   way -- there's no equivalent natural identity key for a physical address
--   the way email/username work for people, so address rows are still just
--   deduped by exact id collision (INSERT IGNORE) and tagged with
--   source_system, same as before.
--
--   customer -- ids are 1:1, nothing to remap: the
--   1:1-in-practice finding above means a source row's own customer_id is
--   ALREADY the merged crm_dev.customer.id -- section 3 uses it directly
--   (LEFT JOIN crm_dev.customer, to keep nulling-out behavior for a dangling
--   customer_id exactly as before) instead of remapping it through
--   customer_legacy_map. This script still keeps the full merge/dedup logic
--   above regardless of the 1:1 finding: crm_dev.customer.email is UNIQUE, so
--   without it, a genuine future duplicate would fail the INSERT outright
--   instead of merging cleanly.
--
-- HOW TO RUN
--   First time, on an empty crm_dev: skip section R, run sections 0 -> 6.
--   Re-running on stage after a previous run: run section R (teardown) to
--   wipe crm_dev back to nothing, then sections 0 -> 6 again.
--   The migration is NOT idempotent without section R -- the CREATE TABLE
--   statements in section 1 fail with "table already exists" if the previous
--   run's tables are still there. That failure is deliberate: it stops you
--   from double-inserting on top of existing data.
--
--   ALWAYS execute this file as ONE UNINTERRUPTED SCRIPT RUN (e.g. DBeaver's
--   "Execute SQL Script", not selecting-and-running one block at a time).
--   Never run section R by itself partway through an existing run and then
--   resume from the middle -- section 1's CREATE TABLE statements must run
--   immediately after R's drops, or a later section's INSERT will fail with
--   "table doesn't exist" against a table R dropped but nothing recreated.
--   R4 SIGNALs a hard
--   error if R3 finds leftover tables, so a broken sequencing like that stops
--   the script instead of silently corrupting the run.
--
-- SECTION R IS DESTRUCTIVE AND IS NOT PART OF THE PRODUCTION MIGRATION.
--   It exists for repeated stage rehearsals. On production this file should
--   be run ONCE, with section R skipped entirely. Read the guard query at
--   the top of section R before running it -- it tells you whether crm_dev
--   contains app-created (post-cutover, live) rows that a teardown would
--   destroy.
-- ============================================================================


-- Required: the CREATE TEMPORARY TABLE statements below are intentionally
-- unqualified (temp tables are session-scoped, not part of any schema's
-- persistent definition), so MySQL needs a default database selected or it
-- errors with "1046 No database selected". Everything else in this file is
-- fully qualified (crm_dev.x / store1_dev.x / ...), so this line only
-- affects where the temp tables land.
USE crm_dev;


-- ############################################################################
-- ## SECTION R -- TEARDOWN / RESET.  *** DESTRUCTIVE -- OPT-IN ONLY ***
-- ##
-- ## Purpose: wipe crm_dev back to empty so the migration can be re-run from
-- ## scratch. Intended for repeated rehearsals on stage.
-- ##
-- ## SKIP THIS SECTION ON PRODUCTION. The production migration runs once, on
-- ## an empty crm_dev, so there is nothing to tear down. After cutover,
-- ## running this would destroy live ticket data.
-- ##
-- ## Scope: every statement below names crm_dev explicitly. Nothing here
-- ## touches store1_dev / store3_dev / store4_dev / store2_dev -- the four
-- ## source databases are only ever READ by this file, never written.
-- ############################################################################

-- R1. GUARD -- run this alone and read it BEFORE the drops below.
--
--     Every row this migration inserts has legacy_id set. A row with
--     legacy_id IS NULL therefore did NOT come from a migration -- it was
--     created by the application, i.e. this crm_dev is LIVE and a teardown
--     would destroy real work.
--
--     app_created_rows = 0        -> safe to tear down and re-run.
--     app_created_rows > 0        -> STOP. Do not run R2. Someone has been
--                                    using this database. Find out who/what
--                                    before destroying anything.
SELECT
    (SELECT COUNT(*) FROM crm_dev.ticket           WHERE legacy_id IS NULL)
        + (SELECT COUNT(*) FROM crm_dev.ticket_comment   WHERE legacy_id IS NULL)
        + (SELECT COUNT(*) FROM crm_dev.ticket_line_item WHERE legacy_id IS NULL)
        + (SELECT COUNT(*) FROM crm_dev.ticket_report    WHERE legacy_id IS NULL)
                                           AS app_created_rows,
    (SELECT COUNT(*) FROM crm_dev.ticket)  AS total_tickets_now,
    (SELECT COUNT(*) FROM crm_dev.s2user)  AS total_staff_now;

-- R2. THE DROPS. Order is reverse-dependency so the real FK constraints are
--     never violated: children before parents. Do not reorder, and do not
--     "fix" a failure here with SET FOREIGN_KEY_CHECKS=0 -- if a drop fails
--     on an FK, something references a table you did not expect, and that is
--     worth understanding rather than suppressing.
--
--     ticket_report is standalone (its ticket_id was never a real FK).
--     ticket_line_item / ticket_comment  -> ticket
--     ticket                             -> customer, address, s2user
--
--     s2user_legacy_map / customer_legacy_map are no longer created by
--     section 1 (see the AMENDMENT near the top of this file) -- these two
--     DROP IF EXISTS lines are purely defensive, in case this stage database
--     still has one left over from an older run of this script, before the
--     amendment. Harmless no-ops otherwise.
DROP TABLE IF EXISTS crm_dev.ticket_report;
DROP TABLE IF EXISTS crm_dev.ticket_line_item;
DROP TABLE IF EXISTS crm_dev.ticket_comment;
DROP TABLE IF EXISTS crm_dev.ticket;
DROP TABLE IF EXISTS crm_dev.s2user_legacy_map;
DROP TABLE IF EXISTS crm_dev.s2user;
DROP TABLE IF EXISTS crm_dev.customer_legacy_map;
DROP TABLE IF EXISTS crm_dev.customer;
DROP TABLE IF EXISTS crm_dev.address;

-- R3. Confirm the 9 tables this migration owns are gone. Should return zero
--     rows; anything listed is a leftover this teardown did not know about.
--     Scoped to exactly the tables R2 drops above -- NOT "every table in
--     crm_dev". crm_dev also holds app_user/app_user_role (the auth
--     allowlist) and sales_order_archive/sales_order_archive_tracking (the
--     Sales Order archive feature), which predate and coexist with this
--     migration and are never touched by Section R; a leftover check against
--     the whole schema would always show those as false "leftovers."
SELECT TABLE_NAME AS leftover_table_in_crm_dev
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'crm_dev'
  AND TABLE_NAME IN (
      'ticket_report', 'ticket_line_item', 'ticket_comment', 'ticket',
      's2user_legacy_map', 's2user', 'customer_legacy_map', 'customer', 'address'
  );

-- R4. HARD GUARD -- do not just eyeball R3's grid. If this file is ever run
--     via "Execute SQL Script" in one uninterrupted pass (the required way to
--     run it -- see HOW TO RUN above), R3 returning non-empty means R2's
--     drops did not fully take (or something else recreated a table in
--     between), and section 1's CREATE TABLE statements below would either
--     fail on "table already exists" or, worse, section 2-6 would run against
--     a stale table that section 1 never touched. This SIGNALs a real error
--     to stop the script cold right here instead of limping into that.
--     Same 9-table scope as R3 -- see that comment for why.
--     (This is also exactly the failure mode if you interrupt a run after
--     Section R and later resume by jumping straight to a middle section
--     without re-running Section 1 first -- don't do that; always run this
--     file as one unbroken pass from Section R (if resetting) through
--     Section 6.)
DROP PROCEDURE IF EXISTS crm_dev.assert_teardown_complete;
DELIMITER $$
CREATE PROCEDURE crm_dev.assert_teardown_complete()
BEGIN
    DECLARE leftover INT;
    SELECT COUNT(*) INTO leftover FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = 'crm_dev'
      AND TABLE_NAME IN (
          'ticket_report', 'ticket_line_item', 'ticket_comment', 'ticket',
          's2user_legacy_map', 's2user', 'customer_legacy_map', 'customer', 'address'
      );
    IF leftover > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Teardown incomplete: leftover migration tables in crm_dev, see R3.';
    END IF;
END $$
DELIMITER ;
CALL crm_dev.assert_teardown_complete();
DROP PROCEDURE crm_dev.assert_teardown_complete;

-- ############################################################################
-- ## END SECTION R
-- ############################################################################


-- ----------------------------------------------------------------------------
-- 0. PRE-FLIGHT INTEGRITY CHECK -- run this FIRST, on its own, and read the
--    result before running anything else in this file.
--
--    Why this exists: the loads below join children to their parents, so any
--    child row whose parent is missing is SILENTLY DROPPED (it cannot be
--    migrated -- crm_dev enforces the FK, so there would be no valid parent
--    to point at). A silent drop is indistinguishable from "Success" in the
--    execution log, so orphans must be found up front, not discovered later
--    by comparing counts.
--
--    Observed on stage: exactly 1 orphan -- store4_dev.ticket_line_item
--    id=4966 points at ticket_id=4441, which does not exist. Note that
--    store4_dev.ticket_line_item DOES have a real FK constraint to ticket,
--    and ids 4438-4444 are otherwise all present, so ticket 4441 was almost
--    certainly deleted with FOREIGN_KEY_CHECKS=0 at some point, leaving the
--    line item stranded. Production may well have more of these.
--
--    Any row returning orphans > 0 will be dropped by this migration. Decide
--    deliberately per row: accept the loss (it is unreachable data anyway --
--    a line item with no ticket is not visible in the app), or repair the
--    source first. Do not just let it slide unnoticed.
-- ----------------------------------------------------------------------------

SELECT 'ticket_line_item->ticket' AS relation, 'store1_dev' AS src, COUNT(*) AS orphans
FROM store1_dev.ticket_line_item c LEFT JOIN store1_dev.ticket p ON p.id=c.ticket_id WHERE p.id IS NULL
UNION ALL SELECT 'ticket_line_item->ticket','store3_dev',COUNT(*) FROM store3_dev.ticket_line_item c LEFT JOIN store3_dev.ticket p ON p.id=c.ticket_id WHERE p.id IS NULL
UNION ALL SELECT 'ticket_line_item->ticket','store4_dev',COUNT(*) FROM store4_dev.ticket_line_item c LEFT JOIN store4_dev.ticket p ON p.id=c.ticket_id WHERE p.id IS NULL
UNION ALL SELECT 'ticket_line_item->ticket','store2_dev',COUNT(*) FROM store2_dev.ticket_line_item c LEFT JOIN store2_dev.ticket p ON p.id=c.ticket_id WHERE p.id IS NULL

UNION ALL SELECT 'ticket_comment->ticket','store1_dev',COUNT(*) FROM store1_dev.ticket_comment c LEFT JOIN store1_dev.ticket p ON p.id=c.ticket_id WHERE p.id IS NULL
UNION ALL SELECT 'ticket_comment->ticket','store3_dev',COUNT(*) FROM store3_dev.ticket_comment c LEFT JOIN store3_dev.ticket p ON p.id=c.ticket_id WHERE p.id IS NULL
UNION ALL SELECT 'ticket_comment->ticket','store4_dev',COUNT(*) FROM store4_dev.ticket_comment c LEFT JOIN store4_dev.ticket p ON p.id=c.ticket_id WHERE p.id IS NULL
UNION ALL SELECT 'ticket_comment->ticket','store2_dev',COUNT(*) FROM store2_dev.ticket_comment c LEFT JOIN store2_dev.ticket p ON p.id=c.ticket_id WHERE p.id IS NULL

-- handler_id is NOT NULL in the source schema and the ticket load uses an
-- INNER JOIN on it, so an orphan here would drop an entire TICKET, not just
-- a child row. Most important line in this check.
UNION ALL SELECT 'ticket->s2user(handler)','store1_dev',COUNT(*) FROM store1_dev.ticket c LEFT JOIN store1_dev.s2user p ON p.id=c.handler_id WHERE p.id IS NULL
UNION ALL SELECT 'ticket->s2user(handler)','store3_dev',COUNT(*) FROM store3_dev.ticket c LEFT JOIN store3_dev.s2user p ON p.id=c.handler_id WHERE p.id IS NULL
UNION ALL SELECT 'ticket->s2user(handler)','store4_dev',COUNT(*) FROM store4_dev.ticket c LEFT JOIN store4_dev.s2user p ON p.id=c.handler_id WHERE p.id IS NULL
UNION ALL SELECT 'ticket->s2user(handler)','store2_dev',COUNT(*) FROM store2_dev.ticket c LEFT JOIN store2_dev.s2user p ON p.id=c.handler_id WHERE p.id IS NULL

-- customer_id / customer_address_id / created_by_id are nullable and loaded
-- via LEFT JOIN, so an orphan here does NOT drop the row -- it just lands as
-- NULL in crm_dev. Still worth knowing about: it means the app will show a
-- ticket with no customer/address, or a comment with no author.
UNION ALL SELECT 'ticket->customer (nulls out)','store1_dev',COUNT(*) FROM store1_dev.ticket c LEFT JOIN store1_dev.customer p ON p.id=c.customer_id WHERE c.customer_id IS NOT NULL AND p.id IS NULL
UNION ALL SELECT 'ticket->customer (nulls out)','store3_dev',COUNT(*) FROM store3_dev.ticket c LEFT JOIN store3_dev.customer p ON p.id=c.customer_id WHERE c.customer_id IS NOT NULL AND p.id IS NULL
UNION ALL SELECT 'ticket->customer (nulls out)','store4_dev',COUNT(*) FROM store4_dev.ticket c LEFT JOIN store4_dev.customer p ON p.id=c.customer_id WHERE c.customer_id IS NOT NULL AND p.id IS NULL
UNION ALL SELECT 'ticket->customer (nulls out)','store2_dev',COUNT(*) FROM store2_dev.ticket c LEFT JOIN store2_dev.customer p ON p.id=c.customer_id WHERE c.customer_id IS NOT NULL AND p.id IS NULL

UNION ALL SELECT 'ticket->address (nulls out)','store1_dev',COUNT(*) FROM store1_dev.ticket c LEFT JOIN store1_dev.address p ON p.id=c.customer_address_id WHERE c.customer_address_id IS NOT NULL AND p.id IS NULL
UNION ALL SELECT 'ticket->address (nulls out)','store3_dev',COUNT(*) FROM store3_dev.ticket c LEFT JOIN store3_dev.address p ON p.id=c.customer_address_id WHERE c.customer_address_id IS NOT NULL AND p.id IS NULL
UNION ALL SELECT 'ticket->address (nulls out)','store4_dev',COUNT(*) FROM store4_dev.ticket c LEFT JOIN store4_dev.address p ON p.id=c.customer_address_id WHERE c.customer_address_id IS NOT NULL AND p.id IS NULL
UNION ALL SELECT 'ticket->address (nulls out)','store2_dev',COUNT(*) FROM store2_dev.ticket c LEFT JOIN store2_dev.address p ON p.id=c.customer_address_id WHERE c.customer_address_id IS NOT NULL AND p.id IS NULL

UNION ALL SELECT 'ticket_comment->s2user (nulls out)','store1_dev',COUNT(*) FROM store1_dev.ticket_comment c LEFT JOIN store1_dev.s2user p ON p.id=c.created_by_id WHERE c.created_by_id IS NOT NULL AND p.id IS NULL
UNION ALL SELECT 'ticket_comment->s2user (nulls out)','store3_dev',COUNT(*) FROM store3_dev.ticket_comment c LEFT JOIN store3_dev.s2user p ON p.id=c.created_by_id WHERE c.created_by_id IS NOT NULL AND p.id IS NULL
UNION ALL SELECT 'ticket_comment->s2user (nulls out)','store4_dev',COUNT(*) FROM store4_dev.ticket_comment c LEFT JOIN store4_dev.s2user p ON p.id=c.created_by_id WHERE c.created_by_id IS NOT NULL AND p.id IS NULL
UNION ALL SELECT 'ticket_comment->s2user (nulls out)','store2_dev',COUNT(*) FROM store2_dev.ticket_comment c LEFT JOIN store2_dev.s2user p ON p.id=c.created_by_id WHERE c.created_by_id IS NOT NULL AND p.id IS NULL
ORDER BY orphans DESC, relation, src;


-- ----------------------------------------------------------------------------
-- 1. Schema: parent tables first, then ticket, then its children
-- ----------------------------------------------------------------------------

-- One row per real customer, merged across brands by email -- see notes
-- above. source_system/legacy_id do NOT live here; a source row's own
-- customer_id already equals this row's id (see "customer -- ids are 1:1"
-- above), so there is no separate legacy-map table to remap through.
CREATE TABLE crm_dev.customer (
                                  id VARCHAR(255) NOT NULL,
                                  consumer_group_icons VARCHAR(255),
                                  date_created DATETIME NOT NULL,
                                  email VARCHAR(255) NOT NULL,
                                  is_new BIT(1) NOT NULL,
                                  is_review_disabled BIT(1) NOT NULL,
                                  last_updated DATETIME NOT NULL,
                                  name VARCHAR(255),
                                  PRIMARY KEY (id),
                                  UNIQUE KEY uk_email (email),
                                  KEY idx_name (name)
) ENGINE=InnoDB;

-- address is NOT identity-merged (no natural key like email/username for a
-- physical address) -- still one row per source record, deduped only on
-- exact id collision, tagged with source_system.
CREATE TABLE crm_dev.address (
                                 id VARCHAR(255) NOT NULL,
                                 source_system VARCHAR(50) NOT NULL,
                                 address_line1 VARCHAR(255),
                                 address_line2 VARCHAR(255),
                                 address_line3 VARCHAR(255),
                                 city VARCHAR(255),
                                 company_name VARCHAR(255),
                                 country_code VARCHAR(255),
                                 date_created DATETIME NOT NULL,
                                 last_updated DATETIME NOT NULL,
                                 name VARCHAR(255),
                                 phone VARCHAR(255),
                                 state VARCHAR(255),
                                 zip_code VARCHAR(255),
                                 PRIMARY KEY (id),
                                 KEY idx_city (city),
                                 KEY idx_name (name),
                                 KEY idx_state (state),
                                 KEY idx_zip_code (zip_code),
                                 KEY idx_source_system (source_system)
) ENGINE=InnoDB;

-- One row per real PERSON (merged across brands by username -- see notes
-- above), not one row per source DB. legacy_id/source_system do NOT live
-- here anymore; unlike customer above, this row's id is a brand new
-- AUTO_INCREMENT value that does NOT equal any source row's original id, so
-- ticket/ticket_comment resolve it via a direct username join instead of a
-- legacy-map table -- see "s2user -- consolidate by username" above.
CREATE TABLE crm_dev.s2user (
                                id BIGINT NOT NULL AUTO_INCREMENT,
                                username VARCHAR(255) NOT NULL,
                                email VARCHAR(255),
                                name VARCHAR(255),
                                version BIGINT NOT NULL DEFAULT 0,
                                account_expired BIT(1) NOT NULL,
                                account_locked BIT(1) NOT NULL,
                                enabled BIT(1) NOT NULL,
                                password VARCHAR(255) NOT NULL,
                                password_expired BIT(1) NOT NULL,
                                telegram_chat_id BIGINT NOT NULL,
                                last_show_version VARCHAR(255),
                                last_updated DATETIME NOT NULL,
                                date_created DATETIME NOT NULL,
                                page_render_option VARCHAR(255),
                                PRIMARY KEY (id),
                                UNIQUE KEY uk_username (username),
                                KEY idx_email (email)
) ENGINE=InnoDB;

CREATE TABLE crm_dev.ticket (
                                id BIGINT NOT NULL AUTO_INCREMENT,
                                legacy_id BIGINT,
                                source_system VARCHAR(50) NOT NULL,
                                action INT,
                                customer_id VARCHAR(255),
                                customer_name VARCHAR(255),
                                date_created DATETIME NOT NULL,
                                description LONGTEXT NOT NULL,
                                email VARCHAR(255),
                                handler_id BIGINT NOT NULL,
                                last_updated DATETIME NOT NULL,
                                status INT NOT NULL,
                                customer_address_id VARCHAR(255),
                                order_number VARCHAR(255),
                                reason INT NOT NULL,
                                refund_type INT,
                                replacement_order_number VARCHAR(255),
                                tracking_number VARCHAR(255),
                                starred BIT(1) NOT NULL,
                                search_text LONGTEXT,
                                out_of_warrant BIT(1) NOT NULL DEFAULT b'0',
                                PRIMARY KEY (id),
                                UNIQUE KEY uk_source_legacy (source_system, legacy_id),
                                KEY idx_customer_id (customer_id),
                                KEY idx_customer_address_id (customer_address_id),
                                KEY idx_handler_id (handler_id),
                                FULLTEXT KEY idx_fulltext_search_text (search_text),
                                CONSTRAINT fk_ticket_customer FOREIGN KEY (customer_id) REFERENCES crm_dev.customer (id),
                                CONSTRAINT fk_ticket_address FOREIGN KEY (customer_address_id) REFERENCES crm_dev.address (id),
                                CONSTRAINT fk_ticket_handler FOREIGN KEY (handler_id) REFERENCES crm_dev.s2user (id)
) ENGINE=InnoDB;

CREATE TABLE crm_dev.ticket_comment (
                                        id BIGINT NOT NULL AUTO_INCREMENT,
                                        legacy_id BIGINT,
                                        source_system VARCHAR(50) NOT NULL,
                                        comment LONGTEXT NOT NULL,
                                        created_by_id BIGINT,
                                        date_created DATETIME NOT NULL,
                                        last_updated DATETIME NOT NULL,
                                        ticket_id BIGINT NOT NULL,
                                        PRIMARY KEY (id),
                                        UNIQUE KEY uk_source_legacy (source_system, legacy_id),
                                        KEY idx_ticket_id (ticket_id),
                                        KEY idx_created_by_id (created_by_id),
                                        CONSTRAINT fk_comment_ticket FOREIGN KEY (ticket_id) REFERENCES crm_dev.ticket (id),
                                        CONSTRAINT fk_comment_user FOREIGN KEY (created_by_id) REFERENCES crm_dev.s2user (id)
) ENGINE=InnoDB;

CREATE TABLE crm_dev.ticket_line_item (
                                          id BIGINT NOT NULL AUTO_INCREMENT,
                                          legacy_id BIGINT,
                                          source_system VARCHAR(50) NOT NULL,
                                          channelsku VARCHAR(255),
                                          date_created DATETIME NOT NULL,
                                          last_updated DATETIME NOT NULL,
                                          name VARCHAR(255),
                                          quantity DOUBLE NOT NULL,
                                          refund_amount DOUBLE NOT NULL,
                                          sku VARCHAR(255),
                                          ticket_id BIGINT NOT NULL,
                                          PRIMARY KEY (id),
                                          UNIQUE KEY uk_source_legacy (source_system, legacy_id),
                                          KEY idx_ticket_id (ticket_id),
                                          CONSTRAINT fk_lineitem_ticket FOREIGN KEY (ticket_id) REFERENCES crm_dev.ticket (id)
) ENGINE=InnoDB;

CREATE TABLE crm_dev.ticket_report (
                                       id BIGINT NOT NULL AUTO_INCREMENT,
                                       legacy_id BIGINT,
                                       source_system VARCHAR(50) NOT NULL,
                                       phone VARCHAR(255),
                                       item_name VARCHAR(255),
                                       address_line2 VARCHAR(255),
                                       date_created DATETIME(6) NOT NULL,
                                       is_finish BIT(1) NOT NULL,
                                       last_updated DATETIME(6) NOT NULL,
                                       zip_code VARCHAR(255) NOT NULL,
                                       buyer_full_name VARCHAR(255),
                                       state VARCHAR(255),
                                       order_date DATETIME(6) NOT NULL,
                                       sku VARCHAR(255) NOT NULL,
                                       report_date DATETIME(6) NOT NULL,
                                       sales_channel INT NOT NULL,
                                       country_code VARCHAR(255),
                                       buyer_email VARCHAR(255),
                                       ticket_id VARCHAR(255),
                                       quantity INT NOT NULL,
                                       city VARCHAR(255),
                                       address_line1 VARCHAR(255) NOT NULL,
                                       item_description VARCHAR(255),
                                       address_name VARCHAR(255) NOT NULL,
                                       order_number VARCHAR(255),
                                       order_resource VARCHAR(255) NOT NULL,
                                       PRIMARY KEY (id),
                                       UNIQUE KEY uk_source_legacy (source_system, legacy_id),
                                       KEY idx_sales_channel (sales_channel)
) ENGINE=InnoDB;


-- ----------------------------------------------------------------------------
-- 2a. Load customer -- merge by email across all 4 sources (see design notes
--     above), keeping only rows referenced as a ticket customer_id in their
--     own source DB. Same staging-table pattern as the s2user merge below.
-- ----------------------------------------------------------------------------

DROP TEMPORARY TABLE IF EXISTS stg_customer_referenced;
CREATE TEMPORARY TABLE stg_customer_referenced AS
SELECT c.id AS legacy_id, 'store1_dev' AS source_system, c.email, c.name, c.consumer_group_icons,
       c.is_new, c.is_review_disabled, c.date_created, c.last_updated
FROM store1_dev.customer c
WHERE c.id IN (SELECT customer_id FROM store1_dev.ticket WHERE customer_id IS NOT NULL)
UNION ALL
SELECT c.id, 'store3_dev', c.email, c.name, c.consumer_group_icons,
       c.is_new, c.is_review_disabled, c.date_created, c.last_updated
FROM store3_dev.customer c
WHERE c.id IN (SELECT customer_id FROM store3_dev.ticket WHERE customer_id IS NOT NULL)
UNION ALL
SELECT c.id, 'store4_dev', c.email, c.name, c.consumer_group_icons,
       c.is_new, c.is_review_disabled, c.date_created, c.last_updated
FROM store4_dev.customer c
WHERE c.id IN (SELECT customer_id FROM store4_dev.ticket WHERE customer_id IS NOT NULL)
UNION ALL
SELECT c.id, 'store2_dev', c.email, c.name, c.consumer_group_icons,
       c.is_new, c.is_review_disabled, c.date_created, c.last_updated
FROM store2_dev.customer c
WHERE c.id IN (SELECT customer_id FROM store2_dev.ticket WHERE customer_id IS NOT NULL);

-- Canonical (most-recently-updated) row per email: source of the kept id,
-- name, consumer_group_icons, is_new, is_review_disabled in the merged row.
DROP TEMPORARY TABLE IF EXISTS stg_customer_canonical;
CREATE TEMPORARY TABLE stg_customer_canonical AS
SELECT email, legacy_id AS canonical_id, name, consumer_group_icons, is_new, is_review_disabled
FROM (
         SELECT r.*, ROW_NUMBER() OVER (PARTITION BY email ORDER BY last_updated DESC, source_system) AS rn
         FROM stg_customer_referenced r
     ) ranked
WHERE rn = 1;

INSERT INTO crm_dev.customer
(id, email, name, consumer_group_icons, is_new, is_review_disabled, date_created, last_updated)
SELECT
    c.canonical_id,
    agg.email,
    c.name,
    c.consumer_group_icons,
    c.is_new,
    c.is_review_disabled,
    agg.min_created,
    agg.max_updated
FROM (
         SELECT email, MIN(date_created) AS min_created, MAX(last_updated) AS max_updated
         FROM stg_customer_referenced
         GROUP BY email
     ) agg
         JOIN stg_customer_canonical c ON c.email = agg.email;

-- VERIFY the "ids are 1:1, nothing to remap" assumption holds for THIS run's
-- actual data before section 3 relies on it (see "customer -- ids are 1:1"
-- above) -- confirmed once against production on 2026-08-27, but re-checking
-- here rather than trusting that note blindly protects against this ever
-- running against a dataset where it's no longer true (e.g. new duplicate
-- emails created between then and whenever this actually runs). Every row
-- here means: this legacy customer record's OWN id differs from the id its
-- email actually got merged/kept under -- i.e. section 3's direct
-- `t.customer_id = cu.id` passthrough would silently drop that legacy row's
-- tickets' customer link instead of correctly pointing at the merged row.
-- MUST be empty. If not, this script's approach for customer needs revisiting
-- (e.g. reintroducing a legacy-map-shaped join) before proceeding.
SELECT r.source_system, r.legacy_id AS this_records_own_id, cu.id AS actually_merged_under
FROM stg_customer_referenced r
         JOIN crm_dev.customer cu ON cu.email = r.email
WHERE r.legacy_id <> cu.id;

DROP TEMPORARY TABLE IF EXISTS stg_customer_canonical;
DROP TEMPORARY TABLE IF EXISTS stg_customer_referenced;


-- ----------------------------------------------------------------------------
-- 2b. Load address from all 4 sources -- ONLY rows referenced by that source
--     DB's own ticket rows (no identity merge -- see design notes above).
-- ----------------------------------------------------------------------------

INSERT IGNORE INTO crm_dev.address
  (id, source_system, address_line1, address_line2, address_line3, city, company_name, country_code, date_created, last_updated, name, phone, state, zip_code)
SELECT id, 'store1_dev', address_line1, address_line2, address_line3, city, company_name, country_code, date_created, last_updated, name, phone, state, zip_code
FROM store1_dev.address
WHERE id IN (SELECT customer_address_id FROM store1_dev.ticket WHERE customer_address_id IS NOT NULL);

INSERT IGNORE INTO crm_dev.address
  (id, source_system, address_line1, address_line2, address_line3, city, company_name, country_code, date_created, last_updated, name, phone, state, zip_code)
SELECT id, 'store3_dev', address_line1, address_line2, address_line3, city, company_name, country_code, date_created, last_updated, name, phone, state, zip_code
FROM store3_dev.address
WHERE id IN (SELECT customer_address_id FROM store3_dev.ticket WHERE customer_address_id IS NOT NULL);

INSERT IGNORE INTO crm_dev.address
  (id, source_system, address_line1, address_line2, address_line3, city, company_name, country_code, date_created, last_updated, name, phone, state, zip_code)
SELECT id, 'store4_dev', address_line1, address_line2, address_line3, city, company_name, country_code, date_created, last_updated, name, phone, state, zip_code
FROM store4_dev.address
WHERE id IN (SELECT customer_address_id FROM store4_dev.ticket WHERE customer_address_id IS NOT NULL);

INSERT IGNORE INTO crm_dev.address
  (id, source_system, address_line1, address_line2, address_line3, city, company_name, country_code, date_created, last_updated, name, phone, state, zip_code)
SELECT id, 'store2_dev', address_line1, address_line2, address_line3, city, company_name, country_code, date_created, last_updated, name, phone, state, zip_code
FROM store2_dev.address
WHERE id IN (SELECT customer_address_id FROM store2_dev.ticket WHERE customer_address_id IS NOT NULL);


-- ----------------------------------------------------------------------------
-- 2c. Load s2user -- merge by username across all 4 sources (see design
--     notes above), keeping only rows referenced as a ticket handler_id or a
--     ticket_comment created_by_id in their own source DB.
-- ----------------------------------------------------------------------------

DROP TEMPORARY TABLE IF EXISTS stg_s2user_referenced;
CREATE TEMPORARY TABLE stg_s2user_referenced AS
SELECT u.id AS legacy_id, 'store1_dev' AS source_system, u.username, u.email, u.name, u.password,
       u.password_expired, u.enabled, u.account_locked, u.account_expired, u.telegram_chat_id,
       u.last_show_version, u.page_render_option, u.date_created, u.last_updated
FROM store1_dev.s2user u
WHERE u.id IN (
    SELECT handler_id FROM store1_dev.ticket
    UNION
    SELECT created_by_id FROM store1_dev.ticket_comment WHERE created_by_id IS NOT NULL
)
UNION ALL
SELECT u.id, 'store3_dev', u.username, u.email, u.name, u.password,
       u.password_expired, u.enabled, u.account_locked, u.account_expired, u.telegram_chat_id,
       u.last_show_version, u.page_render_option, u.date_created, u.last_updated
FROM store3_dev.s2user u
WHERE u.id IN (
    SELECT handler_id FROM store3_dev.ticket
    UNION
    SELECT created_by_id FROM store3_dev.ticket_comment WHERE created_by_id IS NOT NULL
)
UNION ALL
SELECT u.id, 'store4_dev', u.username, u.email, u.name, u.password,
       u.password_expired, u.enabled, u.account_locked, u.account_expired, u.telegram_chat_id,
       u.last_show_version, u.page_render_option, u.date_created, u.last_updated
FROM store4_dev.s2user u
WHERE u.id IN (
    SELECT handler_id FROM store4_dev.ticket
    UNION
    SELECT created_by_id FROM store4_dev.ticket_comment WHERE created_by_id IS NOT NULL
)
UNION ALL
SELECT u.id, 'store2_dev', u.username, u.email, u.name, u.password,
       u.password_expired, u.enabled, u.account_locked, u.account_expired, u.telegram_chat_id,
       u.last_show_version, u.page_render_option, u.date_created, u.last_updated
FROM store2_dev.s2user u
WHERE u.id IN (
    SELECT handler_id FROM store2_dev.ticket
    UNION
    SELECT created_by_id FROM store2_dev.ticket_comment WHERE created_by_id IS NOT NULL
);

-- Canonical (most-recently-updated) row per person: source of email, name,
-- password, password_expired, telegram_chat_id, last_show_version,
-- page_render_option in the merged row.
DROP TEMPORARY TABLE IF EXISTS stg_s2user_canonical;
CREATE TEMPORARY TABLE stg_s2user_canonical AS
SELECT username, email, name, password, password_expired, telegram_chat_id, last_show_version, page_render_option
FROM (
         SELECT r.*, ROW_NUMBER() OVER (PARTITION BY username ORDER BY last_updated DESC, source_system) AS rn
         FROM stg_s2user_referenced r
     ) ranked
WHERE rn = 1;

INSERT INTO crm_dev.s2user
(username, email, name, version, account_expired, account_locked, enabled, password, password_expired, telegram_chat_id, last_show_version, page_render_option, date_created, last_updated)
SELECT
    agg.username,
    c.email,
    c.name,
    0,
    agg.max_expired,
    agg.max_locked,
    agg.max_enabled,
    c.password,
    c.password_expired,
    c.telegram_chat_id,
    c.last_show_version,
    c.page_render_option,
    agg.min_created,
    agg.max_updated
FROM (
         SELECT
             username,
             MIN(date_created) AS min_created,
             MAX(last_updated) AS max_updated,
             MAX(CAST(account_expired AS UNSIGNED)) AS max_expired,
             MAX(CAST(account_locked AS UNSIGNED)) AS max_locked,
             MAX(CAST(enabled AS UNSIGNED)) AS max_enabled
         FROM stg_s2user_referenced
         GROUP BY username
     ) agg
         JOIN stg_s2user_canonical c ON c.username = agg.username;

DROP TEMPORARY TABLE IF EXISTS stg_s2user_canonical;
DROP TEMPORARY TABLE IF EXISTS stg_s2user_referenced;


-- ----------------------------------------------------------------------------
-- 2d. PRE-FLIGHT for section 3's handler resolution -- see "s2user --
--     consolidate by username" above. ticket.handler_id is NOT NULL, so a
--     handler this two-hop join can't resolve would otherwise make section 3
--     either violate that constraint (LEFT JOIN, caught here first instead)
--     or silently drop the whole ticket (INNER JOIN, which section 3 does
--     NOT use precisely to avoid this). By construction this should already
--     be empty -- every source s2user referenced by a ticket's handler_id was
--     included in stg_s2user_referenced above, so its username is guaranteed
--     to exist in crm_dev.s2user -- but verifying beats assuming. MUST be
--     empty before running section 3.
SELECT 'store1_dev' AS src, t.id AS ticket_legacy_id, t.handler_id AS unresolvable_handler_id
FROM store1_dev.ticket t
         JOIN store1_dev.s2user ou ON ou.id = t.handler_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
WHERE nu.id IS NULL
UNION ALL
SELECT 'store3_dev', t.id, t.handler_id
FROM store3_dev.ticket t
         JOIN store3_dev.s2user ou ON ou.id = t.handler_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
WHERE nu.id IS NULL
UNION ALL
SELECT 'store4_dev', t.id, t.handler_id
FROM store4_dev.ticket t
         JOIN store4_dev.s2user ou ON ou.id = t.handler_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
WHERE nu.id IS NULL
UNION ALL
SELECT 'store2_dev', t.id, t.handler_id
FROM store2_dev.ticket t
         JOIN store2_dev.s2user ou ON ou.id = t.handler_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
WHERE nu.id IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Load ticket (depends on customer, address, s2user already being
--    loaded). customer_id passes the source row's own value straight through
--    via a LEFT JOIN against crm_dev.customer (nullable in the source schema,
--    and equal to the merged id -- see "customer -- ids are 1:1" above);
--    handler_id resolves via the two-hop username join verified by 2d above
--    (NOT NULL in the source schema, every ticket has a handler -- see that
--    section's own comment for why this still uses LEFT JOIN, not INNER,
--    for the second hop).
-- ----------------------------------------------------------------------------

INSERT INTO crm_dev.ticket
(legacy_id, source_system, action, customer_id, customer_name, date_created, description, email, handler_id, last_updated, status, customer_address_id, order_number, reason, refund_type, replacement_order_number, tracking_number, starred, search_text, out_of_warrant)
SELECT t.id, 'store1_dev', t.action, cu.id, t.customer_name, t.date_created, t.description, t.email, nu.id, t.last_updated, t.status, t.customer_address_id, t.order_number, t.reason, t.refund_type, t.replacement_order_number, t.tracking_number, t.starred, t.search_text, t.out_of_warrant
FROM store1_dev.ticket t
         JOIN store1_dev.s2user ou ON ou.id = t.handler_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
         LEFT JOIN crm_dev.customer cu ON cu.id = t.customer_id;

INSERT INTO crm_dev.ticket
(legacy_id, source_system, action, customer_id, customer_name, date_created, description, email, handler_id, last_updated, status, customer_address_id, order_number, reason, refund_type, replacement_order_number, tracking_number, starred, search_text, out_of_warrant)
SELECT t.id, 'store3_dev', t.action, cu.id, t.customer_name, t.date_created, t.description, t.email, nu.id, t.last_updated, t.status, t.customer_address_id, t.order_number, t.reason, t.refund_type, t.replacement_order_number, t.tracking_number, t.starred, t.search_text, t.out_of_warrant
FROM store3_dev.ticket t
         JOIN store3_dev.s2user ou ON ou.id = t.handler_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
         LEFT JOIN crm_dev.customer cu ON cu.id = t.customer_id;

INSERT INTO crm_dev.ticket
(legacy_id, source_system, action, customer_id, customer_name, date_created, description, email, handler_id, last_updated, status, customer_address_id, order_number, reason, refund_type, replacement_order_number, tracking_number, starred, search_text, out_of_warrant)
SELECT t.id, 'store4_dev', t.action, cu.id, t.customer_name, t.date_created, t.description, t.email, nu.id, t.last_updated, t.status, t.customer_address_id, t.order_number, t.reason, t.refund_type, t.replacement_order_number, t.tracking_number, t.starred, t.search_text, t.out_of_warrant
FROM store4_dev.ticket t
         JOIN store4_dev.s2user ou ON ou.id = t.handler_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
         LEFT JOIN crm_dev.customer cu ON cu.id = t.customer_id;

INSERT INTO crm_dev.ticket
(legacy_id, source_system, action, customer_id, customer_name, date_created, description, email, handler_id, last_updated, status, customer_address_id, order_number, reason, refund_type, replacement_order_number, tracking_number, starred, search_text, out_of_warrant)
SELECT t.id, 'store2_dev', t.action, cu.id, t.customer_name, t.date_created, t.description, t.email, nu.id, t.last_updated, t.status, t.customer_address_id, t.order_number, t.reason, t.refund_type, t.replacement_order_number, t.tracking_number, t.starred, t.search_text, t.out_of_warrant
FROM store2_dev.ticket t
         JOIN store2_dev.s2user ou ON ou.id = t.handler_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username
         LEFT JOIN crm_dev.customer cu ON cu.id = t.customer_id;


-- ----------------------------------------------------------------------------
-- 4. Load ticket_comment and ticket_line_item (depend on ticket being loaded).
--    created_by_id resolves via the same two-hop username join section 3
--    uses for handler_id, but LEFT JOIN at every step (both the first hop and
--    the second): created_by_id is nullable on the real table, so a comment
--    with no author, or an author this join can't resolve, still loads --
--    just with created_by_id ending up NULL, exactly as it did through
--    s2user_legacy_map before this amendment.
-- ----------------------------------------------------------------------------

INSERT INTO crm_dev.ticket_comment
(legacy_id, source_system, comment, created_by_id, date_created, last_updated, ticket_id)
SELECT tc.id, 'store1_dev', tc.comment, nu.id, tc.date_created, tc.last_updated, nt.id
FROM store1_dev.ticket_comment tc
         JOIN crm_dev.ticket nt ON nt.source_system = 'store1_dev' AND nt.legacy_id = tc.ticket_id
         LEFT JOIN store1_dev.s2user ou ON ou.id = tc.created_by_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username;

INSERT INTO crm_dev.ticket_comment
(legacy_id, source_system, comment, created_by_id, date_created, last_updated, ticket_id)
SELECT tc.id, 'store3_dev', tc.comment, nu.id, tc.date_created, tc.last_updated, nt.id
FROM store3_dev.ticket_comment tc
         JOIN crm_dev.ticket nt ON nt.source_system = 'store3_dev' AND nt.legacy_id = tc.ticket_id
         LEFT JOIN store3_dev.s2user ou ON ou.id = tc.created_by_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username;

INSERT INTO crm_dev.ticket_comment
(legacy_id, source_system, comment, created_by_id, date_created, last_updated, ticket_id)
SELECT tc.id, 'store4_dev', tc.comment, nu.id, tc.date_created, tc.last_updated, nt.id
FROM store4_dev.ticket_comment tc
         JOIN crm_dev.ticket nt ON nt.source_system = 'store4_dev' AND nt.legacy_id = tc.ticket_id
         LEFT JOIN store4_dev.s2user ou ON ou.id = tc.created_by_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username;

INSERT INTO crm_dev.ticket_comment
(legacy_id, source_system, comment, created_by_id, date_created, last_updated, ticket_id)
SELECT tc.id, 'store2_dev', tc.comment, nu.id, tc.date_created, tc.last_updated, nt.id
FROM store2_dev.ticket_comment tc
         JOIN crm_dev.ticket nt ON nt.source_system = 'store2_dev' AND nt.legacy_id = tc.ticket_id
         LEFT JOIN store2_dev.s2user ou ON ou.id = tc.created_by_id
         LEFT JOIN crm_dev.s2user nu ON nu.username = ou.username;


INSERT INTO crm_dev.ticket_line_item
(legacy_id, source_system, channelsku, date_created, last_updated, name, quantity, refund_amount, sku, ticket_id)
SELECT tl.id, 'store1_dev', tl.channelsku, tl.date_created, tl.last_updated, tl.name, tl.quantity, tl.refund_amount, tl.sku, nt.id
FROM store1_dev.ticket_line_item tl
         JOIN crm_dev.ticket nt ON nt.source_system = 'store1_dev' AND nt.legacy_id = tl.ticket_id;

INSERT INTO crm_dev.ticket_line_item
(legacy_id, source_system, channelsku, date_created, last_updated, name, quantity, refund_amount, sku, ticket_id)
SELECT tl.id, 'store3_dev', tl.channelsku, tl.date_created, tl.last_updated, tl.name, tl.quantity, tl.refund_amount, tl.sku, nt.id
FROM store3_dev.ticket_line_item tl
         JOIN crm_dev.ticket nt ON nt.source_system = 'store3_dev' AND nt.legacy_id = tl.ticket_id;

INSERT INTO crm_dev.ticket_line_item
(legacy_id, source_system, channelsku, date_created, last_updated, name, quantity, refund_amount, sku, ticket_id)
SELECT tl.id, 'store4_dev', tl.channelsku, tl.date_created, tl.last_updated, tl.name, tl.quantity, tl.refund_amount, tl.sku, nt.id
FROM store4_dev.ticket_line_item tl
         JOIN crm_dev.ticket nt ON nt.source_system = 'store4_dev' AND nt.legacy_id = tl.ticket_id;

INSERT INTO crm_dev.ticket_line_item
(legacy_id, source_system, channelsku, date_created, last_updated, name, quantity, refund_amount, sku, ticket_id)
SELECT tl.id, 'store2_dev', tl.channelsku, tl.date_created, tl.last_updated, tl.name, tl.quantity, tl.refund_amount, tl.sku, nt.id
FROM store2_dev.ticket_line_item tl
         JOIN crm_dev.ticket nt ON nt.source_system = 'store2_dev' AND nt.legacy_id = tl.ticket_id;


-- ----------------------------------------------------------------------------
-- 5. Load ticket_report (standalone, no real FK in the source schemas either)
-- ----------------------------------------------------------------------------

INSERT INTO crm_dev.ticket_report
(legacy_id, source_system, phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource)
SELECT id, 'store1_dev', phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource
FROM store1_dev.ticket_report;

INSERT INTO crm_dev.ticket_report
(legacy_id, source_system, phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource)
SELECT id, 'store3_dev', phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource
FROM store3_dev.ticket_report;

INSERT INTO crm_dev.ticket_report
(legacy_id, source_system, phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource)
SELECT id, 'store4_dev', phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource
FROM store4_dev.ticket_report;

INSERT INTO crm_dev.ticket_report
(legacy_id, source_system, phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource)
SELECT id, 'store2_dev', phone, item_name, address_line2, date_created, is_finish, last_updated, zip_code, buyer_full_name, state, order_date, sku, report_date, sales_channel, country_code, buyer_email, ticket_id, quantity, city, address_line1, item_description, address_name, order_number, order_resource
FROM store2_dev.ticket_report;


-- ----------------------------------------------------------------------------
-- 6. Verification
--
--    The reconciliation below is SELF-CHECKING: it emits an explicit
--    OK / MISMATCH status column per (table, source). Scan the status column
--    -- do not try to eyeball two separate result grids against each other.
--    (An earlier version of this script printed source counts and merged
--    counts as two independent grids; a genuine 1-row shortfall in
--    store4_dev.ticket_line_item went unnoticed that way, because every
--    statement still reported "Success". Hence the status column.)
--
--    ticket / ticket_comment / ticket_line_item / ticket_report must all be
--    OK. If any row says MISMATCH, the difference is rows the migration
--    dropped -- go back and run the section 0 pre-flight orphan check to
--    identify exactly which ones and why.
--
--    address is EXPECTED to be smaller than source (only ticket-referenced
--    rows migrate, and identical ids across brands collapse via INSERT
--    IGNORE), so it is reported separately, without a pass/fail status.
--
--    customer and s2user are identity-merges, so a raw count comparison is
--    meaningless for them -- they reconcile via the resolved-count queries
--    further below instead.
-- ----------------------------------------------------------------------------

-- The UNION is wrapped in a derived table purely so the ORDER BY can force
-- MISMATCH rows to the top explicitly. Do NOT "simplify" this to a bare
-- `ORDER BY status DESC` on the UNION: 'OK' sorts AFTER 'MISMATCH'
-- alphabetically, so DESC would bury the failures at the bottom of the grid
-- -- the exact thing this section exists to prevent.
SELECT * FROM (

                  SELECT 'ticket' AS tbl, s.src, s.source_count, COALESCE(m.merged_count,0) AS merged_count,
                         s.source_count - COALESCE(m.merged_count,0) AS dropped,
                         CASE WHEN s.source_count = COALESCE(m.merged_count,0) THEN 'OK' ELSE 'MISMATCH' END AS status
                  FROM (
                           SELECT 'store1_dev' AS src, COUNT(*) AS source_count FROM store1_dev.ticket
                           UNION ALL SELECT 'store3_dev', COUNT(*) FROM store3_dev.ticket
                           UNION ALL SELECT 'store4_dev', COUNT(*) FROM store4_dev.ticket
                           UNION ALL SELECT 'store2_dev', COUNT(*) FROM store2_dev.ticket
                       ) s
                           LEFT JOIN (SELECT source_system, COUNT(*) AS merged_count FROM crm_dev.ticket GROUP BY source_system) m
                                     ON m.source_system = s.src

                  UNION ALL
                  SELECT 'ticket_comment', s.src, s.source_count, COALESCE(m.merged_count,0),
                         s.source_count - COALESCE(m.merged_count,0),
                         CASE WHEN s.source_count = COALESCE(m.merged_count,0) THEN 'OK' ELSE 'MISMATCH' END
                  FROM (
                           SELECT 'store1_dev' AS src, COUNT(*) AS source_count FROM store1_dev.ticket_comment
                           UNION ALL SELECT 'store3_dev', COUNT(*) FROM store3_dev.ticket_comment
                           UNION ALL SELECT 'store4_dev', COUNT(*) FROM store4_dev.ticket_comment
                           UNION ALL SELECT 'store2_dev', COUNT(*) FROM store2_dev.ticket_comment
                       ) s
                           LEFT JOIN (SELECT source_system, COUNT(*) AS merged_count FROM crm_dev.ticket_comment GROUP BY source_system) m
                                     ON m.source_system = s.src

                  UNION ALL
                  SELECT 'ticket_line_item', s.src, s.source_count, COALESCE(m.merged_count,0),
                         s.source_count - COALESCE(m.merged_count,0),
                         CASE WHEN s.source_count = COALESCE(m.merged_count,0) THEN 'OK' ELSE 'MISMATCH' END
                  FROM (
                           SELECT 'store1_dev' AS src, COUNT(*) AS source_count FROM store1_dev.ticket_line_item
                           UNION ALL SELECT 'store3_dev', COUNT(*) FROM store3_dev.ticket_line_item
                           UNION ALL SELECT 'store4_dev', COUNT(*) FROM store4_dev.ticket_line_item
                           UNION ALL SELECT 'store2_dev', COUNT(*) FROM store2_dev.ticket_line_item
                       ) s
                           LEFT JOIN (SELECT source_system, COUNT(*) AS merged_count FROM crm_dev.ticket_line_item GROUP BY source_system) m
                                     ON m.source_system = s.src

                  UNION ALL
                  SELECT 'ticket_report', s.src, s.source_count, COALESCE(m.merged_count,0),
                         s.source_count - COALESCE(m.merged_count,0),
                         CASE WHEN s.source_count = COALESCE(m.merged_count,0) THEN 'OK' ELSE 'MISMATCH' END
                  FROM (
                           SELECT 'store1_dev' AS src, COUNT(*) AS source_count FROM store1_dev.ticket_report
                           UNION ALL SELECT 'store3_dev', COUNT(*) FROM store3_dev.ticket_report
                           UNION ALL SELECT 'store4_dev', COUNT(*) FROM store4_dev.ticket_report
                           UNION ALL SELECT 'store2_dev', COUNT(*) FROM store2_dev.ticket_report
                       ) s
                           LEFT JOIN (SELECT source_system, COUNT(*) AS merged_count FROM crm_dev.ticket_report GROUP BY source_system) m
                                     ON m.source_system = s.src

              ) recon
ORDER BY CASE WHEN status = 'MISMATCH' THEN 0 ELSE 1 END, tbl, src;

-- address: informational only (merged < source is expected, see note above)
SELECT 'address' AS tbl, source_system, COUNT(*) AS merged_count
FROM crm_dev.address GROUP BY source_system ORDER BY source_system;

-- Resolved customer/handler counts per source (audit follow-up: replaces the
-- old customer_legacy_map/s2user_legacy_map count query, neither table
-- existing anymore -- same question, "how many of this source's referenced
-- people actually resolved to a merged row", answered via the same
-- direct-join logic section 3 itself uses instead of a map table).
SELECT 'customer' AS tbl, 'store1_dev' AS src, COUNT(DISTINCT cu.id) AS resolved_referenced
FROM store1_dev.ticket t JOIN crm_dev.customer cu ON cu.id = t.customer_id
UNION ALL SELECT 'customer', 'store3_dev', COUNT(DISTINCT cu.id) FROM store3_dev.ticket t JOIN crm_dev.customer cu ON cu.id = t.customer_id
UNION ALL SELECT 'customer', 'store4_dev', COUNT(DISTINCT cu.id) FROM store4_dev.ticket t JOIN crm_dev.customer cu ON cu.id = t.customer_id
UNION ALL SELECT 'customer', 'store2_dev', COUNT(DISTINCT cu.id) FROM store2_dev.ticket t JOIN crm_dev.customer cu ON cu.id = t.customer_id
UNION ALL
SELECT 's2user', 'store1_dev', COUNT(DISTINCT nu.id) FROM (
    SELECT handler_id AS id FROM store1_dev.ticket
    UNION SELECT created_by_id FROM store1_dev.ticket_comment WHERE created_by_id IS NOT NULL
) x JOIN store1_dev.s2user ou ON ou.id = x.id JOIN crm_dev.s2user nu ON nu.username = ou.username
UNION ALL
SELECT 's2user', 'store3_dev', COUNT(DISTINCT nu.id) FROM (
    SELECT handler_id AS id FROM store3_dev.ticket
    UNION SELECT created_by_id FROM store3_dev.ticket_comment WHERE created_by_id IS NOT NULL
) x JOIN store3_dev.s2user ou ON ou.id = x.id JOIN crm_dev.s2user nu ON nu.username = ou.username
UNION ALL
SELECT 's2user', 'store4_dev', COUNT(DISTINCT nu.id) FROM (
    SELECT handler_id AS id FROM store4_dev.ticket
    UNION SELECT created_by_id FROM store4_dev.ticket_comment WHERE created_by_id IS NOT NULL
) x JOIN store4_dev.s2user ou ON ou.id = x.id JOIN crm_dev.s2user nu ON nu.username = ou.username
UNION ALL
SELECT 's2user', 'store2_dev', COUNT(DISTINCT nu.id) FROM (
    SELECT handler_id AS id FROM store2_dev.ticket
    UNION SELECT created_by_id FROM store2_dev.ticket_comment WHERE created_by_id IS NOT NULL
) x JOIN store2_dev.s2user ou ON ou.id = x.id JOIN crm_dev.s2user nu ON nu.username = ou.username
ORDER BY tbl, src;

-- Completeness check: how many DISTINCT customers/handlers does each
-- source's tickets actually reference, vs. how many actually resolved in the
-- query directly above. These two numbers should match exactly per
-- source_system -- a mismatch means some referenced customer/s2user record
-- failed to resolve (see section 2d's pre-flight for the handler case).
SELECT 'customer' AS tbl, 'store1_dev' AS src, COUNT(DISTINCT customer_id) AS expected_referenced FROM store1_dev.ticket WHERE customer_id IS NOT NULL
UNION ALL SELECT 'customer', 'store3_dev', COUNT(DISTINCT customer_id) FROM store3_dev.ticket WHERE customer_id IS NOT NULL
UNION ALL SELECT 'customer', 'store4_dev', COUNT(DISTINCT customer_id) FROM store4_dev.ticket WHERE customer_id IS NOT NULL
UNION ALL SELECT 'customer', 'store2_dev', COUNT(DISTINCT customer_id) FROM store2_dev.ticket WHERE customer_id IS NOT NULL
UNION ALL
SELECT 's2user', 'store1_dev', COUNT(*) FROM (
                                                  SELECT handler_id AS id FROM store1_dev.ticket
                                                  UNION SELECT created_by_id FROM store1_dev.ticket_comment WHERE created_by_id IS NOT NULL
                                              ) x
UNION ALL
SELECT 's2user', 'store3_dev', COUNT(*) FROM (
                                                 SELECT handler_id AS id FROM store3_dev.ticket
                                                 UNION SELECT created_by_id FROM store3_dev.ticket_comment WHERE created_by_id IS NOT NULL
                                             ) x
UNION ALL
SELECT 's2user', 'store4_dev', COUNT(*) FROM (
                                                 SELECT handler_id AS id FROM store4_dev.ticket
                                                 UNION SELECT created_by_id FROM store4_dev.ticket_comment WHERE created_by_id IS NOT NULL
                                             ) x
UNION ALL
SELECT 's2user', 'store2_dev', COUNT(*) FROM (
                                                 SELECT handler_id AS id FROM store2_dev.ticket
                                                 UNION SELECT created_by_id FROM store2_dev.ticket_comment WHERE created_by_id IS NOT NULL
                                             ) x
ORDER BY tbl, src;

-- Compare against the "resolved_referenced" counts query earlier in this
-- section -- "expected_referenced" above should equal "resolved_referenced"
-- for every row.

-- Uniqueness check: confirm the UNIQUE constraints actually did their job --
-- every legacy record for a given email/username really did land on the
-- SAME merged row (this can only fail if the UNIQUE KEY was somehow missing
-- or bypassed; included as a direct confirmation rather than relying solely
-- on the constraint existing).
SELECT COUNT(*) AS distinct_emails_in_customer, (SELECT COUNT(*) FROM crm_dev.customer) AS total_customer_rows
FROM (SELECT DISTINCT email FROM crm_dev.customer) x;

SELECT COUNT(*) AS distinct_usernames_in_s2user, (SELECT COUNT(*) FROM crm_dev.s2user) AS total_s2user_rows
FROM (SELECT DISTINCT username FROM crm_dev.s2user) x;