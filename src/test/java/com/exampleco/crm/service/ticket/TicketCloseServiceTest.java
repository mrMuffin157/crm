package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.ticket.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import com.exampleco.crm.controller.ticket.TicketController;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.exception.ticket.TicketNotFoundException;
import com.exampleco.crm.repository.ticket.CustomerRepository;
import com.exampleco.crm.repository.ticket.TicketLineItemRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// TicketCloseService is shared by the manual POST /api/tickets/{id}/close path and the CSV bulk
// import (TicketCloseImportService) — covers the lock check, the optional tracking-number/
// comment semantics, line-item population, and the carrier rule this depends on: null
// derives UPS/USPS from the tracking number's "1Z" prefix (the manual close path's own behavior,
// unchanged), a non-null value (only ever passed by the CSV import) is used verbatim and can be
// something the derive rule could never produce, like "FedEx". Every test here passes
// includeBcc=false — that flag only controls ReplacementShippedMailService's own BCC behavior
// (covered by ReplacementShippedMailServiceTest), and none of these tickets have a customerId set,
// so the "n/a" backfill (also covered separately, in the tests below) never touches
// customerRepository either unless a test explicitly sets one up.
@ExtendWith(MockitoExtension.class)
class TicketCloseServiceTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketLineItemRepository ticketLineItemRepository;
    @Mock
    private TicketCommentService ticketCommentService;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ReplacementShippedMailService replacementShippedMailService;

    private TicketCloseService service;

    @BeforeEach
    void setUp() {
        service = new TicketCloseService(ticketRepository, ticketLineItemRepository, ticketCommentService,
                customerRepository, replacementShippedMailService);
    }

    private Ticket ticketStub(TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setStatus(status);
        return ticket;
    }

    private void stubSaveAndLineItems() {
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(ticketLineItemRepository.findByTicketIdIn(List.of(1L))).thenReturn(List.of());
    }

    @Test
    void close_ticketNotFound_throwsTicketNotFoundException() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(1L, null, null, "csagent", null, false))
                .isInstanceOf(TicketNotFoundException.class);
        verifyNoInteractions(ticketCommentService, ticketLineItemRepository);
    }

    @Test
    void close_alreadyClosedTicket_throwsIllegalStateExceptionWithoutSaving() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Closed)));

        assertThatThrownBy(() -> service.close(1L, null, null, "csagent", null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Closed or Cancelled");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void close_cancelledTicket_throwsIllegalStateExceptionWithoutSaving() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Cancelled)));

        assertThatThrownBy(() -> service.close(1L, null, null, "csagent", null, false))
                .isInstanceOf(IllegalStateException.class);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void close_movesTicketToClosedAndPopulatesLineItems() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Processing)));
        stubSaveAndLineItems();

        Ticket result = service.close(1L, null, null, "csagent", "USPS", false);

        assertThat(result.getStatus()).isEqualTo(TicketStatus.Closed);
        assertThat(result.getLastUpdated()).isNotNull();
        assertThat(result.getLineItems()).isEmpty();
    }

    @Test
    void close_blankTrackingNumber_leavesExistingTrackingNumberUnchanged() {
        Ticket ticket = ticketStub(TicketStatus.Processing);
        ticket.setTrackingNumber("EXISTING123");
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubSaveAndLineItems();

        Ticket result = service.close(1L, "  ", null, "csagent", "USPS", false);

        assertThat(result.getTrackingNumber()).isEqualTo("EXISTING123");
    }

    @Test
    void close_nonBlankTrackingNumber_overwritesExistingOne() {
        Ticket ticket = ticketStub(TicketStatus.Processing);
        ticket.setTrackingNumber("OLD123");
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubSaveAndLineItems();

        Ticket result = service.close(1L, "NEW456", null, "csagent", "USPS", false);

        assertThat(result.getTrackingNumber()).isEqualTo("NEW456");
    }

    @Test
    void close_nullComment_addsNoComment() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Processing)));
        stubSaveAndLineItems();

        service.close(1L, null, null, "csagent", "USPS", false);

        verifyNoInteractions(ticketCommentService);
    }

    @Test
    void close_blankComment_addsNoComment() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Processing)));
        stubSaveAndLineItems();

        service.close(1L, null, "   ", "csagent", "USPS", false);

        verifyNoInteractions(ticketCommentService);
    }

    @Test
    void close_nonBlankComment_addsItAttributedToTheGivenAuthor() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Processing)));
        stubSaveAndLineItems();

        service.close(1L, null, "Shipped via USPS", "csagent", "USPS", false);

        verify(ticketCommentService).addComment(1L, TicketController.SOURCE_SYSTEM, "Shipped via USPS", "csagent");
    }

    @Test
    void close_nullCarrier_derivesUpsFromA1ZTrackingNumber() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Processing)));
        stubSaveAndLineItems();

        Ticket result = service.close(1L, "1Z999AA10123456784", null, "csagent", null, false);

        assertThat(result.getCarrier()).isEqualTo("UPS");
    }

    @Test
    void close_nullCarrier_derivesUspsFromANon1ZTrackingNumber() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Processing)));
        stubSaveAndLineItems();

        Ticket result = service.close(1L, "9400100000000000000001", null, "csagent", null, false);

        assertThat(result.getCarrier()).isEqualTo("USPS");
    }

    @Test
    void close_nullCarrierAndNoTrackingNumberAtAll_derivesUsps() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Processing)));
        stubSaveAndLineItems();

        Ticket result = service.close(1L, null, null, "csagent", null, false);

        assertThat(result.getCarrier()).isEqualTo("USPS");
    }

    // The whole reason the CSV import never relies on the derive rule: it can't
    // produce "FedEx" at all, and a "1Z"-looking tracking number must not silently downgrade a
    // real FedEx shipment to a guessed "UPS" when the caller already knows the real carrier.
    @Test
    void close_explicitCarrier_isUsedVerbatimEvenAgainstA1ZLookingTrackingNumber() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Processing)));
        stubSaveAndLineItems();

        Ticket result = service.close(1L, "1Z999AA10123456784", null, "csagent", "FedEx", false);

        assertThat(result.getCarrier()).isEqualTo("FedEx");
    }

    // --- .2: customer.name == "n/a" backfill -----------------------------------------

    @Test
    void close_placeholderCustomerName_isBackfilledFromTicketCustomerName() {
        Ticket ticket = ticketStub(TicketStatus.Processing);
        ticket.setCustomerId("cust-1");
        ticket.setCustomerName("Real Buyer Name");
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubSaveAndLineItems();
        com.exampleco.crm.domain.ticket.Customer customer = new com.exampleco.crm.domain.ticket.Customer();
        customer.setId("cust-1");
        customer.setName("n/a");
        when(customerRepository.findById("cust-1")).thenReturn(Optional.of(customer));

        service.close(1L, null, null, "csagent", "USPS", false);

        verify(customerRepository).save(customer);
        assertThat(customer.getName()).isEqualTo("Real Buyer Name");
    }

    @Test
    void close_nonPlaceholderCustomerName_isLeftUntouched() {
        Ticket ticket = ticketStub(TicketStatus.Processing);
        ticket.setCustomerId("cust-1");
        ticket.setCustomerName("Real Buyer Name");
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubSaveAndLineItems();
        com.exampleco.crm.domain.ticket.Customer customer = new com.exampleco.crm.domain.ticket.Customer();
        customer.setId("cust-1");
        customer.setName("Already A Real Name");
        when(customerRepository.findById("cust-1")).thenReturn(Optional.of(customer));

        service.close(1L, null, null, "csagent", "USPS", false);

        verify(customerRepository, never()).save(any());
    }

    // Overwriting the "n/a" placeholder with a blank value would be worse than leaving the
    // placeholder alone.
    @Test
    void close_placeholderCustomerNameButBlankTicketCustomerName_leavesPlaceholderAlone() {
        Ticket ticket = ticketStub(TicketStatus.Processing);
        ticket.setCustomerId("cust-1");
        ticket.setCustomerName("   ");
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubSaveAndLineItems();

        service.close(1L, null, null, "csagent", "USPS", false);

        verifyNoInteractions(customerRepository);
    }

    @Test
    void close_noCustomerIdOnTicket_neverTouchesCustomerRepository() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticketStub(TicketStatus.Processing)));
        stubSaveAndLineItems();

        service.close(1L, null, null, "csagent", "USPS", false);

        verifyNoInteractions(customerRepository);
    }

    // A failing customer save is bookkeeping, not part of closing the ticket — it must not
    // propagate and fail the close itself.
    @Test
    void close_customerSaveFailure_doesNotFailTheClose() {
        Ticket ticket = ticketStub(TicketStatus.Processing);
        ticket.setCustomerId("cust-1");
        ticket.setCustomerName("Real Buyer Name");
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        stubSaveAndLineItems();
        com.exampleco.crm.domain.ticket.Customer customer = new com.exampleco.crm.domain.ticket.Customer();
        customer.setId("cust-1");
        customer.setName("n/a");
        when(customerRepository.findById("cust-1")).thenReturn(Optional.of(customer));
        when(customerRepository.save(customer)).thenThrow(new RuntimeException("db down"));

        Ticket result = service.close(1L, null, null, "csagent", "USPS", false);

        assertThat(result.getStatus()).isEqualTo(TicketStatus.Closed);
    }

    // --- TicketCloseService always triggers the buyer-facing mail ------------------

    @Test
    void close_alwaysInvokesReplacementShippedMailServiceWithTheResolvedCarrierAndLineItems() {
        Ticket ticket = ticketStub(TicketStatus.Processing);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        com.exampleco.crm.domain.ticket.TicketLineItem lineItem = new com.exampleco.crm.domain.ticket.TicketLineItem();
        lineItem.setTicketId(1L);
        when(ticketLineItemRepository.findByTicketIdIn(List.of(1L))).thenReturn(List.of(lineItem));

        service.close(1L, "1Z999AA10123456784", null, "csagent", null, true);

        verify(replacementShippedMailService).sendReplacementShipped(ticket, List.of(lineItem), "UPS", true);
    }
}
