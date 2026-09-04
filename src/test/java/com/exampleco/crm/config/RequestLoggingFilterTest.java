package com.exampleco.crm.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// RequestLoggingFilter's whole job is a log side-effect, so these assert against a
// Logback ListAppender attached directly to its Logger rather than any return value.
//
// The WebFilterChain stub below sets HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE lazily,
// inside a Mono.defer -- i.e. only once the returned Mono is actually subscribed, exactly like
// the real DispatcherHandler sets it during dispatch. This is deliberate: an earlier version of
// the filter read that attribute via a plain `.then(logRequest(exchange))`, which evaluates
// logRequest(exchange) eagerly while the Mono chain is still being assembled -- before
// chain.filter(exchange) is ever subscribed -- so the attribute was always null. A stub that set
// the attribute eagerly (e.g. as a side effect of the mock invocation itself) would not have
// caught that regression; only a lazily-set attribute does.
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private final Logger log = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        log.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        log.detachAppender(appender);
    }

    static class TicketController {
        public Mono<Void> listTickets() {
            return Mono.empty();
        }
    }

    static class AppUserAdminController {
        public Mono<Void> updateRole() {
            return Mono.empty();
        }
    }

    private WebFilterChain chainThatMatches(ServerWebExchange exchange, HandlerMethod handlerMethod) {
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.defer(() -> {
            exchange.getAttributes().put(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handlerMethod);
            return Mono.empty();
        }));
        return chain;
    }

    private HandlerMethod handlerMethod(Object controller, String methodName) throws NoSuchMethodException {
        return new HandlerMethod(controller, controller.getClass().getMethod(methodName));
    }

    @Test
    void logsTheAuthenticatedUsernameControllerAndAction() throws NoSuchMethodException {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/tickets"));
        HandlerMethod handlerMethod = handlerMethod(new TicketController(), "listTickets");
        WebFilterChain chain = chainThatMatches(exchange, handlerMethod);
        Authentication authentication = new UsernamePasswordAuthenticationToken("jdoe", null);

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .isEqualTo("User(username:jdoe) on Controller:ticket  Action:listTickets");
    }

    // Confirms the "Controller" suffix is stripped and the remainder decapitalized even for a
    // multi-word class name, not just a single-word one like TicketController above.
    @Test
    void decapitalizesAMultiWordControllerName() throws NoSuchMethodException {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.patch("/api/admin/users/1/role"));
        HandlerMethod handlerMethod = handlerMethod(new AppUserAdminController(), "updateRole");
        WebFilterChain chain = chainThatMatches(exchange, handlerMethod);
        Authentication authentication = new UsernamePasswordAuthenticationToken("admin1", null);

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        assertThat(appender.list.get(0).getFormattedMessage())
                .isEqualTo("User(username:admin1) on Controller:appUserAdmin  Action:updateRole");
    }

    @Test
    void logsAnonymousWhenNoSecurityContextIsPresent() throws NoSuchMethodException {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/config"));
        HandlerMethod handlerMethod = handlerMethod(new TicketController(), "listTickets");
        WebFilterChain chain = chainThatMatches(exchange, handlerMethod);

        filter.filter(exchange, chain).block();

        assertThat(appender.list.get(0).getFormattedMessage())
                .isEqualTo("User(username:anonymous) on Controller:ticket  Action:listTickets");
    }

    // A request that never reaches a controller action (a static SPA asset, a 404) never sets
    // HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE -- that must not be logged as an action.
    @Test
    void logsNothingWhenNoControllerActionWasMatched() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/index.html"));
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        assertThat(appender.list).isEmpty();
    }
}
