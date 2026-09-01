package com.exampleco.crm.dto.audit;

import java.time.LocalDateTime;

// One changed property, on one entity, in one revision -- mirrors the property-level audit log
// shape legacy-app's own audit trail used to show (actor/class/property/event/old/new/when), the
// sample UI this feature was modeled after. See TicketHistoryService for how this is derived
// from Envers' own whole-entity-snapshot-per-revision model.
public record TicketHistoryEntry(
        String actor,
        String className,
        Long persistedObjectId,
        String propertyName,
        String eventName,
        String oldValue,
        String newValue,
        LocalDateTime dateCreated
) {
}
