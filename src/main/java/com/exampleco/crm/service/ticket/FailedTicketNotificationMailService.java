package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.common.MailTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import com.exampleco.crm.domain.ticket.Ticket;

// Replaces legacy-app's notifyMail.gsp template — one daily digest email per
// handler, listing that handler's own currently-ProcessFailed tickets (see
// NotifyFailedTicketsService#sendPerHandlerDigests). This app never sends mail directly: it only ever
// publishes a MailTO (exampleco-core) onto the shared "message" exchange, which a separate
// mail-sending consumer elsewhere in the exampleco system actually delivers — same broker
// contract DeadLetterTO's own exchange/routingKey fields point at, and the same reasoning
// legacy-app itself already relies on for outbound mail.
@Component
public class FailedTicketNotificationMailService {

    private static final Logger log = LoggerFactory.getLogger(FailedTicketNotificationMailService.class);

    // Fixed contract with the mail-sending consumer, not an environment-specific setting — same
    // reasoning as the hand-mirrored Grails enum codes in TicketController, so this isn't a
    // ${VAR:default} property in application.properties.
    private static final String MAIL_EXCHANGE = "message";
    private static final String MAIL_ROUTING_KEY = "message";

    private final RabbitTemplate rabbitTemplate;
    private final String fromAddress;
    // Reuses the existing frontend-origin property (already the source of truth for where the
    // SPA is served from — see SecurityConfig's CORS wiring and
    // app.keycloak.post-logout-redirect-uri's own precedent for reusing it) rather than adding a
    // second "base URL" property that could drift from it.
    private final String frontendBaseUrl;

    public FailedTicketNotificationMailService(RabbitTemplate rabbitTemplate,
                                                @Value("${app.mail.from-address}") String fromAddress,
                                                @Value("${app.cors.allowed-origins}") String frontendBaseUrl) {
        this.rabbitTemplate = rabbitTemplate;
        this.fromAddress = fromAddress;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    // No-op (not an error) if there's nothing to report or nobody to report it to — a clean
    // ProcessFailed-free day, or a handler with no email on file yet, shouldn't spam logs with a
    // publish failure.
    public void sendDigest(List<Ticket> failedTickets, List<String> recipients) {
        if (failedTickets.isEmpty() || recipients.isEmpty()) {
            return;
        }
        try {
            MailTO mail = new MailTO();
            mail.setFrom(fromAddress);
            mail.setTo(recipients);
            mail.setSubject(failedTickets.size() + " ticket(s) failed processing");
            mail.setBodyContent(buildHtml(failedTickets));
            rabbitTemplate.convertAndSend(MAIL_EXCHANGE, MAIL_ROUTING_KEY, mail);
        } catch (Exception e) {
            // A failed *publish* of the failed-ticket alert must not itself blow up the
            // scheduled job (see NotifyFailedTicketsJob) — log and move on, same "one bad
            // piece doesn't take down the whole run" tolerance LegacyApiClient applies to a down
            // host.
            log.warn("Failed to publish failed-ticket digest MailTO: {}", e.toString());
        }
    }

    private String buildHtml(List<Ticket> tickets) {
        StringBuilder rows = new StringBuilder();
        for (Ticket ticket : tickets) {
            rows.append("<tr>")
                    .append("<td><a href=\"").append(ticketLink(ticket.getId())).append("\">")
                    .append(ticket.getId()).append("</a></td>")
                    .append("<td>").append(escape(ticket.getSourceSystem())).append("</td>")
                    .append("<td>").append(escape(ticket.getOrderNumber())).append("</td>")
                    .append("<td>").append(escape(ticket.getLastException())).append("</td>")
                    .append("</tr>");
        }
        return "<html><body>"
                + "<p>" + tickets.size() + " ticket(s) are currently in ProcessFailed status:</p>"
                + "<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\">"
                + "<tr><th>Ticket</th><th>Channel</th><th>Order #</th><th>Last exception</th></tr>"
                + rows
                + "</table>"
                + "</body></html>";
    }

    private String ticketLink(Long ticketId) {
        return frontendBaseUrl + "/?ticketId=" + ticketId;
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
