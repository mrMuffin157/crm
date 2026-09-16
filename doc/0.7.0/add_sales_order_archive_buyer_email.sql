-- ============================================================================
-- crm_dev.sales_order_archive: add an indexed buyer_email so the archive can
-- be queried BY BUYER, not just by order.
--
-- Two user-visible symptoms, one root cause.
--
--   1. An archived order's Sales Order page shows the buyer as "0 orders"
--      while legacy-app's own show page shows the real number (e.g. "(5) orders"
--      for 111-0000000-0000003 / customer 00000000000000000000000000000001).
--
--   2. Clicking that count opens the Customer View on nothing: the backing
--      GET /api/salesApi/getSalesOrderInfoListByEmail fans out across legacy-app's
--      4 live hosts only, all 4 answer [] for a buyer whose orders have aged
--      out, and the endpoint 404s.
--
-- Root cause: SalesOrderInfoTO.customerOrderCount is deliberately NOT
-- backfilled by either migration tool (see this folder's
-- backfill_sales_order_archive_events_and_customer_id.sql #3 -- a count frozen
-- at migration time would be wrong the moment it was written), and because the
-- field is a primitive int it serializes as a confident-looking 0 rather than
-- an absent value. Meanwhile sales_order_archive has no queryable buyer column
-- at all -- source_system, source_sales_order_id, amazon_order_id and the JSON
-- blob -- so nothing on this side could compute the count or list a buyer's
-- orders even though the email is sitting right there inside sales_order_info.
--
-- The fix keeps the "never freeze live state" rule intact: the count is not
-- stored anywhere. It is COUNTed at read time by SalesOrderArchiveService,
-- which cannot go stale, and added to whatever legacy-app's live lookup already
-- reported (the two order sets are disjoint -- see the verification at the
-- bottom of this file).
--
-- ============================================================================
-- Why a VIRTUAL generated column instead of a real column + backfill
-- ============================================================================
--
-- sales_order_archive is very large. A real NULLable column would be an
-- INSTANT add, but populating it means an UPDATE across every row, and it
-- then has to be kept in sync by every future writer (SalesOrderArchiveMigrationV2
-- and anything after it) -- a second source of truth for a value that already
-- exists in sales_order_info, i.e. exactly the drift this folder keeps having
-- to write scripts about.
--
-- A VIRTUAL generated column has neither problem. Adding one rewrites no table
-- data (it is metadata only -- MySQL 8.0 does this INSTANT), the index on it is
-- built INPLACE without touching the table, no writer ever sets it, and it
-- cannot disagree with the JSON it is derived from. Confirmed against the live
-- server: MySQL 8.0.39, which supports both the virtual column and a secondary
-- index over it.
--
-- The composite (buyer_email, source_sales_order_id) -- not buyer_email alone --
-- so the Customer View's "this buyer's orders, newest first" read is served in
-- index order with no filesort. source_sales_order_id ascends with time on the
-- archive host, so it is a usable proxy for purchase order.
--
-- Run these one statement at a time ("Execute SQL Statement", not "Execute
-- Script") -- the ADD INDEX below is the long one and wants to be watched.

-- ---------------------------------------------------------------------------
-- 1. The column. Metadata-only: expect this to return in well under a second
--    even against the full table. VIRTUAL is load-bearing -- do NOT change it to
--    STORED, which would force a full table rebuild and reintroduce the
--    backfill this script exists to avoid.
--
--    The JSON path matches what the migration tools actually write:
--    {"customer": {"id": ..., "name": ..., "email": ..., "reviewDisabled": ...}}.
--    Orders with no linked customer have no $.customer at all and resolve to
--    NULL here, which is correct -- they are excluded from every buyer query
--    below, and a count of orders sharing "no buyer" would say nothing about
--    any buyer.
-- ---------------------------------------------------------------------------
ALTER TABLE crm_dev.sales_order_archive
    ADD COLUMN buyer_email VARCHAR(255)
        GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(sales_order_info, '$.customer.email'))) VIRTUAL,
    ALGORITHM=INSTANT;
-- If this server refuses INSTANT for a virtual column, drop the clause and let
-- MySQL pick (it falls back to INPLACE, still without rewriting table data).
-- Do NOT "fix" it by making the column STORED.

-- ---------------------------------------------------------------------------
-- 2. The index. This is the expensive statement: it evaluates the expression
--    above once per row to build the index, without rewriting the table.
--    Budget for it accordingly on a table this size, and run it in a window
--    where a long-running INPLACE DDL is acceptable. LOCK=NONE keeps
--    concurrent reads/writes of sales_order_archive working throughout.
-- ---------------------------------------------------------------------------
ALTER TABLE crm_dev.sales_order_archive
    ADD KEY idx_buyer_email_source_sales_order_id (buyer_email, source_sales_order_id),
    ALGORITHM=INPLACE, LOCK=NONE;

-- ============================================================================
-- Verification
-- ============================================================================

-- The column exists and is VIRTUAL GENERATED (not STORED, not a plain column).
SELECT COLUMN_NAME, DATA_TYPE, EXTRA, GENERATION_EXPRESSION
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'crm_dev' AND TABLE_NAME = 'sales_order_archive'
  AND COLUMN_NAME = 'buyer_email';

-- The index exists over both columns in the right order.
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'crm_dev' AND TABLE_NAME = 'sales_order_archive'
  AND INDEX_NAME = 'idx_buyer_email_source_sales_order_id'
ORDER BY SEQ_IN_INDEX;

-- The reported symptom, now answerable from this side. Expect 5 rows, all
-- 115-* orders, matching what legacy-app's own show page counts for this buyer
-- (verified against the archive host: SELECT COUNT(*) FROM sales_order WHERE
-- customer_id = '00000000000000000000000000000001' -> 5).
SELECT source_sales_order_id, amazon_order_id
FROM crm_dev.sales_order_archive
WHERE buyer_email = 'buyer1@example.com'
ORDER BY source_sales_order_id DESC;

-- The index is actually used -- this must show "ref" on
-- idx_buyer_email_source_sales_order_id, NOT a full table scan. If it reports
-- ALL, stop: a full-table scan per Customer View open is not a fix.
EXPLAIN SELECT source_sales_order_id
FROM crm_dev.sales_order_archive
WHERE buyer_email = 'buyer1@example.com'
ORDER BY source_sales_order_id DESC;

-- ============================================================================
-- Why the live count and the archived count can simply be added together
-- ============================================================================
--
-- SalesOrderController#lookup adds the archived count for a buyer on top of
-- whatever legacy-app reported, which is only correct if an order can never be in
-- both places. Confirm the two are disjoint before trusting the sum, and re-run
-- this check if either host is ever reloaded:
--
--   SELECT MIN(id), MAX(id), MIN(date_purchased), MAX(date_purchased)
--   FROM sales_order;   -- on each host; the two id ranges must not overlap
--
