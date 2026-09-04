package com.exampleco.crm.dto.salesorder;

// Body of CRM's own POST /api/ticketApi/markReviewDisabled. Deliberately narrower than legacy-app's
// contract for the same path: legacy-app takes {orderNumber, updateBy}, but updateBy is filled in
// server-side from the caller's session rather than accepted here — see SalesOrderController's
// own comment on why. sourceSystem is CRM-only, naming which of the 4 storefronts owns the
// order so the call can be routed to that one host instead of fanned out at all of them.
public record MarkOrderReviewDisabledRequest(String sourceSystem, String orderNumber) {
}
