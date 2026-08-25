package com.exampleco.crm.service.salesorder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.exampleco.core.dto.order.SalesOrderInfoTO;
import com.exampleco.core.dto.ticket.SalesOrderLineOptionTO;
import com.exampleco.core.dto.ticket.SalesOrderLinesResultTO;
import com.exampleco.crm.config.salesorder.LegacyApiProperties;
import com.exampleco.crm.dto.salesorder.SalesOrderLineTO;
import com.exampleco.crm.exception.salesorder.ReviewSettingRejectedException;
import com.exampleco.crm.exception.ticket.LegacyAppUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

// Fans a single sales-order lookup out to all 4 legacy-app storefront hosts in parallel and returns
// the first real match — legacy-app's own getSalesOrderInfo endpoint is a single-record lookup by
// exact Amazon order id or tracking number, not a query API. WebClient calls here are already
// non-blocking, so — unlike the JPA/PDFBox work elsewhere in this app — none of this needs
// Schedulers.boundedElastic() wrapping.
//
// accessToken() is also reused by service.ticket.LegacyLineItemResolutionClient to
// authenticate its own outbound calls to legacy-app's /api/ticketApi/* — same signing issuer behind
// all 4 brands (see the accessToken field's own comment), so there is exactly one
// token-fetch-and-cache to share rather than a second copy of this same refresh-token exchange.
// (The buyer-facing replacement-shipped mail never calls legacy-app at all — it goes out
// over RabbitMQ instead.)
@Component
public class LegacyApiClient {

    private static final Logger log = LoggerFactory.getLogger(LegacyApiClient.class);

    // legacy-app's own doc comment on SalesApiController#getSalesOrderInfo says "/api/sales/...",
    // but that's stale/wrong: Grails derives the URL's controller segment from the class name
    // minus "Controller" ("SalesApiController" -> "salesApi"), and static namespace = "api"
    // only controls the leading "/api/" segment, not that derivation. Confirmed against a real
    // stage host: "/api/sales/..." 404s with the Grails app's generic HTML error page (never
    // reaches the controller at all); "/api/salesApi/..." hits the real controller and returns
    // its actual JSON 400/404/200 bodies.
    private static final String LOOKUP_PATH = "/api/salesApi/getSalesOrderInfo";
    // Refund line-item picker — same "which of the 4 storefronts owns this order"
    // problem as LOOKUP_PATH above, since an order number alone doesn't say which host to ask.
    // Now live on legacy-app (confirmed against the US storefront as of exampleco-core 5.4.7), responding with
    // core's own SalesOrderLinesResultTO — see SalesOrderLineTO's own comment.
    private static final String SALES_ORDER_LINES_PATH = "/api/ticketApi/salesOrderLines";
    // Customer View — every sales order one buyer has ever placed, keyed by their
    // email. Note the "salesApi" segment, not the "sales" the ticket's own spec writes: Grails
    // derives that segment from SalesApiController's class name, exactly as LOOKUP_PATH's
    // comment above records after confirming both spellings against a real stage host.
    private static final String ORDERS_BY_EMAIL_PATH = "/api/salesApi/getSalesOrderInfoListByEmail";
    // The two review blocks an agent can apply from CRM. Both live on legacy-app, which
    // owns the review pipeline these suppress — CRM has no review data of its own to update.
    private static final String ORDER_MARK_REVIEW_DISABLED_PATH = "/api/ticketApi/markReviewDisabled";
    private static final String CUSTOMER_MARK_REVIEW_DISABLED_PATH = "/api/customerApi/markReviewDisabled";
    private static final String TOKEN_PATH = "/oauth/access_token";

    // access_token is a real JWT (legacy-app's JwtClientFilter signs it) — resolveTtl() below reads
    // its own "exp" claim so the cache TTL always matches the token actually issued. This is only
    // the fallback for a token that doesn't decode (matches legacy-app's own JwtClientFilter default).
    private static final Duration TOKEN_TTL = Duration.ofSeconds(3600);
    // Refresh a little before the token's real expiry rather than exactly at it, so an in-flight
    // request never races a token that expires mid-call.
    private static final Duration EXPIRY_SAFETY_MARGIN = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ParameterizedTypeReference<List<SalesOrderInfoTO>> SALES_ORDER_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final List<String> hosts;
    private final LegacyApiProperties.Hosts hostsByChannel;
    private final String refreshToken;
    private final Mono<String> accessToken;

    public LegacyApiClient(WebClient.Builder webClientBuilder, LegacyApiProperties properties) {
        this.webClient = webClientBuilder.build();
        this.hosts = List.of(properties.hosts().us(), properties.hosts().deco(),
                properties.hosts().ca(), properties.hosts().au());
        this.hostsByChannel = properties.hosts();
        this.refreshToken = properties.refreshToken();
        // legacy-app's JwtClientFilter caches its access token only by serviceId, not by which host
        // issued it — the same signing issuer sits behind all 4 brands, so one exchange (against
        // any single host) is reused for calls to all 4. cache()'s error/empty TTL of ZERO means
        // a failed exchange isn't stuck being replayed for the next hour; the next lookup just
        // retries the exchange.
        this.accessToken = fetchAccessToken()
                .cache(LegacyApiClient::resolveTtl, error -> Duration.ZERO, () -> Duration.ZERO);
    }

    // Exposes the cached token for other clients calling other legacy-app APIs (see this class's own
    // comment) — same Mono every caller of lookup() below already shares, so a second caller adds
    // no extra token-exchange traffic.
    public Mono<String> accessToken() {
        return accessToken;
    }

    // Exactly one of amazonOrderId/trackingNumber must be non-null — mirrors legacy-app's own
    // SalesApiController#getSalesOrderInfo validation. Empty if no host has the order, or the
    // host that had it is unreachable; a single down host never fails the whole lookup.
    public Mono<SalesOrderInfoTO> lookup(String amazonOrderId, String trackingNumber) {
        if ((amazonOrderId == null) == (trackingNumber == null)) {
            return Mono.error(new IllegalArgumentException(
                    "Exactly one of amazonOrderId or trackingNumber is required"));
        }
        return accessToken
                .flatMapMany(token -> Flux.fromIterable(hosts)
                        .flatMap(host -> lookupOnHost(host, token, amazonOrderId, trackingNumber)))
                .next();
    }

    private Mono<SalesOrderInfoTO> lookupOnHost(String baseUrl, String token, String amazonOrderId, String trackingNumber) {
        log.info("Calling legacy-app API host={} action=getSalesOrderInfo amazonOrderId={} trackingNumber={}",
                baseUrl, amazonOrderId, trackingNumber);
        return webClient.get()
                .uri(buildLookupUri(baseUrl, amazonOrderId, trackingNumber))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchangeToMono(response -> response.statusCode() == HttpStatus.OK
                        ? response.bodyToMono(SalesOrderInfoTO.class)
                        : response.releaseBody().then(Mono.empty()))
                .timeout(REQUEST_TIMEOUT)
                .onErrorResume(ex -> {
                    log.warn("Sales order lookup against {} failed, treating as not found: {}", baseUrl, ex.toString());
                    return Mono.empty();
                });
    }

    private URI buildLookupUri(String baseUrl, String amazonOrderId, String trackingNumber) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + LOOKUP_PATH);
        if (amazonOrderId != null) {
            builder.queryParam("amazonOrderId", amazonOrderId);
        } else {
            builder.queryParam("trackingNumber", trackingNumber);
        }
        return builder.build().toUri();
    }

    // Same fan-out-and-race shape as lookup() above: an order number alone doesn't say which of
    // the 4 storefronts owns it, so every host is asked in parallel and the first 200 wins —
    // treating an empty line list as a legitimate "found the order, it just has no lines" result,
    // not a reason to keep waiting on the other hosts. A 404/error host is "not found there,"
    // same tolerance as lookup(): one storefront being down must never fail the whole picker.
    public Mono<List<SalesOrderLineTO>> salesOrderLines(String orderNumber) {
        return accessToken
                .flatMapMany(token -> Flux.fromIterable(hosts)
                        .flatMap(host -> salesOrderLinesOnHost(host, token, orderNumber)))
                .next();
    }

    private Mono<List<SalesOrderLineTO>> salesOrderLinesOnHost(String baseUrl, String token, String orderNumber) {
        log.info("Calling legacy-app API host={} action=salesOrderLines orderNumber={}", baseUrl, orderNumber);
        return webClient.get()
                .uri(buildSalesOrderLinesUri(baseUrl, orderNumber))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchangeToMono(response -> response.statusCode() == HttpStatus.OK
                        ? response.bodyToMono(SalesOrderLinesResultTO.class).map(this::toLineTOs)
                        : response.releaseBody().then(Mono.empty()))
                .timeout(REQUEST_TIMEOUT)
                .onErrorResume(ex -> {
                    log.warn("Sales order lines lookup against {} failed, treating as not found: {}", baseUrl, ex.toString());
                    return Mono.empty();
                });
    }

    // legacy-app's response wraps the selectable lines alongside an asinMismatchSkus warning list
    // (see SalesOrderLinesResultTO's own comment) that CRM's Refund picker doesn't surface yet —
    // only the lines themselves are mapped into our own frontend-facing SalesOrderLineTO shape.
    private List<SalesOrderLineTO> toLineTOs(SalesOrderLinesResultTO result) {
        List<SalesOrderLineOptionTO> lines = result.getLines();
        if (lines == null) {
            return List.of();
        }
        return lines.stream()
                .map(line -> new SalesOrderLineTO(line.getId(), line.getProductSku(), line.getAmazonSku(),
                        line.getName(), (double) line.getQuantity(), line.getPrice()))
                .toList();
    }

    private URI buildSalesOrderLinesUri(String baseUrl, String orderNumber) {
        return UriComponentsBuilder.fromUriString(baseUrl + SALES_ORDER_LINES_PATH)
                .queryParam("orderNumber", orderNumber)
                .build().toUri();
    }

    // Customer View: every sales order placed by one buyer, keyed by their email.
    //
    // Fanned out like lookup() above — a buyer's email says no more about which storefront owns
    // them than an order number does — but with one deliberate difference: a host that simply
    // doesn't know this buyer answers 200 with an EMPTY list, not a 404, so taking the first
    // response the way lookup() does would hand back "no orders" from whichever host replied
    // fastest. Empty results are therefore skipped and the first host with actual orders wins.
    // (salesOrderLines() below does the opposite for the opposite reason: there the order is
    // already known to exist, so an empty line list is a real answer.) An Amazon buyer email is
    // marketplace-scoped, so exactly one storefront can ever hold it in practice.
    public Mono<List<SalesOrderInfoTO>> salesOrdersByEmail(String email) {
        return accessToken
                .flatMapMany(token -> Flux.fromIterable(hosts)
                        .flatMap(host -> salesOrdersByEmailOnHost(host, token, email)))
                .filter(orders -> !orders.isEmpty())
                .next();
    }

    private Mono<List<SalesOrderInfoTO>> salesOrdersByEmailOnHost(String baseUrl, String token, String email) {
        return webClient.get()
                .uri(UriComponentsBuilder.fromUriString(baseUrl + ORDERS_BY_EMAIL_PATH)
                        .queryParam("email", email)
                        .build().toUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchangeToMono(response -> response.statusCode() == HttpStatus.OK
                        ? response.bodyToMono(SALES_ORDER_LIST)
                        : response.releaseBody().then(Mono.empty()))
                .timeout(REQUEST_TIMEOUT)
                .onErrorResume(ex -> {
                    log.warn("Customer order lookup against {} failed, treating as not found: {}", baseUrl, ex.toString());
                    return Mono.empty();
                });
    }

    // Blocks Amazon review solicitation for one order. Routed to the single legacy-app host
    // that owns `sourceSystem` rather than fanned out the way the read paths above are: this
    // WRITES, and blindly POSTing a state change at all 4 storefronts to find the one that
    // recognises the order would be a mutation aimed at 3 hosts that must not receive it. The
    // caller always knows the channel here — it comes off the very sales order being displayed —
    // so there is nothing to discover by fanning out. Same per-channel routing, and the same
    // reasoning, as LegacyLineItemResolutionClient.
    public Mono<Void> markOrderReviewDisabled(String sourceSystem, String orderNumber, String updateBy) {
        return markReviewDisabled(sourceSystem, ORDER_MARK_REVIEW_DISABLED_PATH,
                Map.of("orderNumber", orderNumber, "updateBy", updateBy),
                "order " + orderNumber);
    }

    // Blocks review solicitation for every order this buyer places, present and future — the
    // customer-level counterpart of markOrderReviewDisabled above, reached from the Customer
    // View. `sourceSystem` is the channel of the sales order the agent opened that view from,
    // for the same routing reason.
    public Mono<Void> markCustomerReviewDisabled(String sourceSystem, String email, String updateBy) {
        return markReviewDisabled(sourceSystem, CUSTOMER_MARK_REVIEW_DISABLED_PATH,
                Map.of("email", email, "updateBy", updateBy),
                "customer " + email);
    }

    // Two failure modes, never collapsed into one: a 4xx is legacy-app reached and refusing, whose
    // own message the agent needs to read (ReviewSettingRejectedException -> 400); anything else
    // — 5xx, timeout, connection refused, no host configured for this channel — is the
    // integration being unavailable (LegacyAppUnavailableException -> 502). Success is a bare
    // completion: legacy-app's response body carries nothing CRM shows, the UI just re-reads the
    // order to pick the new flag up from the same place it renders everything else.
    private Mono<Void> markReviewDisabled(String sourceSystem, String path, Map<String, String> body,
                                            String subject) {
        String host = hostsByChannel.hostForSourceSystem(sourceSystem);
        if (host == null) {
            return Mono.error(new LegacyAppUnavailableException(
                    "Cannot disable review for " + subject + ": no legacy-app channel host for source system '"
                            + sourceSystem + "'", null));
        }
        return accessToken
                .flatMap(token -> webClient.post()
                        .uri(host + path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .bodyValue(body)
                        .exchangeToMono(response -> {
                            if (response.statusCode().is2xxSuccessful()) {
                                return response.releaseBody();
                            }
                            boolean rejected = response.statusCode().is4xxClientError();
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(responseBody -> Mono.error(rejected
                                            ? new ReviewSettingRejectedException(
                                                    "legacy-app rejected disabling review for " + subject + ": "
                                                            + extractMessage(responseBody, response.statusCode().value()))
                                            : new LegacyAppUnavailableException(
                                                    "legacy-app responded with " + response.statusCode().value()
                                                            + " disabling review for " + subject, null)));
                        })
                        .timeout(REQUEST_TIMEOUT))
                .onErrorMap(ex -> !(ex instanceof ReviewSettingRejectedException)
                                && !(ex instanceof LegacyAppUnavailableException),
                        ex -> {
                            log.warn("Disabling review for {} against {} failed: {}", subject, host, ex.toString());
                            return new LegacyAppUnavailableException(
                                    "Failed to reach legacy-app to disable review for " + subject + ": " + ex.getMessage(), ex);
                        })
                .then();
    }

    // legacy-app's Grails error responses are JSON with a "message" (or "error") field; anything else
    // — an HTML error page from the proxy in front of it, an empty body — falls back to the raw
    // status so the agent still sees something actionable rather than a blank message.
    // Package-private for LegacyApiClientReviewTest, matching how
    // LegacyLineItemResolutionClient exposes its own equivalent.
    static String extractMessage(String body, int statusCode) {
        if (body != null && !body.isBlank()) {
            try {
                JsonNode parsed = JSON.readTree(body);
                for (String field : List.of("message", "error")) {
                    JsonNode value = parsed.get(field);
                    if (value != null && !value.isNull() && !value.asString().isBlank()) {
                        return value.asString();
                    }
                }
            } catch (Exception ignored) {
                // Not JSON — fall through to the status below.
            }
        }
        return "HTTP " + statusCode;
    }

    private Mono<String> fetchAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);

        log.info("Calling legacy-app API host={} action=accessToken", hosts.get(0));
        return webClient.post()
                .uri(hosts.get(0) + TOKEN_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(AccessTokenResponse.class)
                .timeout(REQUEST_TIMEOUT)
                .map(AccessTokenResponse::accessToken);
    }

    private record AccessTokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    // Decodes the JWT payload (no signature check — CRM only holds this token, legacy-app verifies
    // it) to read the real "exp" claim, so the cache doesn't outlive or underuse the token legacy-app
    // actually issued. Falls back to TOKEN_TTL for anything that isn't a decodable JWT with an
    // "exp" claim, so a format change on legacy-app's side degrades gracefully instead of breaking.
    private static Duration resolveTtl(String token) {
        try {
            String[] parts = token.split("\\.");
            String payload = parts[1];
            payload += "=".repeat((4 - payload.length() % 4) % 4);
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(payload));
            Duration untilExpiry = Duration.between(Instant.now(), Instant.ofEpochSecond(claims.get("exp").asLong()))
                    .minus(EXPIRY_SAFETY_MARGIN);
            return untilExpiry.isNegative() ? Duration.ZERO : untilExpiry;
        } catch (Exception ex) {
            log.warn("Could not read exp claim from legacy-app's access token, using default TTL of {}: {}",
                    TOKEN_TTL, ex.toString());
            return TOKEN_TTL;
        }
    }
}
