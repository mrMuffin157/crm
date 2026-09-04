package com.exampleco.crm.exception.salesorder;

// Thrown when legacy-app was reached and explicitly refused a markReviewDisabled call (a 4xx — no
// such order, no such customer, review already blocked), as opposed to legacy-app being unreachable
// or erroring. Same split, and for the same reason, as the ticket side's
// LineItemResolutionException vs. LegacyAppUnavailableException: "legacy-app said no" is something the
// CS agent can act on and must see legacy-app's own wording for (400), while "the integration is
// down" is an ops problem the agent can do nothing about (502).
public class ReviewSettingRejectedException extends RuntimeException {
    public ReviewSettingRejectedException(String message) {
        super(message);
    }
}
