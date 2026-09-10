package com.exampleco.crm.controller.ticket;

import com.exampleco.crm.domain.ticket.Customer;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// A non-blank customerId only ever comes from a ticket raised within a Sales Order lookup
// (SalesOrdersPage.jsx seeding it from the order's own CustomerTO), so it isn't necessarily in
// crm_dev.customer yet — TicketController#resolveCustomerId must insert it rather than trust it
// blindly, since Ticket.customerId has an FK to customer.id
// (doc/0.2.0/migrate_tickets_to_crm_dev.sql). Plain Mockito unit test, no Spring context or
// database, same shape as TicketControllerActionStatusDerivationTest.
class TicketControllerCustomerResolutionTest {

    private record Env(TicketController controller, CustomerRepository customerRepository) {
    }

    private Env buildController() {
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

        when(ticketRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        return new Env(controller, customerRepository);
    }

    private Ticket update(TicketController controller, String customerId, String customerName, String email) {
        TicketWriteRequest request = new TicketWriteRequest(
                customerName, email, customerId, "ORDER-1",
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null);

        return controller.updateTicket(1L, request,
                Mono.just(new UsernamePasswordAuthenticationToken("agent", null, List.of()))).block();
    }

    @Test
    void unknownCustomerIdIsInsertedWhenNotYetInCrm() {
        Env env = buildController();
        when(env.customerRepository().existsById("cust-1")).thenReturn(false);
        when(env.customerRepository().findFirstByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.empty());

        Ticket saved = update(env.controller(), "cust-1", "Jane Doe", "jane@example.com");

        assertThat(saved.getCustomerId()).isEqualTo("cust-1");
        verify(env.customerRepository()).save(any(Customer.class));
    }

    @Test
    void existingCustomerIdIsUsedWithoutInserting() {
        Env env = buildController();
        when(env.customerRepository().existsById("cust-1")).thenReturn(true);

        Ticket saved = update(env.controller(), "cust-1", "Jane Doe", "jane@example.com");

        assertThat(saved.getCustomerId()).isEqualTo("cust-1");
        verify(env.customerRepository(), never()).save(any());
    }

    @Test
    void unknownCustomerIdWithEmailAlreadyLinkedToAnotherRowReusesThatRow() {
        Env env = buildController();
        Customer other = new Customer();
        other.setId("cust-2");
        other.setEmail("jane@example.com");

        when(env.customerRepository().existsById("cust-1")).thenReturn(false);
        when(env.customerRepository().findFirstByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(other));

        Ticket saved = update(env.controller(), "cust-1", "Jane Doe", "jane@example.com");

        assertThat(saved.getCustomerId()).isEqualTo("cust-2");
        verify(env.customerRepository(), never()).save(any());
    }

    @Test
    void unknownCustomerIdWithNoEmailIsNotInserted() {
        Env env = buildController();
        when(env.customerRepository().existsById("cust-1")).thenReturn(false);

        Ticket saved = update(env.controller(), "cust-1", "Jane Doe", null);

        assertThat(saved.getCustomerId()).isNull();
        verify(env.customerRepository(), never()).save(any());
    }

    @Test
    void blankCustomerIdFallsBackToEmailMatch() {
        Env env = buildController();
        Customer existingCustomer = new Customer();
        existingCustomer.setId("cust-3");
        existingCustomer.setEmail("jane@example.com");

        when(env.customerRepository().findFirstByEmailIgnoreCase("jane@example.com"))
                .thenReturn(Optional.of(existingCustomer));

        Ticket saved = update(env.controller(), null, "Jane Doe", "jane@example.com");

        assertThat(saved.getCustomerId()).isEqualTo("cust-3");
        verify(env.customerRepository(), never()).save(any());
    }
}
