package com.exampleco.crm.domain.ticket;

import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.core.dto.ticket.TicketStatus;

import com.exampleco.core.order.SalesChannel;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

// legacy-app's own enum constant names (e.g. "Processing", "Replacement") for status/action are now
// handled directly by TicketStatus.valueOf()/TicketAction.valueOf() — those two are real Java
// enums (see their own class comments), so this class is left with only the sourceSystem/channel
// validation that has nothing to do with either.
public final class TicketEnums {

    private TicketEnums() {
    }

    // Every Amazon channel code (see doc/0.5.0/normalize_ticket_source_system.sql and
    // PickingListExportService.ORDER_SOURCE_LABELS, which key off the same "az-" codes) —
    // one per storefront host LegacyApiClient/LegacyLineItemResolutionClient can fan out to. Derived
    // from exampleco-core's own SalesChannel rather than hardcoded, so a new az-* channel added
    // there (e.g. a new storefront) is picked up here automatically without a CRM code change;
    // LegacyApiProperties.Hosts still needs its own host configured before that new channel's
    // ticket writes can actually reach legacy-app (see its own comment). A brand-new ticket created
    // in CRM ('s channel picker) must supply one of these; "crm"
    // (TicketController.SOURCE_SYSTEM) is this app's own placeholder for the handful of writes
    // that aren't a specific new ticket (existing-ticket comments) and is deliberately not a
    // member of this set.
    public static final Set<String> SOURCE_SYSTEM_CHANNELS = Arrays.stream(SalesChannel.values())
            .map(SalesChannel::getChannelCode)
            .filter(code -> code != null && code.startsWith("az-"))
            .collect(Collectors.toUnmodifiableSet());

    public static String requireValidSourceSystem(String sourceSystem) {
        if (sourceSystem == null || !SOURCE_SYSTEM_CHANNELS.contains(sourceSystem)) {
            throw new IllegalArgumentException("Unknown ticket channel (sourceSystem): " + sourceSystem);
        }
        return sourceSystem;
    }
}
