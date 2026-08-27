-- ============================================================================
-- New crm_dev tables: sales_order_archive, sales_order_archive_tracking.
--
-- Purpose: legacy-app's live Sales Order lookup (SalesOrderController /
-- LegacyApiClient) only ever queries legacy-app's 4 current storefront hosts, and
-- has no way to reach orders that have aged out into a separate archive
-- database. store1_dev's archive now lives on its own host
-- (archive-db.example.internal), outside legacy-app entirely. These tables let
-- crm_dev hold a locally-queryable copy of that archived data, in the same
-- shape the live lookup already returns (SalesOrderInfoTO), so the frontend
-- can eventually fall back to this table when the live lookup comes back
-- empty.
--
-- Scope: store1_dev (the US storefront) only, for now -- source_system is
-- included below specifically so rows from the other 3 legacy per-storefront
-- archives (store3_dev, store4_dev, store2_dev), if/when they get migrated
-- the same way, can live in the same table without a schema change or any
-- ambiguity about which storefront a given archived order came from.
--
-- Why this file has NO data-load section (unlike
-- doc/0.2.0/migrate_tickets_to_crm_dev.sql):
-- That migration's 4 source DBs (store1_dev/store3_dev/store4_dev/
-- store2_dev) live on the SAME MySQL server as crm_dev (db-stage.example.internal),
-- so a single script could do real cross-database joins
-- (`INSERT INTO crm_dev.x SELECT ... FROM store1_dev.x JOIN ...`).
-- archive-db.example.internal is a genuinely separate physical server -- plain
-- SQL cannot join across it. The actual extraction also needs real
-- application logic that doesn't belong in SQL anyway: decoding
-- status/salesChannel/fulfillmentType from their raw int codes into the enum
-- NAME strings SalesOrderInfoTO expects (mirroring legacy-app's own
-- SalesOrderInfoService.groovy exactly, so archived and live orders render
-- identically), and assembling the nested lineItems/trackingNumbers JSON.
-- That belongs in a batched, resumable Java job with its own JDBC connection
-- to the archive host -- not in this file.
--
-- Why two tables, not the single flat table originally proposed: a sales
-- order's tracking numbers come from fulfillment_package
-- (sales_order_id -> tracking_number), which is one-to-many -- confirmed
-- against the real archive data, where the top orders by package count have
-- 150-350 distinct tracking numbers each. A single scalar tracking_no column
-- on one row per order would mean duplicating the entire sales_order_info
-- JSON blob once per tracking number (350x for the worst orders). Splitting
-- tracking numbers into their own child table avoids that entirely and
-- mirrors how fulfillment_package itself is already modeled (one row per
-- package/tracking number).
--
-- What's deliberately NOT here: customer and address. Neither contributes
-- any field to SalesOrderInfoTO (merchantOrderId, amazonOrderId, status,
-- salesChannel, fulfillmentType, datePurchased, dateShipped, grandTotalPrice,
-- shippingFee, taxFee, trackingNumbers, lineItems) or SalesOrderLineItemTO --
-- joining them would pull real cost (customer is 11G, address is 15G on the
-- archive) for data that has nowhere to go in the output shape.
--
-- sales_order_line_item on the archive host carries an extra `is_migrated`
-- column not present on live store1_dev. Checked against legacy-app source:
-- that field only exists on the Walmart module's SalesOrderLineItem.groovy,
-- not the Amazon one store1_dev actually backs -- it was dropped from the
-- live Amazon schema at some point after this archive snapshot was taken,
-- and is unrelated leftover, not something this migration needs to read or
-- gate on.
--
-- HOW TO RUN: execute this ENTIRE file as one script (DBeaver: "Execute SQL
-- Script", not "Execute SQL Statement" -- running the two CREATE TABLE
-- statements separately/out of order is exactly what produces
-- "SQL Error [1824]: Failed to open the referenced table
-- 'sales_order_archive'" on the second one, if the first hasn't actually
-- been committed yet). Both statements use IF NOT EXISTS, so it's also safe
-- to just re-run the whole file again if a previous attempt partially
-- succeeded.
-- ============================================================================

-- One row per archived sales order. sales_order_info holds the full
-- SalesOrderInfoTO JSON (everything except trackingNumbers, which lives in
-- the child table below) so a lookup hit can be deserialized straight into
-- that same DTO the live lookup already returns.
CREATE TABLE IF NOT EXISTS crm_dev.sales_order_archive (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_system VARCHAR(50) NOT NULL,
    source_sales_order_id BIGINT NOT NULL,
    amazon_order_id VARCHAR(255),
    sales_order_info JSON NOT NULL,
    date_created DATETIME NOT NULL,
    PRIMARY KEY (id),
    -- (source_system, source_sales_order_id) -- not id alone -- is what makes
    -- re-running the migration job idempotent (INSERT ... ON DUPLICATE KEY
    -- UPDATE against the archive's own sales_order.id), same purpose as
    -- uk_source_legacy on crm_dev.ticket in the 0.2.0 migration.
    UNIQUE KEY uk_source_system_sales_order_id (source_system, source_sales_order_id),
    KEY idx_amazon_order_id (amazon_order_id)
) ENGINE=InnoDB;

-- One row per tracking number per order (see design note above on why this
-- isn't a column on sales_order_archive).
CREATE TABLE IF NOT EXISTS crm_dev.sales_order_archive_tracking (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sales_order_archive_id BIGINT NOT NULL,
    tracking_no VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_tracking_no (tracking_no),
    KEY idx_sales_order_archive_id (sales_order_archive_id),
    CONSTRAINT fk_sales_order_archive_tracking_archive
        FOREIGN KEY (sales_order_archive_id) REFERENCES crm_dev.sales_order_archive (id)
) ENGINE=InnoDB;

-- Verification -- both rows must show up. If sales_order_archive_tracking is
-- missing, the FK failed (see the 1824 note above); if sales_order_archive
-- is missing too, neither statement ran.
SELECT TABLE_NAME, ENGINE, CREATE_TIME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'crm_dev' AND TABLE_NAME IN ('sales_order_archive', 'sales_order_archive_tracking');
