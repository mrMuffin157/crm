package com.exampleco.crm.service.salesorder;

import com.exampleco.crm.config.salesorder.LegacyApiProperties;
import com.exampleco.crm.exception.ticket.LegacyAppUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Covers the two pieces of markReviewDisabled path that don't need a live legacy-app:
// which host the call is routed to, and how legacy-app's own refusal message is surfaced. The
// request itself is a WebClient call with no mock host to point at, the same limitation
// LegacyLineItemResolutionClientTest documents for its own client.
class LegacyApiClientReviewTest {

    // Only us/deco/ca/au are configured, exactly as in every deployed profile — the other four
    // "az-*" channels have no legacy-app host yet (see LegacyApiProperties.Hosts).
    private final LegacyApiProperties.Hosts hosts =
            new LegacyApiProperties.Hosts("https://us.example", "https://deco.example",
                    "https://ca.example", "https://au.example", null, null, null, null);
    private final LegacyApiClient client =
            new LegacyApiClient(WebClient.builder(), new LegacyApiProperties("refresh-token", hosts));

    // A channel with no host configured can't be routed anywhere, and that is an integration gap
    // (502), never a rejection the agent could act on (400) — so it must not surface as the
    // latter. Fails before any network call, which is what makes it unit-testable at all.
    @Test
    void refusesToDisableOrderReviewForAChannelWithNoHostConfigured() {
        LegacyAppUnavailableException thrown = assertThrows(LegacyAppUnavailableException.class,
                () -> client.markOrderReviewDisabled("az-mx", "111-0000000-0000002", "jdoe").block());

        assertTrue(thrown.getMessage().contains("az-mx"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("111-0000000-0000002"), thrown.getMessage());
    }

    @Test
    void refusesToDisableCustomerReviewForAChannelWithNoHostConfigured() {
        LegacyAppUnavailableException thrown = assertThrows(LegacyAppUnavailableException.class,
                () -> client.markCustomerReviewDisabled("az-brande-au", "buyer@example.com", "jdoe").block());

        assertTrue(thrown.getMessage().contains("buyer@example.com"), thrown.getMessage());
    }

    // "crm" is TicketController's own placeholder sourceSystem, not a real Amazon channel — it
    // maps to no host at all, and must be rejected the same way rather than fanned out blindly.
    @Test
    void refusesToDisableReviewForANonChannelSourceSystem() {
        assertThrows(LegacyAppUnavailableException.class,
                () -> client.markOrderReviewDisabled("crm", "111-0000000-0000002", "jdoe").block());
    }

    @Test
    void extractsLegacyAppsOwnMessageFromARejection() {
        assertEquals("No sales order found for 111-0000000-0000002",
                LegacyApiClient.extractMessage("{\"message\":\"No sales order found for 111-0000000-0000002\"}", 404));
    }

    @Test
    void fallsBackToTheErrorFieldWhenThereIsNoMessageField() {
        assertEquals("Review already disabled",
                LegacyApiClient.extractMessage("{\"error\":\"Review already disabled\"}", 400));
    }

    // A Grails HTML error page or an empty body carries nothing worth showing — the status is
    // still more actionable than a blank message.
    @Test
    void fallsBackToTheStatusForANonJsonBody() {
        assertEquals("HTTP 502", LegacyApiClient.extractMessage("<html>Bad Gateway</html>", 502));
    }

    @Test
    void fallsBackToTheStatusForAnEmptyOrNullBody() {
        assertEquals("HTTP 404", LegacyApiClient.extractMessage("", 404));
        assertEquals("HTTP 404", LegacyApiClient.extractMessage(null, 404));
    }
}
