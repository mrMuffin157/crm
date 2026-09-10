package com.exampleco.crm.domain.ticket;

/**
 * Ticket.status/action/reason/refund_type moved from a numeric legacy code to the enum's own
 * {@code name()} string as the column's stored value
 * (doc/0.7.0/convert_ticket_status_action_reason_refund_type_to_string.sql, run against every
 * environment) — the converters in this package no longer need to tell the two forms apart.
 * TicketRepositoryImpl's status/action query-param normalization still does, though: legacy-app's
 * M2M search endpoint and the browser's ticketEnums.js both send the enum name now, but an
 * older cached CRM client or a saved bookmark from before this change can still send the
 * legacy numeric code, so this is the one place left that tells the two forms apart.
 */
public final class LegacyTicketCode {

    private LegacyTicketCode() {
    }

    public static boolean isNumeric(String value) {
        int start = value.startsWith("-") ? 1 : 0;
        if (start == value.length()) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
