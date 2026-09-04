package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.core.dto.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.domain.ticket.TicketLineItem;
import com.exampleco.crm.repository.ticket.TicketLineItemRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// replaces legacy-app's own NotifyRefundTicketsJob/CrmTicketService#getOpenRefundTickets/
// SalesOrderService#notifyRefundTickets. Unlike legacy-app (one BasicProfile country per app
// instance), a single CRM run sees every channel at once, so the US-vs-else routing that legacy-app
// expressed as one whole-app branch is expressed here as a per-ticket split — that split is the
// main thing under test; message rendering itself is RefundTicketNotificationServiceTest's job.
@ExtendWith(MockitoExtension.class)
class NotifyRefundTicketsServiceTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketLineItemRepository ticketLineItemRepository;
    @Mock
    private RefundTicketNotificationService notificationService;

    private NotifyRefundTicketsService newService(String memberChatIdsCsv, String gsmAlarmGroupChatId) {
        return new NotifyRefundTicketsService(ticketRepository, ticketLineItemRepository, notificationService,
                memberChatIdsCsv, gsmAlarmGroupChatId);
    }

    private Ticket ticket(Long id, String sourceSystem, String orderNumber) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setAction(TicketAction.Refund);
        ticket.setStatus(TicketStatus.Processing);
        ticket.setSourceSystem(sourceSystem);
        ticket.setOrderNumber(orderNumber);
        return ticket;
    }

    private void stubNoLineItems() {
        lenient().when(ticketLineItemRepository.findByTicketIdIn(anyList())).thenReturn(List.of());
    }

    @Test
    void noOpenRefundTickets_returnsZeroAndNeverNotifies() {
        when(ticketRepository.findByActionAndOrderNumberIsNotNullAndStatusNotIn(eq(TicketAction.Refund), anyList()))
                .thenReturn(List.of());
        NotifyRefundTicketsService service = newService("111", "999");

        int count = service.run();

        assertThat(count).isZero();
        verifyNoInteractions(ticketLineItemRepository, notificationService);
    }

    @Test
    void queriesRefundActionExcludingClosedAndCancelled() {
        when(ticketRepository.findByActionAndOrderNumberIsNotNullAndStatusNotIn(any(), any())).thenReturn(List.of());
        NotifyRefundTicketsService service = newService("111", "999");

        service.run();

        ArgumentCaptor<List<TicketStatus>> excludedCaptor = ArgumentCaptor.forClass(List.class);
        verify(ticketRepository).findByActionAndOrderNumberIsNotNullAndStatusNotIn(eq(TicketAction.Refund), excludedCaptor.capture());
        assertThat(excludedCaptor.getValue()).containsExactlyInAnyOrder(TicketStatus.Closed, TicketStatus.Cancelled);
    }

    @Test
    void usChannelTicket_isRoutedToMemberChatIdsOnly() {
        Ticket usTicket = ticket(1L, "az-us", "ORDER-US");
        when(ticketRepository.findByActionAndOrderNumberIsNotNullAndStatusNotIn(any(), any())).thenReturn(List.of(usTicket));
        stubNoLineItems();
        NotifyRefundTicketsService service = newService("111,222", "999");

        service.run();

        verify(notificationService).sendDigest(eq(List.of(usTicket)), any(), eq(List.of(111L, 222L)));
        verify(notificationService).sendDigest(eq(List.of()), any(), eq(List.of(999L)));
    }

    @Test
    void nonUsChannelTicket_isRoutedToGsmAlarmGroupOnly() {
        Ticket caTicket = ticket(2L, "az-ca", "ORDER-CA");
        when(ticketRepository.findByActionAndOrderNumberIsNotNullAndStatusNotIn(any(), any())).thenReturn(List.of(caTicket));
        stubNoLineItems();
        NotifyRefundTicketsService service = newService("111", "999");

        service.run();

        verify(notificationService).sendDigest(eq(List.of()), any(), eq(List.of(111L)));
        verify(notificationService).sendDigest(eq(List.of(caTicket)), any(), eq(List.of(999L)));
    }

    // Safe default: an unmapped/unknown sourceSystem (including the "crm" write placeholder)
    // must never be silently treated as US.
    @Test
    void unknownChannel_isTreatedAsNonUs() {
        Ticket unknownTicket = ticket(3L, "crm", "ORDER-UNKNOWN");
        when(ticketRepository.findByActionAndOrderNumberIsNotNullAndStatusNotIn(any(), any())).thenReturn(List.of(unknownTicket));
        stubNoLineItems();
        NotifyRefundTicketsService service = newService("111", "999");

        service.run();

        verify(notificationService).sendDigest(eq(List.of()), any(), eq(List.of(111L)));
        verify(notificationService).sendDigest(eq(List.of(unknownTicket)), any(), eq(List.of(999L)));
    }

    @Test
    void mixedChannels_splitAcrossBothDigests() {
        Ticket usTicket = ticket(1L, "az-us", "ORDER-US");
        Ticket auTicket = ticket(2L, "az-au", "ORDER-AU");
        when(ticketRepository.findByActionAndOrderNumberIsNotNullAndStatusNotIn(any(), any()))
                .thenReturn(List.of(usTicket, auTicket));
        stubNoLineItems();
        NotifyRefundTicketsService service = newService("111", "999");

        int count = service.run();

        assertThat(count).isEqualTo(2);
        verify(notificationService).sendDigest(eq(List.of(usTicket)), any(), eq(List.of(111L)));
        verify(notificationService).sendDigest(eq(List.of(auTicket)), any(), eq(List.of(999L)));
    }

    @Test
    void blankChatIdConfig_resultsInEmptyRecipientList() {
        Ticket usTicket = ticket(1L, "az-us", "ORDER-US");
        when(ticketRepository.findByActionAndOrderNumberIsNotNullAndStatusNotIn(any(), any())).thenReturn(List.of(usTicket));
        stubNoLineItems();
        NotifyRefundTicketsService service = newService("", "");

        service.run();

        verify(notificationService).sendDigest(eq(List.of(usTicket)), any(), eq(List.of()));
        verify(notificationService).sendDigest(eq(List.of()), any(), eq(List.of()));
    }

    @Test
    void nonNumericChatId_isIgnoredRatherThanThrowing() {
        Ticket usTicket = ticket(1L, "az-us", "ORDER-US");
        when(ticketRepository.findByActionAndOrderNumberIsNotNullAndStatusNotIn(any(), any())).thenReturn(List.of(usTicket));
        stubNoLineItems();
        NotifyRefundTicketsService service = newService("111, not-a-number ,222", "999");

        service.run();

        verify(notificationService).sendDigest(eq(List.of(usTicket)), any(), eq(List.of(111L, 222L)));
    }

    @Test
    void lineItemSkus_areBatchedByTicketIdAndPassedToNotificationService() {
        Ticket usTicket = ticket(1L, "az-us", "ORDER-US");
        when(ticketRepository.findByActionAndOrderNumberIsNotNullAndStatusNotIn(any(), any())).thenReturn(List.of(usTicket));
        TicketLineItem item1 = new TicketLineItem();
        item1.setTicketId(1L);
        item1.setSku("SKU-1");
        TicketLineItem item2 = new TicketLineItem();
        item2.setTicketId(1L);
        item2.setSku("SKU-2");
        when(ticketLineItemRepository.findByTicketIdIn(List.of(1L))).thenReturn(List.of(item1, item2));
        NotifyRefundTicketsService service = newService("111", "999");

        service.run();

        ArgumentCaptor<Map<Long, List<String>>> skusCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).sendDigest(eq(List.of(usTicket)), skusCaptor.capture(), eq(List.of(111L)));
        assertThat(skusCaptor.getValue().get(1L)).containsExactly("SKU-1", "SKU-2");
    }
}
