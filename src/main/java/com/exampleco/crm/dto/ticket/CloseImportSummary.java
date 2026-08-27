package com.exampleco.crm.dto.ticket;

import java.util.List;

// Response shape for POST /api/tickets/close-import — a partial-success summary rather than an
// all-or-nothing result: one bad row (a typo'd ticket id, a ticket that's already Closed)
// shouldn't block every other row in the same file from closing, and a non-ticket row in the
// vendor export (see CloseImportRowResult.Outcome) shouldn't count against either total.
public record CloseImportSummary(int totalRows, int successCount, int failureCount, int skippedCount,
                                  List<CloseImportRowResult> rows) {
}
