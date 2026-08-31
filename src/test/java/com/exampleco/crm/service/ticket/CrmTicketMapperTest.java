package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.ticket.CrmTicketTO;
import com.exampleco.core.dto.ticket.RefundType;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.core.dto.ticket.TicketReason;
import com.exampleco.core.dto.ticket.TicketStatus;
import com.exampleco.crm.dto.ticket.AddressDetail;
import com.exampleco.crm.dto.ticket.CustomerDetail;
import com.exampleco.crm.dto.ticket.HandlerDetail;
import com.exampleco.crm.dto.ticket.LineItemSummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// CrmTicketMapper is the piece of the earlier "CRM ticket API 的 TO" submodule commit
// left unimplemented on this side (exampleco-core defines CrmTicketTO's shape; nothing here ever
// constructed one). Enum fields must carry the enum *name*, not the numeric code CrmTicketTO's
// own javadoc calls out legacy-app as mapping back via valueOf -- the opposite of every other
// TicketController API convention, which is why it's worth a dedicated regression test.
class CrmTicketMapperTest {

    private Ticket baseTicket() {
        Ticket ticket = new Ticket();
        ticket.setId(42L);
        ticket.setSourceSystem("az-us");
        ticket.setAction(TicketAction.Replacement);
        ticket.setStatus(TicketStatus.Processing);
        ticket.setReason(TicketReason.Defective);
        ticket.setRefundType(RefundType.CustomerReturn);
        ticket.setOrderNumber("111-2222222-3333333");
        ticket.setReplacementOrderNumber("111-4444444-5555555");
        ticket.setTrackingNumber("1Z999AA10123456784");
        ticket.setEmail("buyer@example.com");
        ticket.setCustomerName("Jane Buyer");
        ticket.setDescription("Arrived broken");
        ticket.setLastException("[Exception] resolveLineItems timed out");
        ticket.setStarred(true);
        ticket.setOutOfWarrant(false);
        ticket.setDateCreated(LocalDateTime.of(2026, 8, 1, 10, 0));
        ticket.setLastUpdated(LocalDateTime.of(2026, 8, 2, 11, 30));
        return ticket;
    }

    @Test
    void mapsScalarAndEnumFieldsAsTheSharedEnumsThemselves() {
        CrmTicketTO to = CrmTicketMapper.toTO(baseTicket());

        assertThat(to.getId()).isEqualTo(42L);
        assertThat(to.getSourceSystem()).isEqualTo("az-us");
        // The TO holds exampleco-core's enums, not their names — legacy-app compiles against these
        // same four types, so a constant either side is missing is a compile error there rather
        // than a string that silently fails to match at runtime.
        assertThat(to.getAction()).isEqualTo(TicketAction.Replacement);
        assertThat(to.getStatus()).isEqualTo(TicketStatus.Processing);
        assertThat(to.getReason()).isEqualTo(TicketReason.Defective);
        assertThat(to.getRefundType()).isEqualTo(RefundType.CustomerReturn);
        assertThat(to.getOrderNumber()).isEqualTo("111-2222222-3333333");
        assertThat(to.getReplacementOrderNumber()).isEqualTo("111-4444444-5555555");
        assertThat(to.getTrackingNumber()).isEqualTo("1Z999AA10123456784");
        assertThat(to.getEmail()).isEqualTo("buyer@example.com");
        assertThat(to.getCustomerName()).isEqualTo("Jane Buyer");
        assertThat(to.getDescription()).isEqualTo("Arrived broken");
        assertThat(to.getLastException()).isEqualTo("[Exception] resolveLineItems timed out");
        assertThat(to.getStarred()).isTrue();
        assertThat(to.getOutOfWarrant()).isFalse();
        assertThat(to.getDateCreated()).isNotNull();
        assertThat(to.getLastUpdated()).isNotNull();
    }

    @Test
    void nullEnumFieldsMapToNullNames() {
        Ticket ticket = new Ticket();
        ticket.setId(1L);

        CrmTicketTO to = CrmTicketMapper.toTO(ticket);

        assertThat(to.getAction()).isNull();
        assertThat(to.getStatus()).isNull();
        assertThat(to.getReason()).isNull();
        assertThat(to.getRefundType()).isNull();
    }

    @Test
    void mapsAddressHandlerAndCustomerWhenEnriched() {
        Ticket ticket = baseTicket();
        ticket.setAddress(new AddressDetail("Jane Buyer", "123 Main St", "Apt 4", "Springfield",
                "IL", "62704", "US", "555-1234"));
        ticket.setHandler(new HandlerDetail("jdoe", "jdoe@example.com", "John Doe"));
        ticket.setCustomer(new CustomerDetail("cust-uuid-1", "Jane Buyer", "buyer@example.com"));

        CrmTicketTO to = CrmTicketMapper.toTO(ticket);

        assertThat(to.getAddress().getName()).isEqualTo("Jane Buyer");
        assertThat(to.getAddress().getAddressLine1()).isEqualTo("123 Main St");
        assertThat(to.getAddress().getState()).isEqualTo("IL");
        assertThat(to.getHandler().getUsername()).isEqualTo("jdoe");
        assertThat(to.getHandler().getEmail()).isEqualTo("jdoe@example.com");
        assertThat(to.getCustomer().getId()).isEqualTo("cust-uuid-1");
        assertThat(to.getCustomer().getEmail()).isEqualTo("buyer@example.com");
    }

    @Test
    void unenrichedAddressHandlerAndCustomerMapToNull() {
        CrmTicketTO to = CrmTicketMapper.toTO(baseTicket());

        assertThat(to.getAddress()).isNull();
        assertThat(to.getHandler()).isNull();
        assertThat(to.getCustomer()).isNull();
    }

    @Test
    void mapsLineItemsIncludingChannelSkuAndQuantity() {
        Ticket ticket = baseTicket();
        ticket.setLineItems(List.of(
                new LineItemSummary(100L, "SKU-1", "Widget", 2.0, "CHANNEL-SKU-1", 0.0),
                new LineItemSummary(101L, "SKU-2", "Gadget", 3.0, "CHANNEL-SKU-2", 15.0)));

        CrmTicketTO to = CrmTicketMapper.toTO(ticket);

        assertThat(to.getLineItems()).hasSize(2);
        assertThat(to.getLineItems().get(0).getSku()).isEqualTo("SKU-1");
        assertThat(to.getLineItems().get(0).getChannelSku()).isEqualTo("CHANNEL-SKU-1");
        assertThat(to.getLineItems().get(0).getQuantity()).isEqualTo(2.0);
        assertThat(to.getLineItems().get(1).getRefundAmount()).isEqualTo(15.0);
    }

    @Test
    void noLineItemsMapsToEmptyListNotNull() {
        CrmTicketTO to = CrmTicketMapper.toTO(baseTicket());

        assertThat(to.getLineItems()).isNotNull().isEmpty();
    }
}
