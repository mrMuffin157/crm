-- ============================================================================
-- No schema change here -- like doc/0.6.0/expand_sales_order_archive_migration.sql,
-- this file documents a behavior change in the sales order archive migration
-- tool, plus how to audit and rebuild its effect on crm_dev.sales_order_archive.
--
-- Unlike 0.6.0 (which edited tools.SalesOrderArchiveMigration.java in place),
-- this fix ships as a brand new class, tools.SalesOrderArchiveMigrationV2 --
-- the original SalesOrderArchiveMigration.java is left completely untouched
-- and still reflects exactly what it always did. Run SalesOrderArchiveMigrationV2
-- (not the original) for this and any future store1_dev archive migration.
--
-- Context: SalesOrderInfoTO (libs/exampleco-core) gained orderEvents,
-- a customer/isReviewDisabled refactor, and customerOrderCount
-- -- all landed AFTER doc/0.6.0's own fix (2026-08-31), and none of
-- them were ever addressed by the original SalesOrderArchiveMigration. Same
-- failure shape as the buyer/ship-to/fulfillment-package gap 0.6.0 already
-- fixed once: an archived order silently rendered a section blank that a live
-- order didn't, no error, just missing data.
--
-- What SalesOrderArchiveMigrationV2 actually fixes, and what it deliberately
-- doesn't:
--
--   1. customer.id -- a real bug, not a design choice. The original already
--      SELECTs customer.id from the archive host (it's the join key), but
--      only ever used it as a map key -- CustomerRow never carried it, and
--      toCustomerTO() never called CustomerTO#setId. Fixed in V2: CustomerRow.id
--      is now set from the query and copied onto CustomerTO.
--
--   2. orderEvents -- a genuine gap, not a bug: the original never queried
--      anything for it because no such query ever existed, not because one
--      was written and broken. store1_dev's sales_order_event table (fully
--      distinct from the 5 tables already read here -- address, customer,
--      fulfillment_package, sales_order, sales_order_line_item) is the source;
--      it was missed for the same reason the doc/0.5.0 header's own "what's
--      deliberately NOT here: customer and address" claim went stale (see #4
--      below) -- nobody re-checked the archive schema against SalesOrderInfoTO
--      after either landed further fields. Fixed in V2 via a new chunk-scoped
--      fetchOrderEvents(), decoding sales_order_event.event_type through a
--      newly hand-mirrored ArchivedSalesOrderEventType enum (same mirroring
--      caveat as ArchivedOrderStatus/ArchivedFulfillmentType -- confirmed
--      directly against legacy-app's own SalesOrderEventType: EmailReview=1,
--      EmailReviewAgain=2, EmailInstruction=3, Comment=11, ReviewBlocked=12,
--      Refund=21, Replacement=22, Other=99). dateCreated prefers
--      sales_order_event.legacy_date_created over date_created via COALESCE
--      when present (confirmed null on every row sampled directly against the
--      live archive, both 2015- and 2026-dated, so this is a defensive
--      fallback, not the common path). Events per order come back newest
--      first, matching orderEvents' own documented contract.
--
--   3. isReviewDisabled (both the top-level SalesOrderInfoTO flag and the
--      nested customer.isReviewDisabled) and customerOrderCount remain
--      deliberately NOT backfilled -- this was already correct in the
--      original and is unchanged in V2. Confirmed directly against
--      the live archive schema that sales_order.is_review_disabled and
--      customer.is_review_disabled DO exist as columns -- so this is not a
--      missing-source-data gap like orderEvents was. It's excluded on purpose:
--      both are live legacy-app state a review block can be applied to or lifted
--      from at any time after an order was archived, so a value frozen at
--      migration time would be actively misleading, not just absent.
--      customerOrderCount has no column to snapshot at all, live or
--      otherwise. An archive hit continues to report "review not disabled,
--      count 0", which the Sales Order page renders as "no review action
--      recorded" rather than as a claim.
--
--   4. Documentation hygiene: doc/0.5.0/create_sales_order_archive.sql's
--      header claims "customer and address [...] contribute no field to
--      SalesOrderInfoTO [...] -- joining them would pull real cost [...] for
--      data that has nowhere to go in the output shape." That was true when
--      0.5.0 was written but has been false since SalesOrderInfoTO gained
--      customer/recipientName/shipToAddress/shippingAddress (well before
--      0.6.0's fix, which already joins both). Left as-is in that file rather
--      than edited -- doc/${version} scripts are a hand-applied historical
--      record of what actually ran, per this project's convention (same
--      reason 0.6.0 added a new file instead of rewriting 0.5.0) -- but
--      flagged here so nobody re-derives a join-cost decision from a comment
--      that no longer matches the code.
--
-- No CREATE/ALTER needed for any of this: sales_order_info is already a JSON
-- blob (doc/0.5.0), so a new SalesOrderInfoTO field is just another key in
-- the blob -- SalesOrderArchive.java/SalesOrderArchiveService are unchanged.
--
-- Audit query below: how many archived rows predate this fix (run against
-- crm_dev; MySQL 5.7+ JSON_EXTRACT/->> works whether the row was written by
-- the pre- or post-0.7.0 version of the job).
-- ============================================================================

SELECT
    COUNT(*)                                                     AS total_rows,
    SUM(sales_order_info ->> '$.customer.id' IS NULL
        AND sales_order_info ->> '$.customer.name' IS NOT NULL)  AS customer_present_but_missing_id,
    SUM(JSON_LENGTH(sales_order_info, '$.orderEvents') = 0)      AS missing_order_events
FROM crm_dev.sales_order_archive
WHERE source_system = 'store1_dev';

-- ============================================================================
-- Reset-and-restart path (same choice doc/0.6.0 made, for the same reason):
-- a full re-walk with the migration's resume point forced to 0 would let the
-- existing ON DUPLICATE KEY UPDATE overwrite every row without deleting
-- first, but deleting first also catches any source sales_order row that was
-- removed from store1_dev after being migrated -- a plain re-walk only ever
-- revisits rows currently present in the source table, so it can never clean
-- up a row whose source disappeared. Deleting first guarantees crm_dev ends
-- up matching current source data exactly, not "current source data plus
-- whatever stale rows accumulated over time."
--
-- Trade-off accepted: sales_order_archive sits completely empty for the
-- entire re-walk's runtime (a multi-hour job against this table's real size),
-- so SalesOrderArchiveService's fallback returns nothing at all for every
-- archived order during that window, not just the ones not yet reprocessed.
-- Acceptable in dev/staging; re-evaluate before ever running this against a
-- customer-facing environment.
--
-- If the tables were dropped entirely (DROP TABLE), not just emptied, run
-- doc/0.5.0/create_sales_order_archive.sql FIRST (as one script -- see that
-- file's own "HOW TO RUN" note) to recreate both tables before continuing;
-- the DELETEs below assume the tables already exist.
--
-- Child table first (FK sales_order_archive_tracking.sales_order_archive_id
-- references sales_order_archive.id). Scoped by source_system rather than
-- TRUNCATE, matching 0.6.0's own approach, even though store1_dev is still
-- the only source_system in this table today.
DELETE FROM sales_order_archive_tracking
WHERE sales_order_archive_id IN (
    SELECT id FROM sales_order_archive WHERE source_system = 'store1_dev'
);

DELETE FROM sales_order_archive
WHERE source_system = 'store1_dev';

-- Verify empty before running tools.SalesOrderArchiveMigrationV2:
-- SELECT COUNT(*) FROM sales_order_archive WHERE source_system = 'store1_dev';

-- After the re-run completes, re-run the audit query above -- both
-- customer_present_but_missing_id and missing_order_events should read 0 (an
-- order with no linked customer row still reports NULL for customer.id, same
-- as before; an order that genuinely has zero rows in sales_order_event will
-- still show an empty orderEvents list -- that's a true empty history, not a
-- migration miss).
-- ============================================================================
