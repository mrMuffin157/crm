package com.exampleco.crm.controller.audit;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import com.exampleco.crm.dto.audit.TicketHistoryEntry;
import com.exampleco.crm.service.audit.TicketHistoryService;

// surfaces Hibernate Envers' revision history for a single ticket (and its comments/
// line items) to the ticket detail UI. No ROLE_SERVICE/ROLE_ADMIN distinction: same access as
// viewing the ticket itself (SecurityConfig's blanket "everything else requires authentication"
// rule already covers this path).
@RestController
@CrossOrigin(origins = "${app.cors.allowed-origins}")
public class TicketHistoryController {

    private final TicketHistoryService ticketHistoryService;

    public TicketHistoryController(TicketHistoryService ticketHistoryService) {
        this.ticketHistoryService = ticketHistoryService;
    }

    @GetMapping(value = "/api/tickets/{id}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<TicketHistoryEntry>> getHistory(@PathVariable Long id) {
        return Mono.fromCallable(() -> ticketHistoryService.getHistory(id))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
