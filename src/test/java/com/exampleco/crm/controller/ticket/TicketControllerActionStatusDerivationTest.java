package com.exampleco.crm.controller.ticket;

import com.exampleco.core.dto.ticket.RefundType;
import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.core.dto.ticket.TicketStatus;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.dto.ticket.TicketWriteRequest;
import com.exampleco.crm.repository.ticket.AddressRepository;
import com.exampleco.crm.repository.ticket.CustomerRepository;
import com.exampleco.crm.repository.ticket.S2UserRepository;
import com.exampleco.crm.repository.ticket.TicketCommentRepository;
import com.exampleco.crm.repository.ticket.TicketLineItemRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;
import com.exampleco.crm.service.auth.ChannelAccessService;
import com.exampleco.crm.service.ticket.LegacyLineItemResolutionClient;
import com.exampleco.crm.service.ticket.PickingListExportService;
import com.exampleco.crm.service.ticket.TicketCloseService;
import com.exampleco.crm.service.ticket.TicketCommentService;
import com.exampleco.crm.service.ticket.TicketFailService;
import com.exampleco.crm.service.ticket.TicketIdLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// PUT /api/tickets/{id} (via TicketController#applyFields) must derive Ticket.status
// from the request's action the same way legacy-app's own
// AmazonSalesOrderProviderService#createTicketLineItems does — NoAction closes the ticket,
// Replacement/Refund/ManualHandling put it in Processing — and must only ever persist a
// refundType alongside a Refund action. Plain Mockito unit test, no Spring context or database,
// same shape as TicketControllerRefundEnrichmentTest.
class TicketControllerActionStatusDerivationTest {

    private Ticket update(TicketAction existingAction, String requestAction, String requestRefundType) {
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

        TicketController controller = new TicketController(ticketRepository, ticketLineItemRepository,
                ticketCommentRepository, addressRepository, s2UserRepository, customerRepository,
                pickingListExportService, ticketIdLock, ticketCommentService, ticketCloseService, ticketFailService,
                legacyLineItemResolutionClient, channelAccessService);

        Ticket existing = new Ticket();
        existing.setId(1L);
        existing.setSourceSystem("crm");
        existing.setAction(existingAction);
        existing.setStatus(TicketStatus.New);

        when(ticketRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TicketWriteRequest request = new TicketWriteRequest(
                "Jane Doe", "jane@example.com", null, "ORDER-1",
                requestAction, null, null, requestRefundType,
                null, null, null, null, null, null,
                null, null, null);

        return controller.updateTicket(1L, request,
                Mono.just(new UsernamePasswordAuthenticationToken("agent", null, List.of()))).block();
    }

    @Test
    void noActionClosesTheTicket() {
        Ticket saved = update(TicketAction.NoAction, TicketAction.NoAction.name(), null);
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.Closed);
    }

    @ParameterizedTest
    @EnumSource(value = TicketAction.class, names = {"Replacement", "Refund", "ManualHandling"})
    void processingActionsSetStatusToProcessing(TicketAction action) {
        Ticket saved = update(action, action.name(), null);
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.Processing);
    }

    @Test
    void refundActionPersistsRefundType() {
        Ticket saved = update(TicketAction.Refund, TicketAction.Refund.name(),
                RefundType.CustomerReturn.name());
        assertThat(saved.getRefundType()).isEqualTo(RefundType.CustomerReturn);
    }

    @Test
    void nonRefundActionClearsAnySubmittedRefundType() {
        Ticket saved = update(TicketAction.Replacement, TicketAction.Replacement.name(),
                RefundType.CustomerReturn.name());
        assertThat(saved.getRefundType()).isNull();
    }
}
