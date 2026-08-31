-- ============================================================================
-- No schema change here -- this file documents a behavior change in
-- tools.SalesOrderArchiveMigration (the Java job, not SQL), plus how to audit
-- and backfill its effect on crm_dev.sales_order_archive.
--
-- Context: the new Sales Order page (frontend/src/SalesOrdersPage.jsx) now
-- renders buyer name/email, recipient name, ship-to address, and fulfillment
-- packages (carrier + tracking + ETA per package). All of these are real
-- fields on SalesOrderInfoTO/FulfillmentPackageTO (libs/exampleco-core) --
-- buyerName, buyerEmail, recipientName, shipToAddress, shippingAddress,
-- fulfillmentPackages -- added to those TOs AFTER
-- doc/0.5.0/create_sales_order_archive.sql and SalesOrderArchiveMigration
-- were first written. The migration was never revisited, so it kept writing
-- sales_order_archive.sales_order_info JSON without those fields, and
-- fulfillment_package was only ever queried for tracking_number, not
-- carrier/estimate_arrival_date. Result: an archived order (one that's aged
-- out of legacy-app's 4 live storefront hosts and falls through to
-- SalesOrderArchiveService) rendered those sections blank, while a live
-- order didn't -- no error, just silently missing data.
--
-- Fix (in SalesOrderArchiveMigration.java, this same change):
--   - sales_order.customer_id / shipping_address_id are now read off the
--     master query, then customer/address are looked up via the same
--     chunk-scoped batched IN (...) shape already used for line items and
--     fulfillment packages -- never joined into the master sales_order
--     query. doc/0.5.0's original design note (customer is 11G, address is
--     15G on the archive host -- avoid a whole-table join) still holds; a
--     chunk-scoped lookup only ever touches the handful of rows a chunk's
--     own orders actually reference.
--   - fulfillment_package is now also read for carrier/estimate_arrival_date
--     (previously only tracking_number), populating fulfillmentPackages.
--   - shipToAddress is formatted the same way this app's own ticket
--     copy-address feature already formats an address block (see
--     frontend/src/App.jsx#buildAddressLines): name, line 1, line 2 if
--     present, then city/state/zip on one line.
--
-- No CREATE/ALTER here because none of this needed one: sales_order_info is
-- already a JSON blob (doc/0.5.0), so a new SalesOrderInfoTO field just
-- means the blob has another key -- the column, and everything in
-- SalesOrderArchive.java/SalesOrderArchiveService, is unchanged.
--
-- Backfill caveat: SalesOrderArchiveMigration resumes from
-- MAX(source_sales_order_id) already committed in crm_dev, so simply
-- re-running the fixed job only reaches orders it hasn't migrated yet --
-- every row a prior run already wrote keeps its old, field-missing JSON.
-- Enriching those needs a deliberate full re-walk (temporarily treating the
-- job's resume point as 0 so its existing ON DUPLICATE KEY UPDATE overwrites
-- sales_order_info on every already-migrated row) -- not done automatically,
-- since that's a real decision about how much of a very large table to
-- re-touch, left to whoever runs it.
--
-- Audit query below: how many archived rows are still missing the new data
-- (run against crm_dev; MySQL 5.7+ JSON_EXTRACT/->> works whether the row
-- was written by the old or new version of the job).
-- ============================================================================

SELECT
    COUNT(*)                                                          AS total_rows,
    SUM(sales_order_info ->> '$.buyerName' IS NULL)                   AS missing_buyer_name,
    SUM(sales_order_info ->> '$.recipientName' IS NULL)                AS missing_recipient_name,
    SUM(sales_order_info ->> '$.shipToAddress' IS NULL)                AS missing_ship_to_address,
    SUM(JSON_LENGTH(sales_order_info, '$.fulfillmentPackages') = 0)    AS missing_fulfillment_packages
FROM crm_dev.sales_order_archive
WHERE source_system = 'store1_dev';

-- Result as of 2026-09-01 (crm_dev, source_system = 'store1_dev'):
--   total_rows = N
--   missing_buyer_name = N (every row)
--   missing_recipient_name = N (every row)
--   missing_ship_to_address = N (every row)
--   missing_fulfillment_packages = NULL (not 0 -- JSON_LENGTH() on a wholly
--     absent key returns NULL, and NULL = 0 is NULL, so SUM() over an
--     all-NULL column is NULL rather than a count; this still means every
--     row's JSON predates the fulfillmentPackages field entirely)
-- i.e. the entire table was written by the pre-fix job -- a full re-walk
-- backfill (see the caveat above) is needed, not a partial one.

-- ============================================================================
-- Reset-and-restart path taken instead of an in-place backfill (2026-09-01).
--
-- A full re-walk with SalesOrderArchiveMigration's resume point forced to 0
-- (RESUME_AFTER_ID env var override, added to that class alongside this
-- file) would have overwritten every row via its existing
-- ON DUPLICATE KEY UPDATE without needing a delete first. Deleting first was
-- chosen anyway for one reason the upsert path can't cover on its own: if any
-- source sales_order row was ever removed from store1_dev after being
-- migrated, its crm_dev.sales_order_archive row would never get cleaned up
-- by a re-walk (the job only ever visits rows currently present in the
-- source table) -- deleting first guarantees crm_dev exactly matches current
-- source data, not "current source data plus whatever stale rows accumulated
-- over time."
--
-- Trade-off accepted: sales_order_archive sits completely empty for the
-- entire re-walk's runtime (a multi-hour job against every source row),
-- so SalesOrderArchiveService's fallback returns nothing at all for every
-- archived order during that window, not just the ones not yet reprocessed.
-- Acceptable here since crm_dev is a dev/staging environment, not customer-
-- facing production.
--
-- Child table first (FK sales_order_archive_tracking.sales_order_archive_id
-- references sales_order_archive.id). Scoped by source_system rather than
-- TRUNCATE, matching the audit query above, even though store1_dev is the
-- only source_system that exists in this table today.
DELETE FROM sales_order_archive_tracking
WHERE sales_order_archive_id IN (
    SELECT id FROM sales_order_archive WHERE source_system = 'store1_dev'
);

DELETE FROM sales_order_archive
WHERE source_system = 'store1_dev';

-- Verify empty before re-running the migration:
-- SELECT COUNT(*) FROM sales_order_archive WHERE source_system = 'store1_dev';
-- ============================================================================
