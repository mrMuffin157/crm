package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.common.TelegramContentType;
import com.exampleco.core.dto.common.TelegramTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import com.exampleco.crm.domain.ticket.Ticket;

// Replaces legacy-app's own NotificationService#sendMessage/#sendAlarm calls in
// SalesOrderService#notifyRefundTickets. This app never sends Telegram messages
// directly: it only ever publishes a TelegramTO (exampleco-core) onto the shared "message"
// exchange, same broker contract FailedTicketNotificationMailService already uses for MailTO —
// a separate message-sending consumer elsewhere in the exampleco system actually delivers it.
@Component
public class RefundTicketNotificationService {

    private static final Logger log = LoggerFactory.getLogger(RefundTicketNotificationService.class);

    // Fixed contract with the message-sending consumer, not an environment-specific setting —
    // same reasoning as FailedTicketNotificationMailService's own MAIL_EXCHANGE/MAIL_ROUTING_KEY.
    private static final String MESSAGE_EXCHANGE = "message";
    private static final String MESSAGE_ROUTING_KEY = "message";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final RabbitTemplate rabbitTemplate;
    // Reuses the existing frontend-origin property, same precedent as
    // FailedTicketNotificationMailService's own frontendBaseUrl field.
    private final String frontendBaseUrl;

    public RefundTicketNotificationService(RabbitTemplate rabbitTemplate,
                                            @Value("${app.cors.allowed-origins}") String frontendBaseUrl) {
        this.rabbitTemplate = rabbitTemplate;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    // No-op (not an error) if there's nothing to report or nobody configured to report it to —
    // mirrors FailedTicketNotificationMailService#sendDigest's own tolerance for an empty
    // recipient list (e.g. NOTIFY_REFUND_TICKET_MEMBER/GSM_ALARM_GROUP left unset).
    public void sendDigest(List<Ticket> tickets, Map<Long, List<String>> skusByTicketId, List<Long> recipientChatIds) {
        if (tickets.isEmpty() || recipientChatIds.isEmpty()) {
            return;
        }
        try {
            TelegramTO telegram = new TelegramTO();
            telegram.setTo(recipientChatIds);
            telegram.setBodyContent(buildMessage(tickets, skusByTicketId));
            telegram.setType(TelegramContentType.HTML);
            rabbitTemplate.convertAndSend(MESSAGE_EXCHANGE, MESSAGE_ROUTING_KEY, telegram);
        } catch (Exception e) {
            // A failed *publish* of the refund-ticket alert must not itself blow up the scheduled
            // job (see NotifyRefundTicketsJob) — log and move on, same tolerance
            // FailedTicketNotificationMailService applies to its own MailTO publish.
            log.warn("Failed to publish refund-ticket digest TelegramTO: {}", e.toString());
        }
    }

    private String buildMessage(List<Ticket> tickets, Map<Long, List<String>> skusByTicketId) {
        StringBuilder lines = new StringBuilder();
        for (Ticket ticket : tickets) {
            if (!lines.isEmpty()) {
                lines.append("\n");
            }
            String skus = String.join(",", skusByTicketId.getOrDefault(ticket.getId(), List.of()).stream()
                    .map(this::escape).toList());
            lines.append(ticket.getStatus().name())
                    .append(" <a href=\"").append(ticketLink(ticket.getId())).append("\">")
                    .append(escape(ticket.getOrderNumber())).append("</a> ")
                    .append(skus);
        }
        return LocalDate.now().format(DATE_FORMAT) + " Refund Tickets:\n" + lines;
    }

    private String ticketLink(Long ticketId) {
        return frontendBaseUrl + "/?ticketId=" + ticketId;
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
