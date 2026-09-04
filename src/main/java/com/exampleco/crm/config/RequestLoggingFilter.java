package com.exampleco.crm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

// logs who called which controller action, mirroring legacy-app's own Grails UserInterceptor
// log line ("User(username:x) on Controller:y  Action:z") so both apps' logs read the same way
// when correlating a user's actions across systems. A single global WebFilter rather than a
// log.info() added by hand to every controller method -- every current AND future endpoint is
// covered for free, the same "logic lives in one place" reasoning TicketController's own
// enrich() batching follows.
//
// A plain @Component WebFilter bean IS auto-registered into WebFlux's global filter chain
// (contrast LegacyServiceAuthenticationFilter's own comment on why it deliberately isn't one) and
// defaults to the lowest precedence, which puts it after Spring Security's own filter chain
// (registered at SecurityWebFiltersOrder position -100) -- so the security context this filter
// reads is already populated by the time it runs.
@Component
public class RequestLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String CONTROLLER_SUFFIX = "Controller";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Mono.defer is required here, not optional: HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE
        // is only populated once WebFlux's DispatcherHandler runs request mapping, which happens
        // *inside* chain.filter(exchange) -- a plain `.then(logRequest(exchange))` would invoke
        // logRequest(exchange) eagerly, while this Mono chain is still being assembled and long
        // before chain.filter(exchange) is ever subscribed, reading the attribute before it exists.
        return chain.filter(exchange).then(Mono.defer(() -> logRequest(exchange)));
    }

    private Mono<Void> logRequest(ServerWebExchange exchange) {
        Object handler = exchange.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // Static SPA assets, 404s, etc. aren't a controller action worth logging this way.
            return Mono.empty();
        }
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(ctx -> Mono.justOrEmpty(ctx.getAuthentication()))
                .map(Authentication::getName)
                .defaultIfEmpty("anonymous")
                .doOnNext(username -> log.info("User(username:{}) on Controller:{}  Action:{}",
                        username, controllerName(handlerMethod), handlerMethod.getMethod().getName()))
                .then();
    }

    private String controllerName(HandlerMethod handlerMethod) {
        String simpleName = handlerMethod.getBeanType().getSimpleName();
        String stripped = simpleName.endsWith(CONTROLLER_SUFFIX)
                ? simpleName.substring(0, simpleName.length() - CONTROLLER_SUFFIX.length())
                : simpleName;
        return stripped.isEmpty() ? simpleName : Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }
}
