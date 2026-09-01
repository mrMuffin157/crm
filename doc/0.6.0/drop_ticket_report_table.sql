-- TicketReport domain removed: the nightly ticket_report snapshot table is replaced by an
-- on-demand CSV export (TicketController#exportTicketReport / TicketReportCsvService), so the
-- table itself is no longer written to or read from.
DROP TABLE crm_dev.ticket_report;
