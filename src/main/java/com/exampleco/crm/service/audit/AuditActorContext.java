package com.exampleco.crm.service.audit;

import java.util.concurrent.Callable;

// Hibernate Envers' RevisionListener has no idea who's logged in -- there's no Spring Security
// context it reads on its own -- so this app has to tell it. Every ticket-mutating controller
// endpoint already resolves the caller's Authentication in a reactive flatMap and then runs the
// actual blocking JPA work inside one synchronous Mono.fromCallable(...).subscribeOn(
// Schedulers.boundedElastic()) block (the reactive-shell pattern CLAUDE.md documents) -- a plain
// ThreadLocal set immediately before that block and cleared immediately after is safe precisely
// because that block, including any repository.save()/flush it triggers, runs start-to-finish on
// one thread, never the Netty event loop and never handed off to another boundedElastic thread
// mid-flight. Reactor's own Context doesn't help here: it isn't visible inside a plain Callable.
public final class AuditActorContext {

    private static final ThreadLocal<String> CURRENT_USERNAME = new ThreadLocal<>();

    private AuditActorContext() {
    }

    public static String getCurrentUsername() {
        return CURRENT_USERNAME.get();
    }

    public static <T> T runAs(String username, Callable<T> action) throws Exception {
        CURRENT_USERNAME.set(username);
        try {
            return action.call();
        } finally {
            CURRENT_USERNAME.remove();
        }
    }
}
