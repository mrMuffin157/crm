package com.exampleco.crm.controller.ticket;

import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.dto.ticket.TicketPageResponse;
import com.exampleco.crm.dto.ticket.TicketWriteRequest;
import com.exampleco.crm.exception.ticket.ChannelNotAuthorizedException;
import com.exampleco.crm.exception.ticket.TicketNotFoundException;
import com.exampleco.crm.repository.ticket.AddressRepository;
import com.exampleco.crm.repository.ticket.CustomerRepository;
import com.exampleco.crm.repository.ticket.S2UserRepository;
import com.exampleco.crm.repository.ticket.TicketCommentRepository;
import com.exampleco.crm.repository.ticket.TicketLineItemRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;
import com.exampleco.crm.service.auth.ChannelAccessService;
import com.exampleco.crm.service.auth.LegacyServiceAuthenticationFilter;
import com.exampleco.crm.service.ticket.LegacyLineItemResolutionClient;
import com.exampleco.crm.service.ticket.PickingListExportService;
import com.exampleco.crm.service.ticket.TicketCloseService;
import com.exampleco.crm.service.ticket.TicketCommentService;
import com.exampleco.crm.service.ticket.TicketFailService;
import com.exampleco.crm.service.ticket.TicketIdLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// the per-member channel-authorization checks layered on top of the M2M
// sourceSystem scoping (see TicketControllerSourceSystemTest, which stays scoped to that earlier
// audit item). Plain Mockito unit test around the controller instance, same shape as that file —
// no Spring context, no datasource.
@ExtendWith(MockitoExtension.class)
class TicketControllerChannelAuthorizationTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketLineItemRepository ticketLineItemRepository;
    @Mock
    private TicketCommentRepository ticketCommentRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private S2UserRepository s2UserRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private PickingListExportService pickingListExportService;
    @Mock
    private TicketIdLock ticketIdLock;
    @Mock
    private TicketCommentService ticketCommentService;
    @Mock
    private TicketCloseService ticketCloseService;
    @Mock
    private TicketFailService ticketFailService;
    @Mock
    private LegacyLineItemResolutionClient legacyLineItemResolutionClient;
    @Mock
    private ChannelAccessService channelAccessService;

    private TicketController controller;

    private static final Authentication SERVICE_AUTH = UsernamePasswordAuthenticationToken.authenticated(
            "legacy-app", null, List.of(new SimpleGrantedAuthority(LegacyServiceAuthenticationFilter.AUTHORITY)));

    private static final Authentication SESSION_AUTH = UsernamePasswordAuthenticationToken.authenticated(
            "csagent", null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

    @BeforeEach
    void setUp() {
        controller = new TicketController(ticketRepository, ticketLineItemRepository, ticketCommentRepository,
                addressRepository, s2UserRepository, customerRepository, pickingListExportService, ticketIdLock,
                ticketCommentService, ticketCloseService, ticketFailService, legacyLineItemResolutionClient,
                channelAccessService);
    }

    private Ticket ticketWithSourceSystem(Long id, String sourceSystem) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setSourceSystem(sourceSystem);
        return ticket;
    }

    private TicketWriteRequest createRequest(String sourceSystem) {
        return new TicketWriteRequest(null, null, null, "ORDER-1", "ManualHandling", null, null, null, null, null,
                null, null, null, null, sourceSystem, null, null);
    }

    // --- createTicket: a channel the caller isn't granted is rejected before any write ---------

    @Test
    void createTicket_channelNotAuthorized_throwsChannelNotAuthorizedExceptionAndNeverTouchesRepository() {
        when(channelAccessService.isChannelAuthorized(SESSION_AUTH, "az-ca")).thenReturn(false);

        Mono<Ticket> result = controller.createTicket(createRequest("az-ca"), Mono.just(SESSION_AUTH));

        assertThatThrownBy(result::block).isInstanceOf(ChannelNotAuthorizedException.class);
        verifyNoInteractions(ticketRepository, ticketIdLock);
    }

    @Test
    void createTicket_channelAuthorized_succeeds() {
        when(channelAccessService.isChannelAuthorized(SESSION_AUTH, "az-us")).thenReturn(true);
        when(s2UserRepository.findByUsername("csagent")).thenReturn(Optional.empty());
        when(ticketRepository.findTopByOrderByIdDesc()).thenReturn(Optional.empty());
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(ticketIdLock.withLock(any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(0);
            return action.get();
        });

        Mono<Ticket> result = controller.createTicket(createRequest("az-us"), Mono.just(SESSION_AUTH));

        assertThat(result.block()).isNotNull();
    }

    @Test
    void channelNotAuthorizedException_mapsToHttpForbidden() {
        var response = controller.handleChannelNotAuthorized(new ChannelNotAuthorizedException("az-ca"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    // --- getTicket: a member not authorized for the ticket's own channel gets 404, same as a --
    // --- mismatched service-caller sourceSystem already does (never a distinct error) ---------

    @Test
    void getTicket_memberChannelNotAuthorized_throwsNotFound() {
        Ticket ticket = ticketWithSourceSystem(42L, "az-ca");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));
        when(channelAccessService.isChannelAuthorized(SESSION_AUTH, "az-ca")).thenReturn(false);

        Mono<Object> result = controller.getTicket(42L, null, null, Mono.just(SESSION_AUTH));

        assertThatThrownBy(result::block).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void getTicket_memberChannelAuthorized_succeeds() {
        Ticket ticket = ticketWithSourceSystem(42L, "az-us");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));
        when(channelAccessService.isChannelAuthorized(SESSION_AUTH, "az-us")).thenReturn(true);
        lenient().when(s2UserRepository.findAll()).thenReturn(List.of());

        Mono<Object> result = controller.getTicket(42L, null, null, Mono.just(SESSION_AUTH));

        assertThat(result.block()).isSameAs(ticket);
    }

    // A service caller resolving to the full channel universe (ChannelAccessService) must never
    // be blocked by this new check on top of its own existing sourceSystem-match scoping.
    @Test
    void getTicket_serviceCallerAuthorizedForFullUniverse_isNotBlockedByChannelCheck() {
        Ticket ticket = ticketWithSourceSystem(42L, "az-us");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));
        when(channelAccessService.isChannelAuthorized(SERVICE_AUTH, "az-us")).thenReturn(true);
        lenient().when(s2UserRepository.findAll()).thenReturn(List.of());

        Mono<Object> result = controller.getTicket(42L, null, "az-us", Mono.just(SERVICE_AUTH));

        assertThat(result.block()).isInstanceOf(com.exampleco.core.dto.ticket.CrmTicketTO.class);
    }

    // --- getTicketsByOrder: results are filtered down to only the caller's authorized channels -

    @Test
    void getTicketsByOrder_filtersOutTicketsFromUnauthorizedChannels() {
        Ticket authorizedTicket = ticketWithSourceSystem(1L, "az-us");
        Ticket unauthorizedTicket = ticketWithSourceSystem(2L, "az-ca");
        when(ticketRepository.findByOrderNumber("ORDER-1")).thenReturn(List.of(authorizedTicket, unauthorizedTicket));
        when(channelAccessService.isChannelAuthorized(SESSION_AUTH, "az-us")).thenReturn(true);
        when(channelAccessService.isChannelAuthorized(SESSION_AUTH, "az-ca")).thenReturn(false);
        lenient().when(s2UserRepository.findAll()).thenReturn(List.of());

        Mono<Object> result = controller.getTicketsByOrder("ORDER-1", null, null, Mono.just(SESSION_AUTH));

        @SuppressWarnings("unchecked")
        List<Ticket> tickets = (List<Ticket>) result.block();
        assertThat(tickets).containsExactly(authorizedTicket);
    }

    // --- listTickets: a member with no channel grants at all gets an empty page, and the query -
    // --- never even runs (an empty "IN ()" clause would be invalid SQL) -------------------------

    @Test
    void listTickets_memberWithNoChannelGrants_returnsEmptyPageWithoutQueryingRepository() {
        when(channelAccessService.resolveAllowedChannels(SESSION_AUTH)).thenReturn(Set.of());

        Mono<TicketPageResponse> result = controller.listTickets(null, null, null, null, null, null, null,
                null, null, null, null, 0, 100, false, Mono.just(SESSION_AUTH));

        TicketPageResponse response = result.block();
        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        verifyNoInteractions(ticketRepository, s2UserRepository);
    }

    @Test
    void listTickets_memberWithChannelGrants_queriesRepositoryScopedToThoseChannels() {
        when(channelAccessService.resolveAllowedChannels(SESSION_AUTH)).thenReturn(Set.of("az-us"));
        var page = new org.springframework.data.domain.PageImpl<Ticket>(List.of());
        // 11 plain filter params (status..sortDir), then allowedChannels, then pageable.
        when(ticketRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyCollection(), any())).thenReturn(page);

        controller.listTickets(null, null, null, null, null, null, null,
                null, null, null, null, 0, 100, false, Mono.just(SESSION_AUTH)).block();

        org.mockito.Mockito.verify(ticketRepository).search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), org.mockito.ArgumentMatchers.eq(Set.of("az-us")), any());
    }
}
