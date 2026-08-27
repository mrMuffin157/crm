package com.exampleco.crm.service.ticket;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import com.exampleco.crm.domain.ticket.S2User;
import com.exampleco.crm.domain.ticket.TicketComment;
import com.exampleco.crm.dto.ticket.CommentSummary;
import com.exampleco.crm.repository.ticket.S2UserRepository;
import com.exampleco.crm.repository.ticket.TicketCommentRepository;

// Extracted out of TicketController#addComment so TicketCloseService's bulk
// CSV import path can add closing comments too, through the exact same id-generation lock —
// see TicketIdLock's own comment for why this can't just get a lock of its own.
@Component
public class TicketCommentService {

    private final TicketCommentRepository ticketCommentRepository;
    private final S2UserRepository s2UserRepository;
    private final TicketIdLock ticketIdLock;

    public TicketCommentService(TicketCommentRepository ticketCommentRepository, S2UserRepository s2UserRepository,
                                 TicketIdLock ticketIdLock) {
        this.ticketCommentRepository = ticketCommentRepository;
        this.s2UserRepository = s2UserRepository;
        this.ticketIdLock = ticketIdLock;
    }

    // Resolves authorUsername to an s2user id the same way TicketController's own callers
    // (ProfileController/AppUserAdminController) do, rather than trusting a client-supplied
    // author id. sourceSystem is the comment's own brand tag — always "crm" for a comment this
    // app writes itself (see TicketController.SOURCE_SYSTEM), never the parent ticket's original
    // migrated source_system.
    public CommentSummary addComment(Long ticketId, String sourceSystem, String text, String authorUsername) {
        Long authorId = s2UserRepository.findByUsername(authorUsername)
                .map(S2User::getId)
                .orElse(null);
        return ticketIdLock.withLock(() -> {
            TicketComment comment = new TicketComment();
            Long nextId = ticketCommentRepository.findTopByOrderByIdDesc()
                    .map(existing -> existing.getId() + 1)
                    .orElse(1L);
            comment.setId(nextId);
            comment.setSourceSystem(sourceSystem);
            comment.setTicketId(ticketId);
            comment.setComment(text);
            comment.setCreatedById(authorId);
            LocalDateTime now = LocalDateTime.now();
            comment.setDateCreated(now);
            comment.setLastUpdated(now);
            TicketComment saved = ticketCommentRepository.save(comment);
            return new CommentSummary(saved.getComment(), authorUsername, saved.getDateCreated());
        });
    }
}
