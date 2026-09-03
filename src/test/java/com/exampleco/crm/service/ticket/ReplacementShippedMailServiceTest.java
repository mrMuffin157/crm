package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.common.MailTO;
import com.exampleco.core.dto.ticket.TicketAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.env.Environment;

import java.util.List;
import com.exampleco.crm.config.ticket.ReplacementShippedMailProperties;
import com.exampleco.crm.config.ticket.ReplacementShippedMailProperties.ChannelMail;
import com.exampleco.crm.config.ticket.ReplacementShippedMailProperties.Channels;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.domain.ticket.TicketLineItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// ReplacementShippedMailService replaces legacy-app's non-existent ticketClosed endpoint —
// CRM now builds the buyer-facing "replacement shipped" HTML itself and publishes it as a MailTO
// onto the shared "message" exchange, same broker contract FailedTicketNotificationMailService
// already uses. Every gate here is deliberately pinned: this mail can reach a real Amazon buyer,
// and the whole reason the enabled/recipientOverride/prod-profile safety layers exist is that
// nothing else in this codebase stops a stray click in dev from mailing an actual customer.
//
// carrier-derivation itself (the "1Z" rule vs. the CSV import's own verbatim value) is NOT
// re-tested here — TicketCloseService already resolves carrier before calling this service (see
// TicketCloseServiceTest's own carrier tests), so this service only ever renders whatever string
// it's handed.
@ExtendWith(MockitoExtension.class)
class ReplacementShippedMailServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private Environment environment;

    private static final ChannelMail US_CHANNEL = new ChannelMail("brandc-us@example.com",
            "Replacement from EPFamily Direct products", "EPFamily Customer Support");
    private static final ChannelMail DECO_CHANNEL = new ChannelMail("brandb-us@example.com",
            "Replacement from BrandB Direct products", "BrandB Direct&nbsp;Customer Support");

    private ReplacementShippedMailProperties properties(boolean enabled, String recipientOverride, String notifyBcc) {
        Channels channels = new Channels(US_CHANNEL, DECO_CHANNEL, null, null, null, null, null, null);
        return new ReplacementShippedMailProperties(enabled, recipientOverride, notifyBcc, channels);
    }

    private ReplacementShippedMailService newService(ReplacementShippedMailProperties properties) {
        return new ReplacementShippedMailService(rabbitTemplate, properties, environment);
    }

    private Ticket manualHandlingTicket() {
        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setAction(TicketAction.ManualHandling);
        ticket.setSourceSystem("az-us");
        ticket.setEmail("buyer@example.com");
        ticket.setTrackingNumber("9400100000000000000001");
        ticket.setOrderNumber("112-1234567-1234567");
        return ticket;
    }

    private TicketLineItem lineItem(String sku, String channelSku, String name, Double quantity, String channelProductName) {
        TicketLineItem item = new TicketLineItem();
        item.setSku(sku);
        item.setChannelSku(channelSku);
        item.setName(name);
        item.setQuantity(quantity);
        item.setChannelProductName(channelProductName);
        return item;
    }

    private List<TicketLineItem> oneLineItem() {
        return List.of(lineItem("SKU-1", "CH-1", "Widget", 1.0, "Catalog Widget"));
    }

    private MailTO capturedMail() {
        ArgumentCaptor<MailTO> captor = ArgumentCaptor.forClass(MailTO.class);
        verify(rabbitTemplate).convertAndSend(eq("message"), eq("message"), captor.capture());
        return captor.getValue();
    }

    // --- Gates, in order --------------------------------------------------------------------

    @Test
    void disabled_neverPublishes() {
        ReplacementShippedMailService service = newService(properties(false, null, null));

        service.sendReplacementShipped(manualHandlingTicket(), oneLineItem(), "USPS", true);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void nonProdWithNoOverride_refusesToPublish() {
        when(environment.matchesProfiles("prod")).thenReturn(false);
        ReplacementShippedMailService service = newService(properties(true, null, null));

        service.sendReplacementShipped(manualHandlingTicket(), oneLineItem(), "USPS", true);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void nonProdWithOverride_stillPublishes() {
        // Override bypasses the prod check entirely (short-circuits before environment is even
        // consulted) — the one supported way to exercise this mail outside prod.
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(manualHandlingTicket(), oneLineItem(), "USPS", true);

        verify(rabbitTemplate).convertAndSend(eq("message"), eq("message"), any(MailTO.class));
        verifyNoInteractions(environment);
    }

    @Test
    void prodWithNoOverride_publishesToTheRealBuyer() {
        when(environment.matchesProfiles("prod")).thenReturn(true);
        ReplacementShippedMailService service = newService(properties(true, null, null));

        service.sendReplacementShipped(manualHandlingTicket(), oneLineItem(), "USPS", true);

        MailTO mail = capturedMail();
        assertThat(mail.getTo()).containsExactly("buyer@example.com");
    }

    @Test
    void replacementAction_isSilentlySkipped() {
        Ticket ticket = manualHandlingTicket();
        ticket.setAction(TicketAction.Replacement);
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(ticket, oneLineItem(), "USPS", true);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void refundAction_isSilentlySkipped() {
        Ticket ticket = manualHandlingTicket();
        ticket.setAction(TicketAction.Refund);
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(ticket, oneLineItem(), "USPS", true);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void blankEmail_isSkipped() {
        Ticket ticket = manualHandlingTicket();
        ticket.setEmail("  ");
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(ticket, oneLineItem(), "USPS", true);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void blankTrackingNumber_isSkipped() {
        Ticket ticket = manualHandlingTicket();
        ticket.setTrackingNumber(null);
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(ticket, oneLineItem(), "USPS", true);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void emptyLineItems_isSkipped() {
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(manualHandlingTicket(), List.of(), "USPS", true);

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void noChannelMailConfiguredForChannel_logsAndSkipsRatherThanThrowing() {
        Ticket ticket = manualHandlingTicket();
        ticket.setSourceSystem("az-mx");
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(ticket, oneLineItem(), "USPS", true);

        verifyNoInteractions(rabbitTemplate);
    }

    // --- Recipient override: replaces To AND replaces (not adds to) the BCC list -------------

    @Test
    void recipientOverride_replacesRecipientAndPrefixesSubject() {
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(manualHandlingTicket(), oneLineItem(), "USPS", true);

        MailTO mail = capturedMail();
        assertThat(mail.getTo()).containsExactly("qa@example.com");
        assertThat(mail.getSubject()).isEqualTo("[REDIRECTED from buyer@example.com] Replacement from EPFamily Direct products");
    }

    @Test
    void recipientOverride_blanksTheBccEvenWhenIncludeBccIsTrue() {
        ReplacementShippedMailService service =
                newService(properties(true, "qa@example.com", "cs1@example.com,cs2@example.com"));

        service.sendReplacementShipped(manualHandlingTicket(), oneLineItem(), "USPS", true);

        MailTO mail = capturedMail();
        assertThat(mail.getBcc()).isNullOrEmpty();
    }

    @Test
    void noOverride_manualClose_includesTheConfiguredBccList() {
        when(environment.matchesProfiles("prod")).thenReturn(true);
        ReplacementShippedMailService service =
                newService(properties(true, null, "cs1@example.com,cs2@example.com"));

        service.sendReplacementShipped(manualHandlingTicket(), oneLineItem(), "USPS", true);

        MailTO mail = capturedMail();
        assertThat(mail.getBcc()).containsExactly("cs1@example.com", "cs2@example.com");
    }

    @Test
    void noOverride_csvImportPath_neverIncludesBcc() {
        when(environment.matchesProfiles("prod")).thenReturn(true);
        ReplacementShippedMailService service =
                newService(properties(true, null, "cs1@example.com,cs2@example.com"));

        service.sendReplacementShipped(manualHandlingTicket(), oneLineItem(), "USPS", false);

        MailTO mail = capturedMail();
        assertThat(mail.getBcc()).isNullOrEmpty();
    }

    // --- Per-channel from/subject/sign-off ----------------------------------------------------

    @Test
    void decoChannel_usesItsOwnFromSubjectAndUnescapedSignOff() {
        Ticket ticket = manualHandlingTicket();
        ticket.setSourceSystem("az-deco");
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(ticket, oneLineItem(), "USPS", true);

        MailTO mail = capturedMail();
        assertThat(mail.getFrom()).isEqualTo("brandb-us@example.com");
        assertThat(mail.getSubject()).isEqualTo("[REDIRECTED from buyer@example.com] Replacement from BrandB Direct products");
        // signOff is operator-authored HTML, deliberately not escaped — "&nbsp;" must survive,
        // not become "&amp;nbsp;".
        assertThat(mail.getBodyContent()).contains("BrandB Direct&nbsp;Customer Support");
    }

    // --- Line-item message: grouping, ordering, per-row quantity, Item Name omission ---------

    @Test
    void lineItemMessage_groupsSortsAndOmitsItemNameWhenChannelProductNameIsNull() {
        List<TicketLineItem> lineItems = List.of(
                lineItem("SKU-B", "CH-B", "Red Widget", 3.0, null),
                lineItem("SKU-A", "CH-A", "Blue Widget", 2.0, "Widget A"),
                lineItem("SKU-A", "CH-A", null, 1.0, "Widget A"),
                lineItem("", "CH-C", "Dropped Row", 5.0, "Ignored"));
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(manualHandlingTicket(), lineItems, "USPS", true);

        MailTO mail = capturedMail();
        assertThat(mail.getBodyContent()).contains(
                "Item SKU # : SKU-A<br/>Item Name : Widget A<br/>Item Part Name : Blue Widget<br/>"
                        + "Item Quantity : 2<br/>Item Quantity : 1<br/> <br/>"
                        + "Item SKU # : SKU-B<br/>Item Part Name : Red Widget<br/>Item Quantity : 3");
        // The blank-sku row contributes nothing at all — not even an empty group.
        assertThat(mail.getBodyContent()).doesNotContain("Dropped Row");
    }

    @Test
    void amazonOrderId_blankOrderNumber_rendersEmptyParagraphNeverTheLiteralNull() {
        Ticket ticket = manualHandlingTicket();
        ticket.setOrderNumber(null);
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(ticket, oneLineItem(), "USPS", true);

        MailTO mail = capturedMail();
        assertThat(mail.getBodyContent()).contains("<p></p>");
        assertThat(mail.getBodyContent()).doesNotContain("null");
    }

    @Test
    void amazonOrderId_presentOrderNumber_isRenderedAndEscaped() {
        Ticket ticket = manualHandlingTicket();
        ticket.setOrderNumber("112-<script>-1234567");
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(ticket, oneLineItem(), "USPS", true);

        MailTO mail = capturedMail();
        assertThat(mail.getBodyContent()).contains("In regards to Amazon Order# 112-&lt;script&gt;-1234567");
    }

    // A buyer-supplied part name containing the literal template-variable syntax must never be
    // substituted by a later pass — this service uses String.formatted(), not ${} substitution,
    // specifically to prevent this.
    @Test
    void partNameContainingDollarBraceSyntax_isNeverSubstituted() {
        List<TicketLineItem> lineItems = List.of(lineItem("SKU-1", "CH-1", "Part ${carrier} special", 1.0, "Catalog"));
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(manualHandlingTicket(), lineItems, "USPS", true);

        MailTO mail = capturedMail();
        assertThat(mail.getBodyContent()).contains("Item Part Name : Part ${carrier} special");
    }

    // sku/name/trackingNumber are agent- or customer-controlled and must be escaped — legacy-app's
    // own GString template engine escaped nothing, so this is a deliberate hardening.
    @Test
    void skuNameAndTrackingNumber_areHtmlEscaped() {
        Ticket ticket = manualHandlingTicket();
        ticket.setTrackingNumber("1Z<b>999</b>");
        List<TicketLineItem> lineItems = List.of(lineItem("SKU&1", "CH-1", "<i>Widget</i>", 1.0, "Cat & Name"));
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(ticket, lineItems, "USPS", true);

        MailTO mail = capturedMail();
        assertThat(mail.getBodyContent()).contains("Item SKU # : SKU&amp;1");
        assertThat(mail.getBodyContent()).contains("Item Name : Cat &amp; Name");
        assertThat(mail.getBodyContent()).contains("Item Part Name : &lt;i&gt;Widget&lt;/i&gt;");
        assertThat(mail.getBodyContent()).contains("1Z&lt;b&gt;999&lt;/b&gt;");
    }

    // --- Broker failure is swallowed, never allowed to fail the close -------------------------

    @Test
    void publishFailure_isCaughtAndDoesNotThrow() {
        doThrow(new AmqpException("broker down") {}).when(rabbitTemplate)
                .convertAndSend(any(String.class), any(String.class), any(MailTO.class));
        ReplacementShippedMailService service = newService(properties(true, "qa@example.com", null));

        service.sendReplacementShipped(manualHandlingTicket(), oneLineItem(), "USPS", true);
    }
}
