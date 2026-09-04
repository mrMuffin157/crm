package com.exampleco.crm.config.salesorder;

import com.exampleco.core.dto.customer.CustomerTO;
import com.exampleco.core.dto.order.OrderEventTO;
import com.exampleco.core.dto.order.SalesOrderInfoTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Pins both ends of the wire contract Sales Order page depends on: legacy-app's JSON must
// deserialize into SalesOrderInfoTO on the way in, and the field names the SPA reads must be the
// ones this app writes on the way out. There is no API versioning here (see CLAUDE.md), so a
// silent rename on either side breaks the deployed frontend immediately with nothing to catch it
// but this test.
//
// A default JsonMapper, matching the app's own, rather than a booted context — so this stays a
// plain unit test with no datasource like every other test in this tree. It needed a mixin module
// (config.salesorder.LegacyCoreJacksonConfig) until exampleco-core gave CustomerTO a no-arg
// constructor; both that class and the test guarding it are gone now that core deserializes on
// its own.
class SalesOrderInfoWireFormatTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final String LEGACY_APP_RESPONSE = """
            {
              "amazonOrderId": "111-0000000-0000002",
              "merchantOrderId": "111-0000000-0000002",
              "salesChannel": "AmazonBrandD",
              "reviewDisabled": true,
              "customerOrderCount": 5,
              "customer": {
                "id": "customer-1",
                "name": "Jane Buyer",
                "email": "buyer2@example.com",
                "reviewDisabled": true
              },
              "orderEvents": [
                {"eventType": "ReviewDisabled", "comment": "blocked by CS", "username": "jdoe",
                 "dateCreated": "2026-02-14T08:00:00.000+00:00"},
                {"eventType": "Refunded", "comment": "full refund", "username": null,
                 "dateCreated": "2026-02-15T08:00:00.000+00:00"}
              ]
            }
            """;

    // CustomerTO used to declare only CustomerTO(String name), which Jackson reads as a delegate
    // creator — every legacy-app response carrying a buyer failed outright until core added a no-arg
    // constructor alongside it. This keeps pinning that the nested buyer still round-trips, since
    // the Sales Order page's whole Buyer block and its review block depend on it.
    @Test
    void deserializesLegacyAppsNestedCustomer() {
        SalesOrderInfoTO order = mapper.readValue(LEGACY_APP_RESPONSE, SalesOrderInfoTO.class);

        CustomerTO customer = order.getCustomer();
        assertNotNull(customer);
        assertEquals("customer-1", customer.getId());
        assertEquals("Jane Buyer", customer.getName());
        assertEquals("buyer2@example.com", customer.getEmail());
        assertTrue(customer.isReviewDisabled());
    }

    @Test
    void deserializesTheOrdersOwnReviewFlagAndBuyerOrderCount() {
        SalesOrderInfoTO order = mapper.readValue(LEGACY_APP_RESPONSE, SalesOrderInfoTO.class);

        assertTrue(order.isReviewDisabled());
        assertEquals(5, order.getCustomerOrderCount());
    }

    // Order Events are rendered newest-first exactly as legacy-app hands them over — this app never
    // re-sorts them, so the list must survive the round trip in the order it arrived in.
    @Test
    void deserializesOrderEventsInTheOrderLegacyAppSentThem() {
        SalesOrderInfoTO order = mapper.readValue(LEGACY_APP_RESPONSE, SalesOrderInfoTO.class);

        assertEquals(2, order.getOrderEvents().size());
        OrderEventTO first = order.getOrderEvents().get(0);
        assertEquals("ReviewDisabled", first.getEventType());
        assertEquals("blocked by CS", first.getComment());
        assertEquals("jdoe", first.getUsername());
        assertNotNull(first.getDateCreated());
        // Written by a job rather than a person — the page renders this as "system".
        assertEquals(null, order.getOrderEvents().get(1).getUsername());
    }

    // The names SalesOrdersPage.jsx / CustomerView.jsx actually read. Note "reviewDisabled", NOT
    // "isReviewDisabled": Lombok names the accessor isReviewDisabled() for a field already
    // prefixed "is", and Jackson strips that prefix. Reading order.isReviewDisabled in the SPA
    // would be permanently undefined, i.e. a review block that never shows as applied.
    @Test
    void serializesTheFieldNamesTheFrontendReads() {
        SalesOrderInfoTO order = new SalesOrderInfoTO();
        order.setReviewDisabled(true);
        order.setCustomerOrderCount(5);
        CustomerTO customer = new CustomerTO("Jane Buyer");
        customer.setEmail("buyer2@example.com");
        customer.setReviewDisabled(true);
        order.setCustomer(customer);
        OrderEventTO event = new OrderEventTO();
        event.setEventType("ReviewDisabled");
        order.setOrderEvents(java.util.List.of(event));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(order));

        assertTrue(json.get("reviewDisabled").asBoolean());
        assertEquals(5, json.get("customerOrderCount").asInt());
        assertEquals("Jane Buyer", json.get("customer").get("name").asString());
        assertEquals("buyer2@example.com", json.get("customer").get("email").asString());
        assertTrue(json.get("customer").get("reviewDisabled").asBoolean());
        assertEquals("ReviewDisabled", json.get("orderEvents").get(0).get("eventType").asString());
    }
}
