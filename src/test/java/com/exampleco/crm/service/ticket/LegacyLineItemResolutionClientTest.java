package com.exampleco.crm.service.ticket;

import com.exampleco.crm.config.salesorder.LegacyApiProperties;
import com.exampleco.crm.service.salesorder.LegacyApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

// extractMessage() is the only pure, easily-unit-testable piece of this client (the rest is a
// live WebClient call to legacy-app — no mock legacy-app host to hit here, see this branch's own
// report). Covers the "showing legacy-app's message verbatim" rule across the response
// shapes legacy-app's own Grails REST error convention is likely to use, plus the non-JSON/blank
// fallback so a resolution failure is never silently turned into an empty 400.
class LegacyLineItemResolutionClientTest {

    // Constructing LegacyApiClient/LegacyLineItemResolutionClient here never makes a network
    // call: LegacyApiClient's own access-token exchange is a lazily-subscribed Mono, and this
    // test only ever calls extractMessage() directly, never resolve().
    private final LegacyApiProperties.Hosts hosts =
            new LegacyApiProperties.Hosts("https://us.example", "https://deco.example", "https://ca.example", "https://au.example",
                    null, null, null, null);
    private final LegacyApiProperties properties = new LegacyApiProperties("refresh-token", hosts);
    private final LegacyApiClient legacyApiClient = new LegacyApiClient(WebClient.builder(), properties);
    private final LegacyLineItemResolutionClient client =
            new LegacyLineItemResolutionClient(WebClient.builder(), properties, legacyApiClient);

    @Test
    void extractsTheMessageField() {
        assertEquals("SKU ABC-123 not found in the product catalog",
                client.extractMessage("{\"message\":\"SKU ABC-123 not found in the product catalog\"}"));
    }

    @Test
    void fallsBackToTheErrorFieldWhenThereIsNoMessageField() {
        assertEquals("Unauthorized", client.extractMessage("{\"error\":\"Unauthorized\"}"));
    }

    @Test
    void fallsBackToTheRawBodyWhenItIsNotJsonShapedAsExpected() {
        assertEquals("Internal Server Error", client.extractMessage("Internal Server Error"));
    }

    @Test
    void fallsBackToAGenericMessageForAnEmptyBody() {
        assertEquals("legacy-app rejected the line item resolution request", client.extractMessage(""));
    }

    @Test
    void fallsBackToAGenericMessageForANullBody() {
        assertEquals("legacy-app rejected the line item resolution request", client.extractMessage(null));
    }
}
