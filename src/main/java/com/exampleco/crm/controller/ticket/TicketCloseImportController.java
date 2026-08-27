package com.exampleco.crm.controller.ticket;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import com.exampleco.crm.dto.ticket.CloseImportSummary;
import com.exampleco.crm.service.audit.AuditActorContext;
import com.exampleco.crm.service.ticket.TicketCloseImportService;

// Close-ticket CSV bulk import (real format confirmed ), replacing
// legacy-app's importCloseTicketData. This is routine CS work — the same reconciliation any member
// can already do one ticket at a time via POST /api/tickets/{id}/close — not an admin action, so
// it deliberately does NOT live under /api/admin/**. SecurityConfig carries its own explicit
// hasAnyRole(ADMIN, MEMBER) rule for this exact path instead, ahead of the M2M-reachable
// anyExchange().authenticated() fallback — see that rule's own comment for why a bare
// authenticated() would be wrong here.
@RestController
@CrossOrigin(origins = "${app.cors.allowed-origins}")
public class TicketCloseImportController {

    private final TicketCloseImportService ticketCloseImportService;

    public TicketCloseImportController(TicketCloseImportService ticketCloseImportService) {
        this.ticketCloseImportService = ticketCloseImportService;
    }

    @PostMapping(value = "/api/tickets/close-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CloseImportSummary> importCloseTickets(@RequestPart("file") Mono<FilePart> filePartMono,
                                                         Mono<Authentication> authenticationMono) {
        return Mono.zip(filePartMono, authenticationMono)
                .flatMap(tuple -> readBytes(tuple.getT1())
                        .flatMap(bytes -> Mono.fromCallable(() ->
                                AuditActorContext.runAs(tuple.getT2().getName(), () ->
                                        ticketCloseImportService.importCsv(bytes, tuple.getT2().getName()))
                        ).subscribeOn(Schedulers.boundedElastic())));
    }

    private Mono<byte[]> readBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(this::toBytesAndRelease);
    }

    private byte[] toBytesAndRelease(DataBuffer buffer) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(buffer.readableByteCount());
            byte[] chunk = new byte[buffer.readableByteCount()];
            buffer.read(chunk);
            out.writeBytes(chunk);
            return out.toByteArray();
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid request: " + e.getMessage()));
    }
}
