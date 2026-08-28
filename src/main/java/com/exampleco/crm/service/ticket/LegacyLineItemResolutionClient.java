package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.ticket.ResolveLineItemsRequestTO;
import com.exampleco.core.dto.ticket.ResolveLineItemsResultTO;
import com.exampleco.core.dto.ticket.ResolvedLineItemTO;
import com.exampleco.core.dto.ticket.TicketLineItemInputTO;
import com.exampleco.crm.config.salesorder.LegacyApiProperties;
import com.exampleco.crm.exception.ticket.LineItemResolutionException;
import com.exampleco.crm.exception.ticket.LegacyAppUnavailableException;
import com.exampleco.crm.service.salesorder.LegacyApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Calls legacy-app's own POST /api/ticketApi/resolveLineItems ("make CRM's create/edit
// form call back into legacy-app for the product/inventory/sales-order data CRM does not hold") to
// resolve each non-Refund line item's internal `sku` into the seller/channel SKU (Amazon listing
// SKU) TicketLineItem#channelSku stores — CRM has no product/inventory catalog of its own to do
// this lookup locally (see that field's own comment). Per-channel: a ticket's own sourceSystem
// already says which single legacy-app host to call, so this is never fanned out to all 4 hosts the
// way LegacyApiClient's Sales Order lookup is.
//
// The request/response TOs are legacy-app's own, taken straight from the exampleco-core submodule
// (com.exampleco.core.dto.ticket, 5.4.7) rather than hand-copied on this side — unlike
// SalesOrderInfoTO's deliberate hand-written copies, these types already ship in the version of
// core this build composes in, so a copy here would just be a second definition of the same
// contract free to drift from legacy-app's.
//
// Unlike ReplacementShippedMailService's own swallow-and-log publish (a broker/publish failure
// there must never fail the close), this call's result is load-bearing and its failure is never
// tolerated: TicketController#saveLineItems blocks the whole
// ticket save on it (a resolution failure surfaces as a 400 with legacy-app's own message, per
// LineItemResolutionException) rather than persisting a line item with a null/stale channelSku.
// Its caller already runs on Schedulers.boundedElastic() (createTicket/updateTicket's blocking
// JPA callable), so resolve() is deliberately blocked on there rather than threaded further
// through as a Mono — the same reasoning CLAUDE.md's reactive-shell rule uses to justify
// boundedElastic in the first place: a dedicated thread pool is exactly where blocking on a
// synchronous HTTP round-trip belongs, unlike the Netty event loop.
@Component
public class LegacyLineItemResolutionClient {

    private static final Logger log = LoggerFactory.getLogger(LegacyLineItemResolutionClient.class);

    private static final String RESOLVE_PATH = "/api/ticketApi/resolveLineItems";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final LegacyApiProperties.Hosts hosts;
    private final LegacyApiClient legacyApiClient;

    public LegacyLineItemResolutionClient(WebClient.Builder webClientBuilder, LegacyApiProperties properties,
                                           LegacyApiClient legacyApiClient) {
        this.webClient = webClientBuilder.build();
        this.hosts = properties.hosts();
        this.legacyApiClient = legacyApiClient;
    }

    // Resolves every given line item against the legacy-app host for `sourceSystem`, returning the
    // results keyed by each item's own `sku` (see ResolvedLineItemTO's own comment) so the
    // caller can map them back onto its own line items regardless of legacy-app's response order.
    // `action` is the TicketAction name: legacy-app resolves a line item by different rules per
    // action (see TicketLineItemInputTO), so it is part of the request rather than something
    // legacy-app could infer from the items alone.
    // Two distinct failure modes, never silent: LineItemResolutionException (400) when legacy-app was
    // reached and explicitly rejected the request (its own message preserved verbatim) or has no
    // channel host mapped for this sourceSystem at all; LegacyAppUnavailableException (502) for
    // everything that means legacy-app itself couldn't be reached or answered (timeout, connection
    // refused, a malformed response body) — an ops/infra problem, not something the CS agent can
    // fix by editing the ticket, so it must not look like the former.
    public Mono<Map<String, ResolvedLineItemTO>> resolve(String sourceSystem, String action, String orderNumber,
                                                          List<TicketLineItemInputTO> lineItems) {
        if (lineItems.isEmpty()) {
            return Mono.just(Map.of());
        }
        String host = hosts.hostForSourceSystem(sourceSystem);
        if (host == null) {
            return Mono.error(new LineItemResolutionException(
                    "Cannot resolve line item SKUs: no legacy-app channel host for source system '" + sourceSystem + "'"));
        }
        return legacyApiClient.accessToken()
                .flatMap(token -> resolveOnHost(host, token, action, orderNumber, lineItems))
                .map(this::indexBySku)
                .onErrorMap(ex -> !(ex instanceof LineItemResolutionException) && !(ex instanceof LegacyAppUnavailableException),
                        ex -> {
                            log.warn("Line item resolution against {} failed: {}", host, ex.toString());
                            return new LegacyAppUnavailableException(
                                    "Failed to reach legacy-app to resolve line item SKUs: " + ex.getMessage(), ex);
                        });
    }

    private Mono<ResolveLineItemsResultTO> resolveOnHost(String host, String token, String action, String orderNumber,
                                                          List<TicketLineItemInputTO> lineItems) {
        log.info("Calling legacy-app API host={} action=resolveLineItems orderNumber={}", host, orderNumber);
        return webClient.post()
                .uri(host + RESOLVE_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(resolveRequest(action, orderNumber, lineItems))
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(ResolveLineItemsResultTO.class);
                    }
                    // A 5xx here is the reverse proxy in front of legacy-app (or legacy-app itself)
                    // failing, never legacy-app's own app explicitly rejecting the request — same
                    // "ops/infra problem" bucket as a connection refused/timeout, not a 400 (see
                    // LegacyAppUnavailableException's own comment, which already documented "5xx"
                    // as belonging here even though this code never checked for it).
                    if (response.statusCode().is5xxServerError()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new LegacyAppUnavailableException(
                                        "legacy-app responded with " + response.statusCode().value() + ": "
                                                + extractMessage(body), null)));
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new LineItemResolutionException(extractMessage(body))));
                })
                .timeout(REQUEST_TIMEOUT);
    }

    // exampleco-core's TOs are plain beans (Lombok @Getter/@Setter), not records, so the request
    // is populated field by field rather than through a constructor.
    private ResolveLineItemsRequestTO resolveRequest(String action, String orderNumber,
                                                      List<TicketLineItemInputTO> lineItems) {
        ResolveLineItemsRequestTO request = new ResolveLineItemsRequestTO();
        request.setAction(action);
        request.setOrderNumber(orderNumber);
        request.setItems(lineItems);
        return request;
    }

    // ResolveLineItemsResultTO#warnings are legacy-app's own "the agent should look at this before
    // saving" prompts and never block the save (see that TO's own comment). CRM has no place to
    // show them — TicketWriteRequest's response shape is the saved Ticket, and adding a field to
    // it would break the deployed frontend (CLAUDE.md: no API versioning) — so they are logged
    // rather than dropped without trace.
    private Map<String, ResolvedLineItemTO> indexBySku(ResolveLineItemsResultTO result) {
        if (result.getWarnings() != null && !result.getWarnings().isEmpty()) {
            log.info("legacy-app returned line item resolution warnings: {}", result.getWarnings());
        }
        if (result.getItems() == null) {
            return Map.of();
        }
        return result.getItems().stream()
                .filter(item -> item.getSku() != null)
                .collect(Collectors.toMap(ResolvedLineItemTO::getSku, item -> item, (first, second) -> second));
    }

    // legacy-app's Grails REST error responses are commonly shaped {"message": "..."}, sometimes
    // {"error": "..."} — falls back to the raw body (or a generic message for a genuinely empty
    // one) when it's neither, so a resolution failure is never silently turned into a blank 400.
    // Uses Spring Boot's own minimal JsonParser (already on the classpath via the webflux/
    // webclient starters) rather than adding a direct jackson-databind dependency just for this
    // one best-effort parse. Package-private so a unit test can exercise this pure parsing logic
    // directly.
    String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "legacy-app rejected the line item resolution request";
        }
        try {
            Map<String, Object> parsed = JsonParserFactory.getJsonParser().parseMap(body);
            Object message = parsed.get("message") != null ? parsed.get("message") : parsed.get("error");
            if (message != null) {
                return message.toString();
            }
        } catch (RuntimeException ignored) {
            // Not JSON, or not shaped as expected (JsonParseException, or any other parse-time
            // RuntimeException) - fall through to the raw body.
        }
        return body;
    }
}
