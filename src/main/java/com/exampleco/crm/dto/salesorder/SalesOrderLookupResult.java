package com.exampleco.crm.dto.salesorder;

import com.exampleco.core.dto.order.SalesOrderInfoTO;

// Wraps the shared exampleco-core SalesOrderInfoTO (never modify that TO directly -- legacy-app
// shares it) with an optional link to a crm_dev ticket already created for this same order/
// tracking number, and that ticket's own real (now migrated, no longer placeholder) linked
// customer/address rows -- see SalesOrderCrmLinkService. This is additive: order's own live
// buyer/ship-to fields are untouched and remain the source of truth for the live lookup itself.
public record SalesOrderLookupResult(
        SalesOrderInfoTO order,
        CrmLink crmLink
) {

    // ticketId/customerName/customerEmail/address are all null when no ticket exists yet for
    // this order/tracking number -- EMPTY is the shared "no match" instance for that case.
    public record CrmLink(
            Long ticketId,
            String customerName,
            String customerEmail,
            CrmAddress address
    ) {
        public static final CrmLink EMPTY = new CrmLink(null, null, null, null);
    }

    public record CrmAddress(
            String name,
            String phone,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String zipCode,
            String countryCode
    ) {
    }
}
