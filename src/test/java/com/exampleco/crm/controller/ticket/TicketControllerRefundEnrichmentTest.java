package com.exampleco.crm.controller.ticket;

import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.crm.domain.ticket.TicketLineItem;
import com.exampleco.crm.repository.ticket.AddressRepository;
import com.exampleco.crm.repository.ticket.CustomerRepository;
import com.exampleco.crm.repository.ticket.S2UserRepository;
import com.exampleco.crm.repository.ticket.TicketCommentRepository;
import com.exampleco.crm.repository.ticket.TicketLineItemRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;
import com.exampleco.crm.service.auth.ChannelAccessService;
import com.exampleco.crm.service.ticket.LegacyLineItemResolutionClient;
import com.exampleco.crm.service.ticket.TicketCloseService;
import com.exampleco.crm.service.ticket.TicketCommentService;
import com.exampleco.crm.service.ticket.TicketFailService;
import com.exampleco.crm.service.ticket.TicketIdLock;
import com.exampleco.crm.service.ticket.PickingListExportService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// GET /api/tickets/{id} (via TicketController#enrich) now has to sum a Refund ticket's
// per-line refundAmount into Ticket#totalRefundAmount, the same "batched pass, not a separate
// query" shape as every other enrich()-derived field (lastException, handlerUsername, ...). This
// is a plain Mockito unit test, not a @SpringBootTest — CrmApplicationTests is the only other
// test in this app and needs a real datasource, which isn't available in every environment this
// runs in; this test needs neither Spring context nor a database.
class TicketControllerRefundEnrichmentTest {

    @Test
    void enrichSumsRefundAmountAcrossARefundTicketsLineItems() {
        TicketRepository ticketRepository = mock(TicketRepository.class);
        TicketLineItemRepository ticketLineItemRepository = mock(TicketLineItemRepository.class);
        TicketCommentRepository ticketCommentRepository = mock(TicketCommentRepository.class);
        AddressRepository addressRepository = mock(AddressRepository.class);
        S2UserRepository s2UserRepository = mock(S2UserRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        PickingListExportService pickingListExportService = mock(PickingListExportService.class);
        TicketIdLock ticketIdLock = mock(TicketIdLock.class);
        TicketCommentService ticketCommentService = mock(TicketCommentService.class);
        TicketCloseService ticketCloseService = mock(TicketCloseService.class);
        TicketFailService ticketFailService = mock(TicketFailService.class);
        LegacyLineItemResolutionClient legacyLineItemResolutionClient = mock(LegacyLineItemResolutionClient.class);
        ChannelAccessService channelAccessService = mock(ChannelAccessService.class);
        when(channelAccessService.isChannelAuthorized(any(), any())).thenReturn(true);

        TicketController controller = new TicketController(ticketRepository, ticketLineItemRepository,
                ticketCommentRepository, addressRepository, s2UserRepository, customerRepository,
                pickingListExportService, ticketIdLock, ticketCommentService, ticketCloseService, ticketFailService,
                legacyLineItemResolutionClient, channelAccessService);

        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setAction(TicketAction.Refund);
        ticket.setSourceSystem("crm");

        TicketLineItem refundLine1 = new TicketLineItem();
        refundLine1.setTicketId(1L);
        refundLine1.setSku("SKU-1");
        refundLine1.setQuantity(2.0);
        refundLine1.setRefundAmount(12.5);

        TicketLineItem refundLine2 = new TicketLineItem();
        refundLine2.setTicketId(1L);
        refundLine2.setSku("SKU-2");
        refundLine2.setQuantity(1.0);
        refundLine2.setRefundAmount(7.5);

        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(ticketLineItemRepository.findByTicketIdIn(List.of(1L))).thenReturn(List.of(refundLine1, refundLine2));
        when(s2UserRepository.findAll()).thenReturn(List.of());
        when(ticketCommentRepository.findByTicketIdInOrderByDateCreatedAsc(List.of(1L))).thenReturn(List.of());

        Object result = controller.getTicket(1L, null, null,
                Mono.just(new UsernamePasswordAuthenticationToken("agent", null, List.of()))).block();

        assertThat(result).isInstanceOf(Ticket.class);
        Ticket enriched = (Ticket) result;
        assertThat(enriched.getLineItems()).hasSize(2);
        assertThat(enriched.getLineItems()).extracting("refundAmount").containsExactlyInAnyOrder(12.5, 7.5);
        assertThat(enriched.getTotalRefundAmount()).isEqualTo(20.0);
    }
}
