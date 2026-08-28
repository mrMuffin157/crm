package com.exampleco.crm.service.ticket;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.core.dto.ticket.TicketStatus;
import com.exampleco.crm.exception.ticket.TicketNotFoundException;
import com.exampleco.crm.repository.ticket.TicketRepository;

// Extracted out of TicketController#failTicket so the status change
// and the optional comment share one DB transaction — without @Transactional here, the two were
// separate blocking calls with no transaction boundary at all, so a comment-insert failure after
// the status save had already committed would leave the ticket ProcessFailed with no explanatory
// comment, exactly the half-done state this endpoint exists to avoid (see its own controller
// comment). Mirrors TicketCloseService's "save ticket, then add the comment" shape.
@Component
public class TicketFailService {

    private final TicketRepository ticketRepository;
    private final TicketCommentService ticketCommentService;

    public TicketFailService(TicketRepository ticketRepository, TicketCommentService ticketCommentService) {
        this.ticketRepository = ticketRepository;
        this.ticketCommentService = ticketCommentService;
    }

    @Transactional
    public Ticket fail(Long id, String sourceSystem, String comment, String authorUsername) {
        Ticket ticket = ticketRepository.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
        if (isLocked(ticket)) {
            throw new IllegalStateException("Cannot fail a Closed or Cancelled ticket");
        }
        ticket.setStatus(TicketStatus.ProcessFailed);
        ticket.setLastUpdated(LocalDateTime.now());
        Ticket saved = ticketRepository.save(ticket);
        if (comment != null && !comment.isBlank()) {
            ticketCommentService.addComment(saved.getId(), sourceSystem, comment, authorUsername);
        }
        return saved;
    }

    private boolean isLocked(Ticket ticket) {
        return ticket.getStatus() == TicketStatus.Closed || ticket.getStatus() == TicketStatus.Cancelled;
    }
}
