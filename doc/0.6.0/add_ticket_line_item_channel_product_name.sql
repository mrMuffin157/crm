-- the buyer-facing "replacement shipped" mail's line-item block needs each row's
-- channel-catalog product name (legacy-app's own two close-path copies each look this up
-- differently — see ReplacementShippedMailService's own comment). Nullable, no backfill:
-- pre-existing rows genuinely don't know their channel product name and there is no local
-- source for one. They repopulate naturally, since TicketController#persistLineItems deletes
-- and re-inserts a ticket's whole line-item set on every save.
--
-- Run BEFORE deploying the jar: findByTicketIdIn is a derived query that SELECTs every mapped
-- column, so a jar mapping a column the table lacks breaks every ticket_line_item read (ticket
-- detail, picking-list PDF, TicketReportGenerationService), not just the mail.
ALTER TABLE crm_dev.ticket_line_item
    ADD COLUMN channel_product_name VARCHAR(255) NULL AFTER name;
