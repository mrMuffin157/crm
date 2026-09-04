package com.exampleco.crm.dto.salesorder;

// Body of CRM's own POST /api/customerApi/markReviewDisabled — the customer-level counterpart
// of MarkOrderReviewDisabledRequest, with the same updateBy and sourceSystem rules.
public record MarkCustomerReviewDisabledRequest(String sourceSystem, String email) {
}
