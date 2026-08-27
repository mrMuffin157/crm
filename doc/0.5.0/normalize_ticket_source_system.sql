-- ============================================================================
-- Normalize ticket-domain source_system values from legacy schema names to
-- legacy-app's own channel codes (BasicProfile.channel.channelCode).
--
-- Why this is a SEPARATE script, not an edit to doc/0.2.0's migration:
--   doc/0.2.0/migrate_tickets_to_crm_dev.sql already ran once (against crm_dev
--   / stage, using the _dev-suffixed source schemas) and wrote source_system
--   as the raw schema name (store1_dev, store2_dev, store4_dev, store3_dev)
--   -- see CLAUDE.md. That script is a historical, already-executed
--   doc/<version> artifact; editing it in place wouldn't change data that's
--   already landed, and it has not yet been run against production at all
--   (production's source schemas are plain store1/store2/store4/store3,
--   without the _dev suffix -- a separate, tracked prerequisite, not this
--   script's job). This script is deliberately environment-agnostic: it maps
--   BOTH the _dev-suffixed and un-suffixed spellings, so the exact same file
--   can be run today against crm_dev to fix the existing stage/dev rows, and
--   safely re-run later (a no-op) after the eventual production migration --
--   whichever schema-name spelling that run happens to have used.
--
-- Channel code mapping confirmed against legacy-app's own
-- web_legacy/grails-app/conf/application.yml per-storefront datasource blocks
-- (dataSource.url schema name -> channel.code):
--   store1(_dev) -> az-us      store2(_dev) -> az-deco
--   store4(_dev)  -> az-ca      store3(_dev) -> az-au
--
-- Idempotent: every UPDATE's CASE only rewrites rows still holding one of the
-- 8 known legacy spellings; rows already normalized to a channel code (or
-- carrying "crm", this app's own brand-new-ticket placeholder -- see
-- TicketController.SOURCE_SYSTEM) fall through the ELSE and are left alone.
-- Safe to run multiple times and safe to run against a database that only
-- has some of the 4 channels' data.
--
-- Covers every ticket-domain table that carries this column: ticket,
-- ticket_comment, ticket_line_item, address, and ticket_report (its
-- source_system is the same migration-era tag, even though ticket_report
-- itself isn't taken over by CRM until a later step).
-- ============================================================================

USE crm_dev;

-- Preview -- run this alone first and confirm the values you expect to see.
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
-- tickets -- not yet a real channel; see TicketController.SOURCE_SYSTEM and
-- the create-form channel picker this still depends on). Any other
-- value means a legacy spelling this script doesn't know about yet.
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
