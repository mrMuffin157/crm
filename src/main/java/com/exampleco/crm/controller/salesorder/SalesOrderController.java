package com.exampleco.crm.controller.salesorder;

import com.exampleco.core.dto.order.SalesOrderInfoTO;
import com.exampleco.crm.dto.salesorder.MarkCustomerReviewDisabledRequest;
import com.exampleco.crm.dto.salesorder.MarkOrderReviewDisabledRequest;
import com.exampleco.crm.dto.salesorder.SalesOrderLineTO;
import com.exampleco.crm.dto.salesorder.SalesOrderLookupResult;
import com.exampleco.crm.exception.salesorder.ReviewSettingRejectedException;
import com.exampleco.crm.exception.ticket.LegacyAppUnavailableException;
import com.exampleco.crm.service.salesorder.LegacyApiClient;
import com.exampleco.crm.service.salesorder.SalesOrderArchiveService;
import com.exampleco.crm.service.salesorder.SalesOrderCrmLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

// A per-request lookup by exact Amazon order id or tracking number, not a browsable/searchable
// list. Checks 5 places in order: legacy-app's 4 live storefront hosts first (LegacyApiClient,
// fanned out in parallel), then this app's own local sales_order_archive table
// (SalesOrderArchiveService) as a fallback for orders that have aged out of all 4 live hosts --
// see doc/0.5.0/create_sales_order_archive.sql for how that table gets populated. The fallback
// only triggers on an empty live result, never on a live lookup failure (WebClientException
// below) -- if legacy-app itself is erroring, silently serving a possibly-stale archive hit instead
// would be a surprising, hidden behavior change.
//
// salesOrdersByEmail() below follows the identical live-then-archive shape, so the Customer
// View reached from an archived order's buyer count has something to render; see
// doc/0.7.0/add_sales_order_archive_buyer_email.sql for the indexed buyer_email that makes
// querying this table by buyer possible at all.
@RestController
@CrossOrigin(origins = "${app.cors.allowed-origins}")
public class SalesOrderController {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderController.class);

    private final LegacyApiClient legacyApiClient;
    private final SalesOrderArchiveService salesOrderArchiveService;
    private final SalesOrderCrmLinkService salesOrderCrmLinkService;

    public SalesOrderController(LegacyApiClient legacyApiClient, SalesOrderArchiveService salesOrderArchiveService,
                                 SalesOrderCrmLinkService salesOrderCrmLinkService) {
        this.legacyApiClient = legacyApiClient;
        this.salesOrderArchiveService = salesOrderArchiveService;
        this.salesOrderCrmLinkService = salesOrderCrmLinkService;
    }

    @GetMapping(value = "/api/salesApi/getSalesOrderInfo", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<SalesOrderLookupResult>> lookup(
            @RequestParam(required = false) String amazonOrderId,
            @RequestParam(required = false) String trackingNumber) {
        String normalizedOrderId = blankToNull(amazonOrderId);
        String normalizedTrackingNumber = blankToNull(trackingNumber);

        if ((normalizedOrderId == null) == (normalizedTrackingNumber == null)) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
        }

        return legacyApiClient.lookup(normalizedOrderId, normalizedTrackingNumber)
                .switchIfEmpty(salesOrderArchiveService.lookup(normalizedOrderId, normalizedTrackingNumber))
                // Applied to the live and the archived hit alike, deliberately: the archive
                // never stores customerOrderCount (it would be stale the moment it was written),
                // so an archived order arrives with a primitive-int 0 and this is the only thing
                // that fills it in, while a live order arrives with legacy-app's live count and this
                // adds the aged-out orders legacy-app can no longer see. See
                // SalesOrderArchiveService#addArchivedOrderCount.
                .flatMap(salesOrderArchiveService::addArchivedOrderCount)
                .flatMap(order -> salesOrderCrmLinkService.lookup(normalizedOrderId, normalizedTrackingNumber)
                        .map(crmLink -> new SalesOrderLookupResult(order, crmLink)))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // Backs the Refund ticket action's line-item picker — an order number alone
    // doesn't say which of the 4 storefronts owns it, same reasoning as lookup() above, so
    // this is proxied through LegacyApiClient's own fan-out rather than a per-channel call.
    @GetMapping(value = "/api/ticketApi/salesOrderLines", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<List<SalesOrderLineTO>>> salesOrderLines(@RequestParam String orderNumber) {
        String normalizedOrderNumber = blankToNull(orderNumber);
        if (normalizedOrderNumber == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
        }

        return legacyApiClient.salesOrderLines(normalizedOrderNumber)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // Customer View: every order this buyer has placed, so an agent handling one
    // ticket can see whether they are a repeat customer and what else they have bought without
    // leaving CRM. Proxied through LegacyApiClient's own fan-out for the same reason lookup()
    // above is — an email says no more about which storefront owns the buyer than an order
    // number does. A buyer with no orders anywhere is a 404, not an empty list: the Customer
    // View has nothing to render for an email legacy-app has never seen.
    //
    // Falls back to the local archive on an empty live result, exactly as lookup() does and for
    // exactly the same reason: a buyer whose every order has aged out of all 4 hosts is not a
    // buyer legacy-app has never seen. Without this the Sales Order page's own buyer-order-count
    // link — which is only reachable from an order that was itself served from the archive —
    // opened the Customer View on a 404. And as with lookup(), an empty live result is the only
    // trigger: a failure upstream of the fan-out throws (see handleUpstreamFailure below) rather
    // than quietly substituting archive data for a legacy-app that is merely erroring.
    @GetMapping(value = "/api/salesApi/getSalesOrderInfoListByEmail", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<List<SalesOrderInfoTO>>> salesOrdersByEmail(@RequestParam String email) {
        String normalizedEmail = blankToNull(email);
        if (normalizedEmail == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
        }

        return legacyApiClient.salesOrdersByEmail(normalizedEmail)
                .switchIfEmpty(salesOrderArchiveService.lookupByEmail(normalizedEmail))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // Blocks Amazon's review solicitation for this one order. CRM holds no review data of its
    // own — this is purely a proxy onto legacy-app, which owns that pipeline.
    //
    // updateBy is taken from the authenticated session, never from the request body, even though
    // legacy-app's own contract carries it as a field: it is the audit trail for who suppressed the
    // review, and a body-supplied value would let any signed-in agent record the action against
    // a colleague. sourceSystem comes from the body because it identifies which storefront owns
    // the order (see LegacyApiClient#markOrderReviewDisabled on why this is routed rather than
    // fanned out), and the caller is reading it straight off the sales order it is acting on.
    @PostMapping(value = "/api/ticketApi/markReviewDisabled", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> markOrderReviewDisabled(@RequestBody MarkOrderReviewDisabledRequest request,
                                                                Authentication authentication) {
        String orderNumber = blankToNull(request.orderNumber());
        String sourceSystem = blankToNull(request.sourceSystem());
        if (orderNumber == null || sourceSystem == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
        }

        return legacyApiClient.markOrderReviewDisabled(sourceSystem, orderNumber, authentication.getName())
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    // The customer-level counterpart: blocks review solicitation for every order this buyer
    // places, not just the one in front of the agent. Same updateBy/sourceSystem rules as above.
    @PostMapping(value = "/api/customerApi/markReviewDisabled", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> markCustomerReviewDisabled(@RequestBody MarkCustomerReviewDisabledRequest request,
                                                                   Authentication authentication) {
        String email = blankToNull(request.email());
        String sourceSystem = blankToNull(request.sourceSystem());
        if (email == null || sourceSystem == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
        }

        return legacyApiClient.markCustomerReviewDisabled(sourceSystem, email, authentication.getName())
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    // legacy-app was reached and refused the review block (no such order/customer, already
    // blocked) — its own wording is what the agent needs to see, so it is passed through as a
    // 400 rather than flattened into a generic failure. See ReviewSettingRejectedException.
    @ExceptionHandler(ReviewSettingRejectedException.class)
    public ResponseEntity<Map<String, String>> handleReviewSettingRejected(ReviewSettingRejectedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    // The other half of that split: legacy-app itself couldn't be reached or answered (timeout, 5xx,
    // no host configured for the order's channel) — an ops problem, not something the agent can
    // fix, so it must not look like a rejection. Mirrors TicketController's own handler for the
    // same exception.
    @ExceptionHandler(LegacyAppUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleLegacyAppUnavailable(LegacyAppUnavailableException e) {
        log.error("Disabling review couldn't reach legacy-app", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    // LegacyApiClient already treats a single down/erroring storefront host as "not found
    // there" (see its per-host onErrorResume) — this only catches a failure upstream of that
    // fan-out, i.e. the Bearer token exchange itself failing (bad/expired refresh token, or the
    // token host unreachable). Without this, that error reached the client as a raw 500 with
    // legacy-app's internal hostname and a full stack trace in the body.
    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<Map<String, String>> handleUpstreamFailure(WebClientException e) {
        log.error("Sales order lookup couldn't reach legacy-app", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Sales order lookup is temporarily unavailable"));
    }
}
