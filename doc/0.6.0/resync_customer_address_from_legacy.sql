-- ============================================================================
-- Re-sync crm_dev.customer / crm_dev.address from the 4 legacy per-storefront
-- DBs (store1_dev, store3_dev, store4_dev, store2_dev), to pick up rows
-- created or edited there SINCE doc/0.2.0/migrate_tickets_to_crm_dev.sql ran.
--
-- Why this is needed at all: that original migration pulled customer/address
-- selectively -- only rows a source DB's ticket/ticket_comment actually
-- referenced at the time (see its own "customer / address / s2user are
-- migrated selectively, NOT wholesale" note). Ticket creation moved to
-- crm_dev entirely after that (see CLAUDE.md "Ticket source_system"), so no
-- NEW ticket-driven references will ever appear in the legacy DBs again --
-- but legacy-app keeps writing/editing customer and address rows there as part
-- of its own unrelated order/storefront flow, independent of tickets. Two
-- crm_dev features now depend on crm_dev.customer/address being reasonably
-- current for people who never happened to have a ticket before the cutover:
--   - TicketController#resolveCustomerId (added 71e7b9c) matches a new/edited
--     ticket to an existing crm_dev.customer by email.
--   - SalesOrderCrmLinkService (added a2c792d) joins a sales order lookup to
--     crm_dev.customer/address.
-- Both silently no-op (find nothing) for anyone whose customer/address row
-- only exists in a legacy DB, never made it into crm_dev the first time, or
-- was edited there afterward. This script closes that gap on demand; it is
-- NOT wired into a scheduled job -- run it by hand when the gap needs
-- closing, same treatment as doc/0.2.0's script.
--
-- Run this the same way as doc/0.2.0/migrate_tickets_to_crm_dev.sql: against
-- the MySQL server that hosts all 5 schemas (db-stage.example.internal), as a user
-- with SELECT on the 4 source schemas and INSERT/UPDATE on crm_dev.
--
-- Scope, deliberately NOT "referenced by ticket" this time: since new tickets
-- no longer originate in the legacy DBs, that filter would exclude exactly
-- the people this script exists to catch (customers/addresses that a ticket
-- might reference for the FIRST time only after today, via email lookup or
-- the sales-order CRM link, not because a legacy ticket pointed at them).
-- Scoped instead to `@cutoff` below -- rows created or updated in a source DB
-- after that timestamp -- so a full unfiltered table scan isn't required on
-- every run. Set @cutoff to the last time this script was successfully run
-- (or to the original migration's completion time, on first run).
--
-- source_system on newly-inserted address rows is tagged with the same
-- az-* channel codes doc/0.5.0/normalize_ticket_source_system.sql defines
-- (store1_dev -> az-us, store3_dev -> az-au, store4_dev -> az-ca, store2_dev
-- -> az-deco) -- NOT the raw legacy schema name doc/0.2.0's original
-- migration used. This script ALSO re-runs that same normalization against
-- ticket/ticket_comment/ticket_line_item/address/ticket_report at the bottom
-- (see that section for why it's duplicated here rather than left as a
-- separate manual step), so running this one file end to end leaves every
-- row -- old and newly-synced -- in the same az-* format.
--
-- Idempotent / safe to re-run: customer is upserted keyed on the `email`
-- unique key (id is already validated 1:1 with the source row's own id, same
-- assumption doc/0.2.0's script made and verified against prod on
-- 2026-08-27); a source row only overwrites crm_dev's existing name/dates if
-- its own last_updated is strictly newer, so a stale re-run (or a customer
-- edited directly in crm_dev since) can never clobber newer data. address is
-- upserted keyed on `id` (the legacy address id, carried over unchanged --
-- there is no natural key otherwise, same as doc/0.2.0's script), same
-- newer-wins guard on last_updated.
-- ============================================================================

USE crm_dev;

-- Adjust before every run: only source rows touched after this instant are
-- considered. First run after doc/0.2.0's migration: use that migration's
-- completion timestamp. Subsequent runs: use the previous run's start time.
SET @cutoff = '2026-08-27 00:00:00';

-- ----------------------------------------------------------------------------
-- customer: identity-merge by email, same logic as doc/0.2.0's script,
-- restricted to rows touched since @cutoff, upserted rather than
-- insert-only.
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS stg_customer_changed;
CREATE TEMPORARY TABLE stg_customer_changed AS
SELECT * FROM (
    SELECT id, consumer_group_icons, date_created, email, is_new,
           is_review_disabled, last_updated, name, 'store1_dev' AS source_system
    FROM store1_dev.customer
    WHERE email IS NOT NULL AND GREATEST(date_created, last_updated) > @cutoff
    UNION ALL
    SELECT id, consumer_group_icons, date_created, email, is_new,
           is_review_disabled, last_updated, name, 'store3_dev'
    FROM store3_dev.customer
    WHERE email IS NOT NULL AND GREATEST(date_created, last_updated) > @cutoff
    UNION ALL
    SELECT id, consumer_group_icons, date_created, email, is_new,
           is_review_disabled, last_updated, name, 'store4_dev'
    FROM store4_dev.customer
    WHERE email IS NOT NULL AND GREATEST(date_created, last_updated) > @cutoff
    UNION ALL
    SELECT id, consumer_group_icons, date_created, email, is_new,
           is_review_disabled, last_updated, name, 'store2_dev'
    FROM store2_dev.customer
    WHERE email IS NOT NULL AND GREATEST(date_created, last_updated) > @cutoff
) all_sources;

-- One canonical row per email: newest last_updated wins ties broken by
-- source_system, mirroring doc/0.2.0's stg_customer_canonical. Written as a
-- single reference to stg_customer_changed (ROW_NUMBER directly, no self-join
-- back onto it) -- MySQL cannot open the same TEMPORARY table twice in one
-- statement ("Can't reopen table"), which a join-back-by-id form would need.
DROP TEMPORARY TABLE IF EXISTS stg_customer_canonical;
CREATE TEMPORARY TABLE stg_customer_canonical AS
SELECT id, consumer_group_icons, date_created, email, is_new, is_review_disabled,
       last_updated, name, source_system
FROM (
    SELECT *,
           ROW_NUMBER() OVER (PARTITION BY email ORDER BY last_updated DESC, source_system) AS rn
    FROM stg_customer_changed
) ranked
WHERE rn = 1;

INSERT INTO crm_dev.customer
    (id, consumer_group_icons, date_created, email, is_new, is_review_disabled, last_updated, name)
SELECT id, consumer_group_icons, date_created, email, is_new, is_review_disabled, last_updated, name
FROM stg_customer_canonical
ON DUPLICATE KEY UPDATE
    consumer_group_icons = IF(VALUES(last_updated) > customer.last_updated, VALUES(consumer_group_icons), customer.consumer_group_icons),
    is_new               = IF(VALUES(last_updated) > customer.last_updated, VALUES(is_new), customer.is_new),
    is_review_disabled   = IF(VALUES(last_updated) > customer.last_updated, VALUES(is_review_disabled), customer.is_review_disabled),
    name                 = IF(VALUES(last_updated) > customer.last_updated, VALUES(name), customer.name),
    date_created         = LEAST(customer.date_created, VALUES(date_created)),
    last_updated         = GREATEST(customer.last_updated, VALUES(last_updated));

-- ----------------------------------------------------------------------------
-- address: no natural key across sources (same as doc/0.2.0), upserted by
-- the legacy id, restricted to rows touched since @cutoff.
-- ----------------------------------------------------------------------------
INSERT INTO crm_dev.address
    (id, source_system, address_line1, address_line2, address_line3, city, company_name,
     country_code, date_created, last_updated, name, phone, state, zip_code)
SELECT src.id, 'az-us', src.address_line1, src.address_line2, src.address_line3, src.city, src.company_name,
       src.country_code, src.date_created, src.last_updated, src.name, src.phone, src.state, src.zip_code
FROM store1_dev.address AS src
WHERE GREATEST(src.date_created, src.last_updated) > @cutoff
ON DUPLICATE KEY UPDATE
    -- Guard every field on source_system still matching: doc/0.2.0's own
    -- design note says address ids are only deduped "on exact id collision"
    -- -- each of the 4 legacy DBs has its own independent auto-increment
    -- sequence, so the SAME id can legitimately belong to two totally
    -- unrelated addresses in two different storefronts. If that happens here,
    -- source_system will already differ from what's stored, and every branch
    -- below leaves the existing row completely untouched -- matching
    -- doc/0.2.0's INSERT IGNORE "first storefront wins, never clobber across
    -- sources" behavior, instead of letting an unrelated storefront's address
    -- overwrite this one just because its last_updated happens to be newer.
    address_line1 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line1), address.address_line1),
    address_line2 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line2), address.address_line2),
    address_line3 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line3), address.address_line3),
    city          = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(city), address.city),
    company_name  = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(company_name), address.company_name),
    country_code  = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(country_code), address.country_code),
    name          = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(name), address.name),
    phone         = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(phone), address.phone),
    state         = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(state), address.state),
    zip_code      = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(zip_code), address.zip_code),
    last_updated  = IF(address.source_system = VALUES(source_system), GREATEST(address.last_updated, VALUES(last_updated)), address.last_updated);

INSERT INTO crm_dev.address
    (id, source_system, address_line1, address_line2, address_line3, city, company_name,
     country_code, date_created, last_updated, name, phone, state, zip_code)
SELECT src.id, 'az-au', src.address_line1, src.address_line2, src.address_line3, src.city, src.company_name,
       src.country_code, src.date_created, src.last_updated, src.name, src.phone, src.state, src.zip_code
FROM store3_dev.address AS src
WHERE GREATEST(src.date_created, src.last_updated) > @cutoff
ON DUPLICATE KEY UPDATE
    -- Guard every field on source_system still matching: doc/0.2.0's own
    -- design note says address ids are only deduped "on exact id collision"
    -- -- each of the 4 legacy DBs has its own independent auto-increment
    -- sequence, so the SAME id can legitimately belong to two totally
    -- unrelated addresses in two different storefronts. If that happens here,
    -- source_system will already differ from what's stored, and every branch
    -- below leaves the existing row completely untouched -- matching
    -- doc/0.2.0's INSERT IGNORE "first storefront wins, never clobber across
    -- sources" behavior, instead of letting an unrelated storefront's address
    -- overwrite this one just because its last_updated happens to be newer.
    address_line1 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line1), address.address_line1),
    address_line2 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line2), address.address_line2),
    address_line3 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line3), address.address_line3),
    city          = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(city), address.city),
    company_name  = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(company_name), address.company_name),
    country_code  = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(country_code), address.country_code),
    name          = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(name), address.name),
    phone         = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(phone), address.phone),
    state         = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(state), address.state),
    zip_code      = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(zip_code), address.zip_code),
    last_updated  = IF(address.source_system = VALUES(source_system), GREATEST(address.last_updated, VALUES(last_updated)), address.last_updated);

INSERT INTO crm_dev.address
    (id, source_system, address_line1, address_line2, address_line3, city, company_name,
     country_code, date_created, last_updated, name, phone, state, zip_code)
SELECT src.id, 'az-ca', src.address_line1, src.address_line2, src.address_line3, src.city, src.company_name,
       src.country_code, src.date_created, src.last_updated, src.name, src.phone, src.state, src.zip_code
FROM store4_dev.address AS src
WHERE GREATEST(src.date_created, src.last_updated) > @cutoff
ON DUPLICATE KEY UPDATE
    -- Guard every field on source_system still matching: doc/0.2.0's own
    -- design note says address ids are only deduped "on exact id collision"
    -- -- each of the 4 legacy DBs has its own independent auto-increment
    -- sequence, so the SAME id can legitimately belong to two totally
    -- unrelated addresses in two different storefronts. If that happens here,
    -- source_system will already differ from what's stored, and every branch
    -- below leaves the existing row completely untouched -- matching
    -- doc/0.2.0's INSERT IGNORE "first storefront wins, never clobber across
    -- sources" behavior, instead of letting an unrelated storefront's address
    -- overwrite this one just because its last_updated happens to be newer.
    address_line1 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line1), address.address_line1),
    address_line2 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line2), address.address_line2),
    address_line3 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line3), address.address_line3),
    city          = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(city), address.city),
    company_name  = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(company_name), address.company_name),
    country_code  = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(country_code), address.country_code),
    name          = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(name), address.name),
    phone         = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(phone), address.phone),
    state         = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(state), address.state),
    zip_code      = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(zip_code), address.zip_code),
    last_updated  = IF(address.source_system = VALUES(source_system), GREATEST(address.last_updated, VALUES(last_updated)), address.last_updated);

INSERT INTO crm_dev.address
    (id, source_system, address_line1, address_line2, address_line3, city, company_name,
     country_code, date_created, last_updated, name, phone, state, zip_code)
SELECT src.id, 'az-deco', src.address_line1, src.address_line2, src.address_line3, src.city, src.company_name,
       src.country_code, src.date_created, src.last_updated, src.name, src.phone, src.state, src.zip_code
FROM store2_dev.address AS src
WHERE GREATEST(src.date_created, src.last_updated) > @cutoff
ON DUPLICATE KEY UPDATE
    -- Guard every field on source_system still matching: doc/0.2.0's own
    -- design note says address ids are only deduped "on exact id collision"
    -- -- each of the 4 legacy DBs has its own independent auto-increment
    -- sequence, so the SAME id can legitimately belong to two totally
    -- unrelated addresses in two different storefronts. If that happens here,
    -- source_system will already differ from what's stored, and every branch
    -- below leaves the existing row completely untouched -- matching
    -- doc/0.2.0's INSERT IGNORE "first storefront wins, never clobber across
    -- sources" behavior, instead of letting an unrelated storefront's address
    -- overwrite this one just because its last_updated happens to be newer.
    address_line1 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line1), address.address_line1),
    address_line2 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line2), address.address_line2),
    address_line3 = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(address_line3), address.address_line3),
    city          = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(city), address.city),
    company_name  = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(company_name), address.company_name),
    country_code  = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(country_code), address.country_code),
    name          = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(name), address.name),
    phone         = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(phone), address.phone),
    state         = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(state), address.state),
    zip_code      = IF(address.source_system = VALUES(source_system) AND VALUES(last_updated) > address.last_updated, VALUES(zip_code), address.zip_code),
    last_updated  = IF(address.source_system = VALUES(source_system), GREATEST(address.last_updated, VALUES(last_updated)), address.last_updated);

-- ----------------------------------------------------------------------------
-- Verification: row count pulled this run, for the operator's own record --
-- not a correctness check (unlike doc/0.2.0's "ids are 1:1" query, there is
-- no cross-source invariant left to verify here: address has never had one,
-- and customer's id/email 1:1 assumption was already validated by that
-- earlier script and isn't re-checked per-run). Read before the temp tables
-- are dropped below.
-- ----------------------------------------------------------------------------
SELECT COUNT(*) AS customers_synced FROM stg_customer_canonical;

DROP TEMPORARY TABLE IF EXISTS stg_customer_changed;
DROP TEMPORARY TABLE IF EXISTS stg_customer_canonical;

-- ============================================================================
-- Normalize source_system on every ticket-domain table, not just the address
-- rows this script just synced.
--
-- Why this is duplicated here instead of just telling the operator to also
-- run doc/0.5.0/normalize_ticket_source_system.sql separately: running this
-- resync script tags brand-new address rows with az-* codes (see above), but
-- as of 2026-08-31 a check against stage found doc/0.5.0's script had NEVER
-- actually been run there -- every pre-existing row across all 5
-- ticket-domain tables (ticket, ticket_comment, ticket_line_item, address,
-- ticket_report) still carried the raw legacy schema name. Left alone, that
-- means the exact split this resync script is meant to close: old rows on
-- one naming convention, new rows on another. Bundling the same idempotent
-- UPDATEs here means running this one file also fixes that drift, instead of
-- depending on a second file being remembered and run in the right order.
-- Identical logic to doc/0.5.0/normalize_ticket_source_system.sql -- see that
-- file for the full design rationale (channel-code mapping source, why both
-- _dev-suffixed and un-suffixed spellings are handled, etc). Safe to re-run:
-- every CASE only rewrites rows still holding one of the 8 known legacy
-- spellings; already-normalized rows (including the az-* rows this script
-- itself just inserted) fall through the ELSE untouched.
-- ============================================================================
UPDATE crm_dev.ticket
SET source_system = CASE source_system
    WHEN 'store1_dev' THEN 'az-us'   WHEN 'store1' THEN 'az-us'
    WHEN 'store2_dev'  THEN 'az-deco' WHEN 'store2'  THEN 'az-deco'
    WHEN 'store4_dev'  THEN 'az-ca'   WHEN 'store4'  THEN 'az-ca'
    WHEN 'store3_dev'  THEN 'az-au'   WHEN 'store3'  THEN 'az-au'
    ELSE source_system
END;

UPDATE crm_dev.ticket_comment
SET source_system = CASE source_system
    WHEN 'store1_dev' THEN 'az-us'   WHEN 'store1' THEN 'az-us'
    WHEN 'store2_dev'  THEN 'az-deco' WHEN 'store2'  THEN 'az-deco'
    WHEN 'store4_dev'  THEN 'az-ca'   WHEN 'store4'  THEN 'az-ca'
    WHEN 'store3_dev'  THEN 'az-au'   WHEN 'store3'  THEN 'az-au'
    ELSE source_system
END;

UPDATE crm_dev.ticket_line_item
SET source_system = CASE source_system
    WHEN 'store1_dev' THEN 'az-us'   WHEN 'store1' THEN 'az-us'
    WHEN 'store2_dev'  THEN 'az-deco' WHEN 'store2'  THEN 'az-deco'
    WHEN 'store4_dev'  THEN 'az-ca'   WHEN 'store4'  THEN 'az-ca'
    WHEN 'store3_dev'  THEN 'az-au'   WHEN 'store3'  THEN 'az-au'
    ELSE source_system
END;

UPDATE crm_dev.address
SET source_system = CASE source_system
    WHEN 'store1_dev' THEN 'az-us'   WHEN 'store1' THEN 'az-us'
    WHEN 'store2_dev'  THEN 'az-deco' WHEN 'store2'  THEN 'az-deco'
    WHEN 'store4_dev'  THEN 'az-ca'   WHEN 'store4'  THEN 'az-ca'
    WHEN 'store3_dev'  THEN 'az-au'   WHEN 'store3'  THEN 'az-au'
    ELSE source_system
END;

UPDATE crm_dev.ticket_report
SET source_system = CASE source_system
    WHEN 'store1_dev' THEN 'az-us'   WHEN 'store1' THEN 'az-us'
    WHEN 'store2_dev'  THEN 'az-deco' WHEN 'store2'  THEN 'az-deco'
    WHEN 'store4_dev'  THEN 'az-ca'   WHEN 'store4'  THEN 'az-ca'
    WHEN 'store3_dev'  THEN 'az-au'   WHEN 'store3'  THEN 'az-au'
    ELSE source_system
END;

-- Verification -- every row here should now show a channel code (az-us/
-- az-deco/az-ca/az-au) or "crm" (this app's own placeholder for brand-new
-- tickets). Any other value means a legacy spelling neither this script nor
-- doc/0.5.0's knows about yet.
SELECT 'ticket' AS tbl, source_system, COUNT(*) FROM crm_dev.ticket GROUP BY source_system
UNION ALL
SELECT 'ticket_comment', source_system, COUNT(*) FROM crm_dev.ticket_comment GROUP BY source_system
UNION ALL
SELECT 'ticket_line_item', source_system, COUNT(*) FROM crm_dev.ticket_line_item GROUP BY source_system
UNION ALL
SELECT 'address', source_system, COUNT(*) FROM crm_dev.address GROUP BY source_system
UNION ALL
SELECT 'ticket_report', source_system, COUNT(*) FROM crm_dev.ticket_report GROUP BY source_system
ORDER BY tbl, source_system;
