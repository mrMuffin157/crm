package com.exampleco.crm.controller.ticket;

import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.core.dto.ticket.TicketStatus;
import com.exampleco.crm.dto.ticket.PatchTicketRequest;
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
import com.exampleco.crm.service.ticket.TicketCloseService;
import com.exampleco.crm.service.ticket.TicketCommentService;
import com.exampleco.crm.service.ticket.TicketFailService;
import com.exampleco.crm.service.ticket.TicketIdLock;
import com.exampleco.crm.service.ticket.PickingListExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

// Regression coverage for the M2M ticket API's two sourceSystem safety nets called out in the
// audit ("a test asserting it" for each):
//
//   1. GET /api/tickets/search must reject a request with no sourceSystem with HTTP 400, never
//      silently returning every channel's tickets (TicketController#searchTicketsForService).
//   2. GET/PATCH /api/tickets/{id} must 404 (not leak data) when a SERVICE-authenticated caller's
//      sourceSystem doesn't match the ticket's own — but only for that service principal, never
//      for an ordinary CRM session (TicketController#enforceSourceSystemForService).
//
// This is a plain Mockito unit test around the controller instance itself, not a Spring slice or
// full-context test: TicketController takes its dependencies as constructor-injected interfaces,
// so the sourceSystem branching (and the exception-handler status mapping it relies on) can be
// exercised directly, with no datasource, no security filter chain, and no web server needed.
@ExtendWith(MockitoExtension.class)
class TicketControllerSourceSystemTest {

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

    // The same authority LegacyServiceAuthenticationFilter grants a validated legacy-app Bearer-token
    // caller — see that class's AUTHORITY constant and its filter() method.
    private static final Authentication SERVICE_AUTH = UsernamePasswordAuthenticationToken.authenticated(
            "legacy-app", null, List.of(new SimpleGrantedAuthority(LegacyServiceAuthenticationFilter.AUTHORITY)));

    // An ordinary CRM session: a logged-in human CS agent, never carrying ROLE_SERVICE.
    private static final Authentication SESSION_AUTH = UsernamePasswordAuthenticationToken.authenticated(
            "csagent", null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

    @BeforeEach
    void setUp() {
        controller = new TicketController(ticketRepository, ticketLineItemRepository, ticketCommentRepository,
                addressRepository, s2UserRepository, customerRepository, pickingListExportService, ticketIdLock,
                ticketCommentService, ticketCloseService, ticketFailService, legacyLineItemResolutionClient,
                channelAccessService);
        // This test file is scoped to the sourceSystem safety nets, not 
        // per-member channel authorization (see TicketControllerChannelAuthorizationTest) — every
        // caller here is authorized for every channel unless a test overrides this.
        lenient().when(channelAccessService.isChannelAuthorized(any(), any())).thenReturn(true);
    }

    private Ticket ticketWithSourceSystem(Long id, String sourceSystem) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setSourceSystem(sourceSystem);
        ticket.setStatus(TicketStatus.New);
        return ticket;
    }

    // --- 1. GET /api/tickets/search: sourceSystem is unconditionally required -----------------

    @Test
    void searchTicketsForService_withoutSourceSystem_throwsIllegalArgumentException() {
        Mono<Object> result = controller.searchTicketsForService(
                null, null, null, null, null, null, null, null, Mono.just(SERVICE_AUTH));

        assertThatThrownBy(result::block).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchTicketsForService_withBlankSourceSystem_throwsIllegalArgumentException() {
        Mono<Object> result = controller.searchTicketsForService(
                "   ", null, null, null, null, null, null, null, Mono.just(SERVICE_AUTH));

        assertThatThrownBy(result::block).isInstanceOf(IllegalArgumentException.class);
    }

    // The controller's own @ExceptionHandler is what turns that IllegalArgumentException into
    // the HTTP 400 the audit item actually cares about — asserting the mapping directly ties the
    // two together instead of trusting Spring's default 500-for-unmapped-exception behavior.
    @Test
    void illegalArgumentException_mapsToHttpBadRequest() {
        var response = controller.handleBadRequest(new IllegalArgumentException("sourceSystem is required"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // A missing sourceSystem must never fall through to an unfiltered result set — guard against
    // a future regression that, say, swallows the check but still calls the repository.
    @Test
    void searchTicketsForService_withoutSourceSystem_neverQueriesRepository() {
        Mono<Object> result = controller.searchTicketsForService(
                null, null, null, null, null, null, null, null, Mono.just(SERVICE_AUTH));

        assertThatThrownBy(result::block).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(ticketRepository);
    }

    // --- 2. GET/PATCH /api/tickets/{id}: source-system scoping, service callers only ----------

    @Test
    void getTicket_serviceCallerWithMismatchedSourceSystem_throwsNotFound() {
        Ticket ticket = ticketWithSourceSystem(42L, "az-us");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));

        Mono<Object> result = controller.getTicket(42L, null, "az-ca", Mono.just(SERVICE_AUTH));

        assertThatThrownBy(result::block).isInstanceOf(TicketNotFoundException.class);
    }

    // A service caller gets CrmTicketTO, not the raw entity -- see CrmTicketMapper.
    @Test
    void getTicket_serviceCallerWithMatchingSourceSystem_succeeds() {
        Ticket ticket = ticketWithSourceSystem(42L, "az-us");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));
        lenient().when(s2UserRepository.findAll()).thenReturn(List.of());

        Mono<Object> result = controller.getTicket(42L, null, "az-us", Mono.just(SERVICE_AUTH));

        assertThat(result.block()).isInstanceOf(com.exampleco.core.dto.ticket.CrmTicketTO.class);
    }

    @Test
    void getTicket_serviceCallerWithoutSourceSystem_throwsIllegalArgumentException() {
        Ticket ticket = ticketWithSourceSystem(42L, "az-us");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));

        Mono<Object> result = controller.getTicket(42L, null, null, Mono.just(SERVICE_AUTH));

        assertThatThrownBy(result::block).isInstanceOf(IllegalArgumentException.class);
    }

    // The scoping is specific to the service principal: an ordinary CRM session passes no
    // sourceSystem at all today (EditTicketModal/fetchTicketById) and must keep working
    // unscoped, exactly as enforceSourceSystemForService's own comment describes. A session
    // caller must also keep getting the raw Ticket entity, never CrmTicketTO -- see
    // TicketController#toResponse.
    @Test
    void getTicket_sessionCallerWithoutSourceSystem_isNotRestricted() {
        Ticket ticket = ticketWithSourceSystem(42L, "az-us");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));
        lenient().when(s2UserRepository.findAll()).thenReturn(List.of());

        Mono<Object> result = controller.getTicket(42L, null, null, Mono.just(SESSION_AUTH));

        assertThat(result.block()).isSameAs(ticket);
    }

    @Test
    void getTicket_sessionCallerWithMismatchedSourceSystem_isNotRestricted() {
        // A CRM session that happens to pass a sourceSystem query param anyway must not be
        // scoped by it -- only the service principal is (see enforceSourceSystemForService).
        Ticket ticket = ticketWithSourceSystem(42L, "az-us");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));
        lenient().when(s2UserRepository.findAll()).thenReturn(List.of());

        Mono<Object> result = controller.getTicket(42L, null, "az-ca", Mono.just(SESSION_AUTH));

        assertThat(result.block()).isSameAs(ticket);
    }

    @Test
    void patchTicketForService_serviceCallerWithMismatchedSourceSystem_throwsNotFound() {
        Ticket ticket = ticketWithSourceSystem(42L, "az-us");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));
        PatchTicketRequest request = new PatchTicketRequest(null, null, null);

        Mono<Object> result = controller.patchTicketForService(42L, null, "az-ca", request, Mono.just(SERVICE_AUTH));

        assertThatThrownBy(result::block).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void patchTicketForService_serviceCallerWithMatchingSourceSystem_succeeds() {
        Ticket ticket = ticketWithSourceSystem(42L, "az-us");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PatchTicketRequest request = new PatchTicketRequest(null, null, null);

        Mono<Object> result = controller.patchTicketForService(42L, null, "az-us", request, Mono.just(SERVICE_AUTH));

        assertThat(result.block()).isInstanceOf(com.exampleco.core.dto.ticket.CrmTicketTO.class);
    }

    // The not-found mapping (both for a mismatched channel and for a genuinely absent id) is
    // itself HTTP 404 via the controller's own handler -- asserted directly, same reasoning as
    // illegalArgumentException_mapsToHttpBadRequest above.
    @Test
    void ticketNotFoundException_mapsToHttpNotFound() {
        var response = controller.handleNotFound(new TicketNotFoundException(42L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
