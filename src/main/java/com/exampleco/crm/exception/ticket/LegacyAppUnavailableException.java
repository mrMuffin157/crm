package com.exampleco.crm.exception.ticket;

// Thrown when a call to legacy-app's M2M API fails for a reason OTHER than legacy-app explicitly
// rejecting the request (connection refused, timeout, 5xx, malformed response) — kept distinct
// from LineItemResolutionException (legacy-app said "this SKU is invalid", a 400 the CS agent must
// act on) so a caller can tell "legacy-app said no" apart from "legacy-app integration isn't reachable
// right now" (502, an ops/infra problem, not something the agent can fix by editing the ticket).
public class LegacyAppUnavailableException extends RuntimeException {
    public LegacyAppUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
