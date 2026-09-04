-- added ticket_line_item.channel_product_name (add_ticket_line_item_channel_product_name.sql)
-- but missed its Envers audit shadow table, ticket_line_item_aud (create_ticket_audit_tables.sql) --
-- TicketLineItem#channelProductName is @Audited, so Envers tries to insert this column into the
-- _aud table on every flush and fails with "Unknown column 'channel_product_name'" (SQLGrammarException),
-- 500ing every ticket create/edit that persists line items (Replacement/ManualHandling/Refund).
--
-- Run BEFORE deploying a jar built after this fix, same reasoning as the original migration.
ALTER TABLE crm_dev.ticket_line_item_aud
    ADD COLUMN channel_product_name VARCHAR(255) NULL AFTER name;
