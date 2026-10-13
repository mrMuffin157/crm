package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import com.exampleco.crm.domain.ticket.S2User;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.domain.ticket.TicketComment;
import com.exampleco.crm.repository.ticket.S2UserRepository;
import com.exampleco.crm.repository.ticket.TicketCommentRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

// Mirrors legacy-app's TicketService#notifyProcessFailedTicketByUser: the daily ProcessFailed digest
// is the handler's own to-do list, so each handler must be mailed only their own tickets — a
// broadcast of every failed ticket to every admin is what this replaced, and nothing else in the
// job would fail if that regressed. Digest rendering itself is not under test here.
@ExtendWith(MockitoExtension.class)
class NotifyFailedTicketsServiceTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketCommentRepository ticketCommentRepository;
    @Mock
    private S2UserRepository s2UserRepository;
    @Mock
    private FailedTicketNotificationMailService mailService;

    private NotifyFailedTicketsService newService() {
        return new NotifyFailedTicketsService(ticketRepository, ticketCommentRepository, s2UserRepository, mailService);
    }

    private Ticket ticket(Long id, Long handlerId) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setStatus(TicketStatus.ProcessFailed);
        ticket.setHandlerId(handlerId);
        return ticket;
    }

    private S2User handler(Long id, String email) {
        S2User user = new S2User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    // Every ticket in these fixtures gets an "[Exception]" comment unless a test deliberately
    // leaves one out — without it the ticket has no lastException and is filtered out of the
    // digest entirely (see NotifyFailedTicketsService#withLastException).
    private void stubExceptionCommentsFor(Long... ticketIds) {
        List<TicketComment> comments = new ArrayList<>();
        for (Long ticketId : ticketIds) {
            TicketComment comment = new TicketComment();
            comment.setTicketId(ticketId);
            comment.setComment(LastExceptionResolver.EXCEPTION_MARKER + " boom on ticket " + ticketId);
            comments.add(comment);
        }
        lenient().when(ticketCommentRepository.findByTicketIdInOrderByDateCreatedAsc(anyList())).thenReturn(comments);
    }

    @Test
    void sendsEachHandlerOnlyTheirOwnFailedTickets() {
        Ticket first = ticket(1L, 10L);
        Ticket second = ticket(2L, 20L);
        Ticket third = ticket(3L, 10L);
        when(ticketRepository.findByStatus(TicketStatus.ProcessFailed)).thenReturn(List.of(first, second, third));
        stubExceptionCommentsFor(1L, 2L, 3L);
        when(s2UserRepository.findAllById(any()))
                .thenReturn(List.of(handler(10L, "alice@example.com"), handler(20L, "bob@example.com")));

        int count = newService().run();

        assertThat(count).isEqualTo(3);
        verify(mailService).sendDigest(List.of(first, third), List.of("alice@example.com"));
        verify(mailService).sendDigest(List.of(second), List.of("bob@example.com"));
        verifyNoMoreInteractions(mailService);
    }

    // legacy-app drops a ticket whose handler (or handler email) is missing out of its send loop;
    // the ticket still counts as found, it just has nobody to report it to.
    @Test
    void skipsTicketsWithNoHandlerOrNoHandlerEmail() {
        Ticket unassigned = ticket(1L, null);
        Ticket noEmail = ticket(2L, 20L);
        Ticket reportable = ticket(3L, 30L);
        when(ticketRepository.findByStatus(TicketStatus.ProcessFailed))
                .thenReturn(List.of(unassigned, noEmail, reportable));
        stubExceptionCommentsFor(1L, 2L, 3L);
        when(s2UserRepository.findAllById(any()))
                .thenReturn(List.of(handler(20L, "  "), handler(30L, "carol@example.com")));

        int count = newService().run();

        assertThat(count).isEqualTo(3);
        verify(mailService).sendDigest(List.of(reportable), List.of("carol@example.com"));
        verifyNoMoreInteractions(mailService);
    }

    // legacy-app only adds a ticket to its handler's mail `if(lastExceptionComment)` — the exception
    // text is the digest's entire payload, so a ProcessFailed ticket without one is nothing the
    // handler can act on. It still counts as found, so the job's log keeps reporting reality.
    @Test
    void skipsTicketsWithNoExceptionComment() {
        Ticket withException = ticket(1L, 10L);
        Ticket withoutException = ticket(2L, 10L);
        when(ticketRepository.findByStatus(TicketStatus.ProcessFailed))
                .thenReturn(List.of(withException, withoutException));
        stubExceptionCommentsFor(1L);
        when(s2UserRepository.findAllById(any())).thenReturn(List.of(handler(10L, "alice@example.com")));

        int count = newService().run();

        assertThat(count).isEqualTo(2);
        verify(mailService).sendDigest(List.of(withException), List.of("alice@example.com"));
        verifyNoMoreInteractions(mailService);
    }

    @Test
    void sendsNothingWhenNoTicketsFailed() {
        when(ticketRepository.findByStatus(TicketStatus.ProcessFailed)).thenReturn(List.of());

        assertThat(newService().run()).isZero();

        verifyNoInteractions(s2UserRepository, mailService);
    }
}
