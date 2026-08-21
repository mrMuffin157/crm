package com.exampleco.crm.repository.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import com.exampleco.crm.domain.ticket.TicketComment;

public interface TicketCommentRepository extends JpaRepository<TicketComment, Long> {
    List<TicketComment> findByTicketIdInOrderByDateCreatedAsc(List<Long> ticketIds);

    // TicketComment.id is not a @GeneratedValue column, same reasoning as Ticket.id — see
    // TicketRepository#findTopByOrderByIdDesc. Mirrored here for TicketController#addComment.
    Optional<TicketComment> findTopByOrderByIdDesc();
}
