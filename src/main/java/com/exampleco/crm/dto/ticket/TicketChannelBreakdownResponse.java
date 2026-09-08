package com.exampleco.crm.dto.ticket;

import java.util.Map;

// Backs GET /api/tickets/stats/channel-breakdown (Dashboard's channel breakdown widgets). Both
// maps are keyed by the enum's own name() string (matching the wire format every other
// status/action value uses, i.e. Ticket's default enum serialization) with the inner map keyed
// by sourceSystem (an "az-*" channel code, see TicketEnums.SOURCE_SYSTEM_CHANNELS) -> count.
// A caller with no access to a given channel never sees it in either map, same channel scoping
// as every other tickets endpoint (see ChannelAccessService).
public record TicketChannelBreakdownResponse(
        Map<String, Map<String, Long>> byStatus,
        Map<String, Map<String, Long>> byAction) {
}
