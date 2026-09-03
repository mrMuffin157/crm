package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.common.MailTO;
import com.exampleco.core.dto.ticket.TicketAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.exampleco.crm.config.ticket.ReplacementShippedMailProperties;
import com.exampleco.crm.config.ticket.ReplacementShippedMailProperties.ChannelMail;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.domain.ticket.TicketLineItem;

// Tells the buyer their replacement shipped — this app builds the HTML itself and
// publishes a MailTO onto the same shared "message" exchange FailedTicketNotificationMailService
// already uses, replacing a call to legacy-app's ticketClosed endpoint.
// The body is a byte-exact port of
// legacy-app's own `[SYS]Manual Handling Ticket Closed Notify for customer` email_template row (all
// 4 channels share this one body — pulled from stage: store1_dev/store4_dev/store3_dev/
// store2_dev — and differ only in ChannelMail's fromAddress/subject/signOff), as rendered by
// AmazonSalesOrderProviderService#handleTicket + #buildLineItemMessage on the legacy-app side before
// /moved ticket data (and now this mail) into CRM.
//
// legacy-app's own buildLineItemMessage resolves each group's "Item Name" from a local AmazonProduct
// lookup (channelSku -> catalog name) — the one piece of data CRM had no source for until 
// added TicketLineItem#channelProductName, populated at save time
// (TicketController#persistLineItems) so this service never needs to call out to legacy-app itself.
@Component
public class ReplacementShippedMailService {

    private static final Logger log = LoggerFactory.getLogger(ReplacementShippedMailService.class);

    // Fixed contract with the mail-sending consumer, not per-environment — same reasoning as
    // FailedTicketNotificationMailService's own constants.
    private static final String MAIL_EXCHANGE = "message";
    private static final String MAIL_ROUTING_KEY = "message";

    // Byte-exact port of the email_template row's `content` column (all 4 channels share this
    // body verbatim on stage). %s placeholders, in order: amazonOrderId, ticketLineItemMsg,
    // carrier (used twice, matching the template's own two ${carrier} references), trackingNumber,
    // signOff. Deliberately String.formatted(), not ${} token substitution (see buildBody) — a
    // buyer-supplied part name containing the literal text "${carrier}" must never be substituted
    // by a later token-replacement pass.
    private static final String BODY_TEMPLATE = """
            <p>Dear Valued Customer,</p>

            <p>%s</p>

            <p>We have processed your replacements for the following item:<br />
            %s</p>

            <p>The following is a link to your item&rsquo;s delivery status via %s Tracking for your reference:<br />
            %s<span style="color:rgb(34, 34, 34); font-family:arial,helvetica,sans-serif; font-size:small">&nbsp;Tracking#&nbsp;</span>%s</p>

            <p>We hope your replacement item finds you well.<br /><br />
            Please note: <br />
            This email was sent from a notification-only address that cannot accept incoming e-mail. Please do not reply to this message. <br />
            If you have any question, please contact our customer service team at the email address listed on the instruction manual.</p>

            <p>Sincerely,<br />
            %s</p>""";

    private final RabbitTemplate rabbitTemplate;
    private final ReplacementShippedMailProperties properties;
    private final Environment environment;

    public ReplacementShippedMailService(RabbitTemplate rabbitTemplate, ReplacementShippedMailProperties properties,
                                          Environment environment) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.environment = environment;
    }

    // carrier is the already-resolved value TicketCloseService#close puts on the ticket (either
    // derived from the tracking number's "1Z" prefix, or the CSV import's own Carrier - Name
    // column — see that class's own comment); this service never re-derives it. includeBcc is
    // true only for the manual single-ticket close (TicketController#closeTicket) — the CSV bulk
    // import passes false, since one upload closing 248 tickets must never blast CS with 248 BCC
    // copies (see TicketCloseImportService).
    //
    // Every gate below is an early return checked OUTSIDE the try/catch, so a gate short-circuit
    // (an expected no-op, or a deliberate refusal) is never confused with a broker publish
    // failure in the logs. Order mirrors legacy-app's own handleTicket gate
    // (`!ticket.email || !ticket.trackingNumber || !ticket.lineItems`), with three CRM-side
    // additions layered in front of it: the enabled toggle, the non-prod-without-override
    // refusal, and the ManualHandling-only restriction.
    public void sendReplacementShipped(Ticket ticket, List<TicketLineItem> lineItems, String carrier,
                                        boolean includeBcc) {
        if (!properties.enabled()) {
            return;
        }
        String overrideRecipient = blankToNull(properties.recipientOverride());
        // dev/test point at the real staging crm_dev, whose ticket.email holds real buyer
        // addresses, and share prod's broker/mail consumer — this is the layer that stops a
        // stray `enabled=true` outside prod from mailing an actual customer even with the
        // recipientOverride safety net unset. Deliberately the only profile-aware bean in this
        // codebase (see this property's own comment in application.properties).
        if (overrideRecipient == null && !environment.matchesProfiles("prod")) {
            log.warn("Refusing to send replacement-shipped mail for ticket {}: not running in the prod profile " +
                    "and no recipientOverride is configured", ticket.getId());
            return;
        }
        // Silent, not a WARN: Replacement/Refund tickets have their own legacy-app-side flows
        // already, and mailing them here too would double-mail the buyer.
        if (ticket.getAction() != TicketAction.ManualHandling) {
            return;
        }
        if (blankToNull(ticket.getEmail()) == null || blankToNull(ticket.getTrackingNumber()) == null
                || lineItems == null || lineItems.isEmpty()) {
            return;
        }
        ChannelMail channelMail = properties.channels() == null ? null
                : properties.channels().forSourceSystem(ticket.getSourceSystem());
        if (channelMail == null) {
            // Not "crm" (createTicket always stamps a real channel) and not a broker/publish
            // problem — a channel with no mail config bound yet (see
            // ReplacementShippedMailProperties.Channels' own comment). Never throw: this runs
            // inside TicketCloseService#close, and a missing mail config must not fail the close
            // itself.
            log.warn("No replacement-shipped mail config for ticket {}'s channel ({}); skipping notification",
                    ticket.getId(), ticket.getSourceSystem());
            return;
        }

        try {
            MailTO mail = new MailTO();
            mail.setFrom(channelMail.fromAddress());
            String recipient = overrideRecipient != null ? overrideRecipient : ticket.getEmail();
            mail.setTo(List.of(recipient));
            // The override replaces notifyBcc too (not just the To), so a redirected test mail
            // never also reaches CS.
            if (overrideRecipient == null && includeBcc) {
                List<String> bcc = parseCommaSeparated(properties.notifyBcc());
                if (!bcc.isEmpty()) {
                    mail.setBcc(bcc);
                }
            }
            mail.setSubject(overrideRecipient != null
                    ? "[REDIRECTED from " + ticket.getEmail() + "] " + channelMail.subject()
                    : channelMail.subject());
            mail.setBodyContent(buildBody(ticket, lineItems, carrier, channelMail.signOff()));
            rabbitTemplate.convertAndSend(MAIL_EXCHANGE, MAIL_ROUTING_KEY, mail);
        } catch (Exception e) {
            // A failed publish must not fail the close itself — same "log and move on" tolerance
            // FailedTicketNotificationMailService already applies to its own digest publish.
            log.warn("Failed to publish replacement-shipped MailTO for ticket {}: {}", ticket.getId(), e.toString());
        }
    }

    private String buildBody(Ticket ticket, List<TicketLineItem> lineItems, String carrier, String signOff) {
        String orderNumber = blankToNull(ticket.getOrderNumber());
        String amazonOrderId = orderNumber == null ? "" : "In regards to Amazon Order# " + escape(orderNumber);
        String escapedCarrier = escape(carrier);
        return BODY_TEMPLATE.formatted(amazonOrderId, buildLineItemMessage(lineItems), escapedCarrier,
                escapedCarrier, escape(ticket.getTrackingNumber()), signOff);
    }

    // Faithful port of legacy-app's buildLineItemMessage: group by sku + ";" + channelSku (same sku
    // can map to a different channelSku with a different catalog name — merging would show the
    // buyer the wrong product), sorted by that key (TreeMap's natural String order == Groovy's
    // `.sort{it}`), a literal " " element between groups, everything joined with a literal
    // "<br/>". A row with a blank sku is dropped entirely, never contributing a group of its own.
    //
    // Two deliberate divergences from legacy-app's own version, both intentional:
    //   - "Item Name" is omitted when channelProductName is null, never rendered as the literal
    //     "null" legacy-app prints on a local catalog miss — every pre-DDL row is null, so preserving
    //     that would put "Item Name : null" in every email on day one.
    //   - every text field here is HTML-escaped; legacy-app's GString template engine escaped
    //     nothing, so this is a deliberate hardening, not parity.
    private String buildLineItemMessage(List<TicketLineItem> lineItems) {
        Map<String, List<TicketLineItem>> grouped = new TreeMap<>();
        for (TicketLineItem item : lineItems) {
            if (item.getSku() == null || item.getSku().isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(item.getSku() + ";" + item.getChannelSku(), k -> new ArrayList<>()).add(item);
        }

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<TicketLineItem>> group : grouped.entrySet()) {
            if (!parts.isEmpty()) {
                parts.add(" ");
            }
            String sku = group.getKey().split(";", -1)[0];
            parts.add("Item SKU # : " + escape(sku));
            // One "Item Name" per group (not per row) — every row in a group shares the same
            // sku+channelSku, so they were all resolved from the same legacy-app lookup at save time
            // (see TicketController#persistLineItems) and carry the same channelProductName.
            String channelProductName = group.getValue().get(0).getChannelProductName();
            if (channelProductName != null) {
                parts.add("Item Name : " + escape(channelProductName));
            }
            for (TicketLineItem item : group.getValue()) {
                if (item.getName() != null && !item.getName().isBlank()) {
                    parts.add("Item Part Name : " + escape(item.getName()));
                }
                parts.add("Item Quantity : " + (item.getQuantity() == null ? 0 : item.getQuantity().intValue()));
            }
        }
        return String.join("<br/>", parts);
    }

    private static List<String> parseCommaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
