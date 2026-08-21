package com.exampleco.crm.repository.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.core.dto.ticket.TicketStatus;

// search()/searchAll() are implemented in TicketRepositoryImpl (Spring Data's
// "<RepositoryName>Impl" custom-implementation convention), via TicketRepositoryCustom —
// dynamic ORDER BY sorting can't be expressed as a bind parameter in a plain @Query.
public interface TicketRepository extends JpaRepository<Ticket, Long>, TicketRepositoryCustom {

    // Ticket.id is not a @GeneratedValue column (bulk CSV imports set it explicitly, and the
    // real table's auto-increment status is unconfirmed), so a new ticket's id is computed
    // in application code instead of relying on the DB to generate one. See
    // TicketController#createTicket.
    Optional<Ticket> findTopByOrderByIdDesc();

    // Backs GET /api/tickets/by-legacy — how legacy-app remaps an id it already knows
    // (from before the migration) into this table's own id. (source_system, legacy_id) is the
    // migration's own unique key (uk_source_legacy), so this is always at most one row.
    Optional<Ticket> findBySourceSystemAndLegacyId(String sourceSystem, Long legacyId);

    // Backs GET /api/tickets/by-order. orderNumber isn't unique — a customer can
    // have more than one ticket against the same order (e.g. a Replacement and a separate
    // Refund) — so this returns every match, not just one.
    List<Ticket> findByOrderNumber(String orderNumber);

    // Channel-scoped variant of the above, for a service caller:
    // orderNumber alone isn't guaranteed unique across the 4 merged channels, so without this a
    // service caller could pull another channel's ticket just by knowing/guessing its order
    // number — the same leak sourceSystem-scoping on /search and /{id} already closes.
    List<Ticket> findBySourceSystemAndOrderNumber(String sourceSystem, String orderNumber);

    // Backs SalesOrderCrmLinkService: a Sales Order lookup by tracking number needs the same
    // ticket join as findByOrderNumber above, and trackingNumber isn't guaranteed unique either.
    List<Ticket> findByTrackingNumber(String trackingNumber);

    // Backs NotifyFailedTicketsService's daily digest — every ProcessFailed
    // ticket across every channel. Deliberately not sourceSystem-scoped like searchForService:
    // unlike legacy-app's retained per-channel jobs, this replaces a SINGLE job (legacy-app's original
    // NotifyFailedTicketsJob) that already looked across all channels at once.
    List<Ticket> findByStatus(TicketStatus status);

    // Backs NotifyRefundTicketsService's daily digest (replacing legacy-app's own
    // NotifyRefundTicketsJob) — every open (not yet Closed/Cancelled) Refund-action ticket with a
    // known order number, across every channel. excludedStatuses is the closed-status complement
    // computed by the caller (mirrors legacy-app's own CrmTicketService#getOpenRefundTickets comment:
    // deriving "open" from the enum rather than hardcoding [New, Processing, ProcessFailed], so a
    // future TicketStatus addition doesn't silently vanish from this digest).
    List<Ticket> findByActionAndOrderNumberIsNotNullAndStatusNotIn(TicketAction action, List<TicketStatus> excludedStatuses);
}
