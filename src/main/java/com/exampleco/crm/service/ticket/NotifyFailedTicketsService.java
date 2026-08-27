package com.exampleco.crm.service.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.exampleco.crm.domain.ticket.S2User;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.domain.ticket.TicketComment;
import com.exampleco.core.dto.ticket.TicketStatus;
import com.exampleco.crm.repository.ticket.S2UserRepository;
import com.exampleco.crm.repository.ticket.TicketCommentRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;

// Replaces legacy-app's own daily 08:00 NotifyFailedTicketsJob — a read-only job
// over ticket data that's fully in crm_dev now, so there's no per-channel scoping concern here
// unlike the retained jobs serves (see TicketRepository#findByStatus's own comment).
@Component
public class NotifyFailedTicketsService {

    private static final Logger log = LoggerFactory.getLogger(NotifyFailedTicketsService.class);

    private final TicketRepository ticketRepository;
    private final TicketCommentRepository ticketCommentRepository;
    private final S2UserRepository s2UserRepository;
    private final FailedTicketNotificationMailService mailService;

    public NotifyFailedTicketsService(TicketRepository ticketRepository, TicketCommentRepository ticketCommentRepository,
                                       S2UserRepository s2UserRepository,
                                       FailedTicketNotificationMailService mailService) {
        this.ticketRepository = ticketRepository;
        this.ticketCommentRepository = ticketCommentRepository;
        this.s2UserRepository = s2UserRepository;
        this.mailService = mailService;
    }

    // Called by NotifyFailedTicketsJob's daily Quartz trigger. Returns the number of
    // failed tickets found (regardless of whether any digest actually sent — see
    // FailedTicketNotificationMailService#sendDigest), for the caller to log.
    public int run() {
        List<Ticket> failedTickets = ticketRepository.findByStatus(TicketStatus.ProcessFailed);
        if (!failedTickets.isEmpty()) {
            attachLastException(failedTickets);
            sendPerHandlerDigests(withLastException(failedTickets));
        }
        return failedTickets.size();
    }

    // legacy-app's TicketService#notifyProcessFailedTicketByUser only adds a ticket to a handler's
    // mail `if(lastExceptionComment)` — the digest's whole payload is the exception text, so a
    // ProcessFailed ticket with no "[Exception]" comment would render as a row with an empty
    // reason and nothing for the handler to act on. Still counted as found by #run (the job logs
    // how many are failing, not how many were mailed) and logged here, because a failed ticket
    // that can't explain itself is worth noticing rather than dropping in silence.
    private List<Ticket> withLastException(List<Ticket> failedTickets) {
        List<Ticket> reportable = new ArrayList<>();
        for (Ticket ticket : failedTickets) {
            if (ticket.getLastException() != null) {
                reportable.add(ticket);
            }
        }
        int skipped = failedTickets.size() - reportable.size();
        if (skipped > 0) {
            log.warn("{} ProcessFailed ticket(s) have no {} comment — not reported to anyone",
                    skipped, LastExceptionResolver.EXCEPTION_MARKER);
        }
        return reportable;
    }

    // Mirrors legacy-app's TicketService#notifyProcessFailedTicketByUser: the digest is the
    // *handler's* own to-do list, not a broadcast — each handler is mailed only the failed
    // tickets assigned to them, instead of every admin getting every ticket. A ticket with no
    // handler (or a handler with no email on file) has nobody to tell, exactly as in legacy-app
    // where a null `ticket.handler`/`handler.email` drops out of the send loop; it's logged
    // here rather than silently dropped, since nobody is looking at those tickets otherwise.
    private void sendPerHandlerDigests(List<Ticket> failedTickets) {
        Map<Long, List<Ticket>> ticketsByHandlerId = new LinkedHashMap<>();
        int unassigned = 0;
        for (Ticket ticket : failedTickets) {
            if (ticket.getHandlerId() == null) {
                unassigned++;
                continue;
            }
            ticketsByHandlerId.computeIfAbsent(ticket.getHandlerId(), k -> new ArrayList<>()).add(ticket);
        }
        if (unassigned > 0) {
            log.warn("{} ProcessFailed ticket(s) have no handler — not reported to anyone", unassigned);
        }
        Map<Long, String> emailByHandlerId = new HashMap<>();
        for (S2User handler : s2UserRepository.findAllById(ticketsByHandlerId.keySet())) {
            emailByHandlerId.put(handler.getId(), handler.getEmail());
        }
        for (Map.Entry<Long, List<Ticket>> entry : ticketsByHandlerId.entrySet()) {
            String email = emailByHandlerId.get(entry.getKey());
            if (email == null || email.isBlank()) {
                log.warn("Handler {} has no email on file — {} ProcessFailed ticket(s) not reported",
                        entry.getKey(), entry.getValue().size());
                continue;
            }
            mailService.sendDigest(entry.getValue(), List.of(email));
        }
    }

    // Mirrors TicketController#enrich's lastException derivation (same batched-lookup shape),
    // scoped down to just what this digest needs — no line items/address/handler/customer.
    private void attachLastException(List<Ticket> tickets) {
        List<Long> ticketIds = tickets.stream().map(Ticket::getId).distinct().toList();
        Map<Long, List<String>> commentsByTicketId = new HashMap<>();
        for (TicketComment comment : ticketCommentRepository.findByTicketIdInOrderByDateCreatedAsc(ticketIds)) {
            commentsByTicketId.computeIfAbsent(comment.getTicketId(), k -> new ArrayList<>()).add(comment.getComment());
        }
        for (Ticket ticket : tickets) {
            ticket.setLastException(LastExceptionResolver.resolve(commentsByTicketId.getOrDefault(ticket.getId(), List.of())));
        }
    }
}
