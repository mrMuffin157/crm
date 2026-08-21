package com.exampleco.crm.repository.ticket;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import com.exampleco.crm.domain.ticket.Ticket;

public interface TicketRepositoryCustom {

    // allowedChannels: the caller's own channel-authorization scope — see
    // ChannelAccessService. Never empty by the time this is called (TicketController short-
    // circuits an empty set before querying at all, since "IN ()" isn't valid SQL); an admin or
    // service caller passes the full channel universe, which is a no-op restriction in practice.
    Page<Ticket> search(String status, String action, Boolean starred, Long handlerId, String search,
                         String orderNumber, String sourceSystem, LocalDateTime createdAfter, LocalDateTime createdBefore,
                         String sortField, String sortDir, Collection<String> allowedChannels, Pageable pageable);

    List<Ticket> searchAll(String status, String action, Boolean starred, Long handlerId, String search,
                            String orderNumber, String sourceSystem, LocalDateTime createdAfter, LocalDateTime createdBefore,
                            String sortField, String sortDir, Collection<String> allowedChannels);

    // Backs GET /api/tickets/search — deliberately separate from search()/
    // searchAll() above rather than adding more optional params to those: this is
    // sourceSystem-scoped (required, not optional — every §2 endpoint must be, now that one
    // shared table replaced 4 separate per-channel databases), supports multi-value status/
    // action (an OR list, not a single equals), and trackingNumber/orderNumber use a 3-way
    // null/notnull/exact-value filter the UI's search never needed. No pagination and no
    // row cap: this feeds legacy-app's scheduled jobs, not a browsable UI page, and silently
    // dropping rows past a cap here could mean a real ticket never gets processed.
    List<Ticket> searchForService(String sourceSystem, List<String> statusCodes, List<String> actionCodes,
                                   String trackingNumberFilter, String orderNumberFilter,
                                   LocalDateTime lastUpdatedFrom, LocalDateTime lastUpdatedTo);

    // Backs the Dashboard's channel breakdown widgets (GET /api/tickets/stats/channel-breakdown).
    // Each row is [statusCode, sourceSystem, count] — a GROUP BY, not per-channel single-count
    // fetches like dashboardStats.js's existing 3-request pattern, since a real breakdown needs
    // status x channel combinations, not just 3 totals. statusCodes/allowedChannels are both
    // caller-controlled (the fixed New/Processing/ProcessFailed set and the caller's own
    // ChannelAccessService scope), never raw client input, so no whitelist map is needed here —
    // there's no dynamic column/direction the way sorting requires one.
    List<Object[]> countByStatusGroupedBySourceSystem(Collection<String> statusCodes, Collection<String> allowedChannels);

    // Same shape as above for TicketAction (Refund/ManualHandling) instead of TicketStatus, plus
    // a required single statusCode: a Refund/ManualHandling ticket that's already Closed,
    // Cancelled, or ProcessFailed isn't the active queue this widget exists to surface, so the
    // caller always passes Processing here rather than this counting every status.
    List<Object[]> countByActionGroupedBySourceSystem(Collection<String> actionCodes, String statusCode, Collection<String> allowedChannels);
}
