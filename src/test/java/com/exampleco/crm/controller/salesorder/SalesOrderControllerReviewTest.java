package com.exampleco.crm.controller.salesorder;

import com.exampleco.core.dto.order.SalesOrderInfoTO;
import com.exampleco.crm.dto.salesorder.MarkCustomerReviewDisabledRequest;
import com.exampleco.crm.dto.salesorder.MarkOrderReviewDisabledRequest;
import com.exampleco.crm.service.salesorder.LegacyApiClient;
import com.exampleco.crm.service.salesorder.SalesOrderArchiveService;
import com.exampleco.crm.service.salesorder.SalesOrderCrmLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// review-disable and customer-orders endpoints. The point of interest is who gets
// recorded as having disabled a review: it must always be the authenticated session, never
// anything the caller could put in the request body.
class SalesOrderControllerReviewTest {

    private final LegacyApiClient legacyApiClient = mock(LegacyApiClient.class);
    private final SalesOrderArchiveService salesOrderArchiveService = mock(SalesOrderArchiveService.class);
    private final SalesOrderController controller = new SalesOrderController(
            legacyApiClient, salesOrderArchiveService, mock(SalesOrderCrmLinkService.class));

    private static Authentication sessionOf(String username) {
        return new UsernamePasswordAuthenticationToken(username, "n/a", List.of());
    }

    // The whole reason updateBy isn't part of MarkOrderReviewDisabledRequest: legacy-app's contract
    // carries it, but sourcing it from the body would let any signed-in agent record the action
    // against a colleague. It comes off the session and nowhere else.
    @Test
    void recordsTheSessionUserAsWhoDisabledTheOrdersReview() {
        when(legacyApiClient.markOrderReviewDisabled(anyString(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        ResponseEntity<Void> response = controller
                .markOrderReviewDisabled(new MarkOrderReviewDisabledRequest("az-us", "111-0000000-0000002"),
                        sessionOf("jdoe"))
                .block();

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(legacyApiClient).markOrderReviewDisabled("az-us", "111-0000000-0000002", "jdoe");
    }

    @Test
    void recordsTheSessionUserAsWhoDisabledTheCustomersReview() {
        when(legacyApiClient.markCustomerReviewDisabled(anyString(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        ResponseEntity<Void> response = controller
                .markCustomerReviewDisabled(new MarkCustomerReviewDisabledRequest("az-ca", "buyer@example.com"),
                        sessionOf("penny"))
                .block();

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(legacyApiClient).markCustomerReviewDisabled("az-ca", "buyer@example.com", "penny");
    }

    // A blank channel would otherwise reach LegacyApiClient and fail there as "no host for
    // source system 'null'" — a 502 blaming the integration for what is really a malformed
    // request. Rejected up front instead, and without calling legacy-app at all.
    @Test
    void rejectsAnOrderReviewRequestMissingItsChannelOrOrderNumber() {
        assertBadRequest(controller.markOrderReviewDisabled(
                new MarkOrderReviewDisabledRequest("  ", "111-0000000-0000002"), sessionOf("jdoe")).block());
        assertBadRequest(controller.markOrderReviewDisabled(
                new MarkOrderReviewDisabledRequest("az-us", null), sessionOf("jdoe")).block());

        verify(legacyApiClient, never()).markOrderReviewDisabled(any(), any(), any());
    }

    @Test
    void rejectsACustomerReviewRequestMissingItsChannelOrEmail() {
        assertBadRequest(controller.markCustomerReviewDisabled(
                new MarkCustomerReviewDisabledRequest(null, "buyer@example.com"), sessionOf("jdoe")).block());
        assertBadRequest(controller.markCustomerReviewDisabled(
                new MarkCustomerReviewDisabledRequest("az-us", ""), sessionOf("jdoe")).block());

        verify(legacyApiClient, never()).markCustomerReviewDisabled(any(), any(), any());
    }

    // A buyer NEITHER legacy-app nor the archive has seen is a 404, not a 200 with an empty array —
    // the Customer View has nothing to render for them, and the SPA distinguishes the two (see
    // lookupSalesOrdersByEmail's own null-on-404 contract).
    @Test
    void reportsAnUnknownBuyerAsNotFoundRatherThanAnEmptyList() {
        when(legacyApiClient.salesOrdersByEmail("nobody@example.com")).thenReturn(Mono.empty());
        when(salesOrderArchiveService.lookupByEmail("nobody@example.com")).thenReturn(Mono.empty());

        ResponseEntity<List<SalesOrderInfoTO>> response =
                controller.salesOrdersByEmail("nobody@example.com").block();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void returnsTheBuyersOrdersForAKnownEmail() {
        SalesOrderInfoTO order = new SalesOrderInfoTO();
        order.setAmazonOrderId("111-0000000-0000002");
        when(legacyApiClient.salesOrdersByEmail("buyer@example.com")).thenReturn(Mono.just(List.of(order)));
        when(salesOrderArchiveService.lookupByEmail("buyer@example.com")).thenReturn(Mono.empty());

        ResponseEntity<List<SalesOrderInfoTO>> response =
                controller.salesOrdersByEmail(" buyer@example.com ").block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("111-0000000-0000002", response.getBody().get(0).getAmazonOrderId());
    }

    // The bug this fallback exists for: a buyer whose every order has aged out of all 4 live
    // hosts is not a buyer legacy-app has never seen. Before this, the Sales Order page's own buyer
    // order-count link — only reachable from an order that was itself served from the archive —
    // opened the Customer View on a 404.
    @Test
    void fallsBackToTheArchiveForABuyerWhoseOrdersHaveAllAgedOut() {
        SalesOrderInfoTO archived = new SalesOrderInfoTO();
        archived.setAmazonOrderId("111-0000000-0000003");
        when(legacyApiClient.salesOrdersByEmail("aged.out@example.com")).thenReturn(Mono.empty());
        when(salesOrderArchiveService.lookupByEmail("aged.out@example.com"))
                .thenReturn(Mono.just(List.of(archived)));

        ResponseEntity<List<SalesOrderInfoTO>> response =
                controller.salesOrdersByEmail("aged.out@example.com").block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("111-0000000-0000003", response.getBody().get(0).getAmazonOrderId());
    }

    @Test
    void rejectsABlankEmailWithoutCallingLegacyApp() {
        assertBadRequest(controller.salesOrdersByEmail("   ").block());

        verify(legacyApiClient, never()).salesOrdersByEmail(any());
    }

    private static void assertBadRequest(ResponseEntity<?> response) {
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
