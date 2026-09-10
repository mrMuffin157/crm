package com.exampleco.crm.domain.ticket;

import com.exampleco.core.dto.ticket.CrmTicketTO;
import com.exampleco.core.dto.ticket.RefundType;
import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.core.dto.ticket.TicketReason;
import com.exampleco.core.dto.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four ticket enums now live in exampleco-core and are shared with legacy-app. Both the Ticket
 * entity (this app's own React frontend) and CrmTicketTO (legacy-app's M2M wire format) serialize
 * status/action/reason/refundType as the enum's name() — Jackson's default for an enum with no
 * {@code @JsonValue} — rather than the legacy numeric id frontend/src/ticketEnums.js and the
 * Ticket entity used to carry. This pins that both stayed in sync now that the split between
 * them (Ticket's own now-removed per-property @JsonSerialize) is gone.
 */
class TicketEnumWireFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ticketEntitySerializesEnumsAsNamesForTheFrontend() {
        Ticket ticket = new Ticket();
        ticket.setStatus(TicketStatus.Processing);
        ticket.setAction(TicketAction.Replacement);
        ticket.setReason(TicketReason.ShippingDamage);
        ticket.setRefundType(RefundType.CustomerReturn);

        String json = mapper.writeValueAsString(ticket);

        assertThat(json).contains("\"status\":\"Processing\"")
                .contains("\"action\":\"Replacement\"")
                .contains("\"reason\":\"ShippingDamage\"")
                .contains("\"refundType\":\"CustomerReturn\"");
    }

    @Test
    void crmTicketTOSerializesTheSameEnumsAsNamesForLegacyApp() {
        CrmTicketTO to = new CrmTicketTO();
        to.setStatus(TicketStatus.Processing);
        to.setAction(TicketAction.Replacement);
        to.setReason(TicketReason.ShippingDamage);
        to.setRefundType(RefundType.CustomerReturn);

        String json = mapper.writeValueAsString(to);

        assertThat(json).contains("\"status\":\"Processing\"")
                .contains("\"action\":\"Replacement\"")
                .contains("\"reason\":\"ShippingDamage\"")
                .contains("\"refundType\":\"CustomerReturn\"");
    }

    @Test
    void aNumericCodeIsRejectedRatherThanReadAsAnOrdinalIndex() {
        assertThat(TicketStatus.values()[2]).isEqualTo(TicketStatus.Closed);
        assertThat(TicketStatus.fromCode("2")).isEqualTo(TicketStatus.Processing);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mapper.readValue("{\"status\":\"2\"}", CrmTicketTO.class)))
                .isNotNull();
    }
}
