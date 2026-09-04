package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.common.TelegramContentType;
import com.exampleco.core.dto.common.TelegramTO;
import com.exampleco.core.dto.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Map;
import com.exampleco.crm.domain.ticket.Ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// RefundTicketNotificationService publishes a TelegramTO onto the shared "message"
// exchange, same broker contract FailedTicketNotificationMailService already uses for MailTO —
// see that service's own test for the parallel structure this mirrors.
@ExtendWith(MockitoExtension.class)
class RefundTicketNotificationServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private RefundTicketNotificationService newService() {
        return new RefundTicketNotificationService(rabbitTemplate, "https://crm.example.com");
    }

    private Ticket ticket(Long id, TicketStatus status, String orderNumber) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setStatus(status);
        ticket.setOrderNumber(orderNumber);
        return ticket;
    }

    private TelegramTO captured() {
        ArgumentCaptor<TelegramTO> captor = ArgumentCaptor.forClass(TelegramTO.class);
        verify(rabbitTemplate).convertAndSend(eq("message"), eq("message"), captor.capture());
        return captor.getValue();
    }

    @Test
    void emptyTickets_neverPublishes() {
        RefundTicketNotificationService service = newService();

        service.sendDigest(List.of(), Map.of(), List.of(111L));

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void emptyRecipients_neverPublishes() {
        RefundTicketNotificationService service = newService();

        service.sendDigest(List.of(ticket(1L, TicketStatus.Processing, "112-1234567-1234567")), Map.of(), List.of());

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void publishesToEveryRecipientChatIdWithHtmlContent() {
        RefundTicketNotificationService service = newService();

        service.sendDigest(List.of(ticket(1L, TicketStatus.Processing, "112-1234567-1234567")),
                Map.of(1L, List.of("SKU-1", "SKU-2")), List.of(111L, 222L));

        TelegramTO telegram = captured();
        assertThat(telegram.getTo()).containsExactly(111L, 222L);
        assertThat(telegram.getType()).isEqualTo(TelegramContentType.HTML);
    }

    @Test
    void messageIncludesStatusOrderNumberLinkAndSkus() {
        RefundTicketNotificationService service = newService();

        service.sendDigest(List.of(ticket(42L, TicketStatus.Processing, "112-1234567-1234567")),
                Map.of(42L, List.of("SKU-1", "SKU-2")), List.of(111L));

        TelegramTO telegram = captured();
        assertThat(telegram.getBodyContent()).contains("Refund Tickets:");
        assertThat(telegram.getBodyContent()).contains("Processing");
        assertThat(telegram.getBodyContent()).contains("<a href=\"https://crm.example.com/?ticketId=42\">112-1234567-1234567</a>");
        assertThat(telegram.getBodyContent()).contains("SKU-1,SKU-2");
    }

    @Test
    void ticketWithNoLineItems_rendersEmptySkuListRatherThanFailing() {
        RefundTicketNotificationService service = newService();

        service.sendDigest(List.of(ticket(1L, TicketStatus.New, "112-1234567-1234567")), Map.of(), List.of(111L));

        TelegramTO telegram = captured();
        assertThat(telegram.getBodyContent()).contains("New <a href=\"https://crm.example.com/?ticketId=1\">112-1234567-1234567</a>");
    }

    @Test
    void multipleTickets_areJoinedOnePerLine() {
        RefundTicketNotificationService service = newService();

        service.sendDigest(List.of(
                        ticket(1L, TicketStatus.New, "ORDER-1"),
                        ticket(2L, TicketStatus.ProcessFailed, "ORDER-2")),
                Map.of(), List.of(111L));

        TelegramTO telegram = captured();
        String[] lines = telegram.getBodyContent().split("\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).contains("ORDER-1");
        assertThat(lines[2]).contains("ORDER-2");
    }

    // orderNumber/sku are agent- or channel-controlled and must be escaped — an unescaped '<'/'>'
    // could otherwise break Telegram's HTML parse mode entirely, same hardening
    // ReplacementShippedMailServiceTest applies on the mail side.
    @Test
    void orderNumberAndSkus_areHtmlEscaped() {
        RefundTicketNotificationService service = newService();

        service.sendDigest(List.of(ticket(1L, TicketStatus.Processing, "112-<script>-1234567")),
                Map.of(1L, List.of("SKU&1")), List.of(111L));

        TelegramTO telegram = captured();
        assertThat(telegram.getBodyContent()).contains("112-&lt;script&gt;-1234567");
        assertThat(telegram.getBodyContent()).contains("SKU&amp;1");
        assertThat(telegram.getBodyContent()).doesNotContain("<script>");
    }

    @Test
    void publishFailure_isCaughtAndDoesNotThrow() {
        doThrow(new AmqpException("broker down") {}).when(rabbitTemplate)
                .convertAndSend(any(String.class), any(String.class), any(TelegramTO.class));
        RefundTicketNotificationService service = newService();

        service.sendDigest(List.of(ticket(1L, TicketStatus.New, "ORDER-1")), Map.of(), List.of(111L));
    }
}
