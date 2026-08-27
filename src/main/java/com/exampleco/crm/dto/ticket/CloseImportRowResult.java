package com.exampleco.crm.dto.ticket;

// One CSV row's outcome from TicketCloseImportService. rowNumber is 1-based and counts the
// header row, matching what a person editing the CSV in a spreadsheet would call that row, so
// an error message here can be matched back to the file directly. ticketId is null when the
// row's own Custom - Field 2 couldn't even be parsed.
//
// Three outcomes, not two: the real vendor export mixes ticket rows in with label
// rows for other channels/order types (see TicketCloseImportService's row filter) — those
// non-ticket rows are expected and should read as SKIPPED, not FAILURE, so a CS user uploading
// the raw export sees "248 closed, 37 not ticket rows" rather than 37 red errors.
public record CloseImportRowResult(int rowNumber, Long ticketId, Outcome outcome, String message) {

    public enum Outcome {
        SUCCESS, FAILURE, SKIPPED
    }
}
