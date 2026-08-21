package com.exampleco.crm.dto.ticket;

import java.util.List;

// Request body for POST/PUT /api/tickets — replaces binding directly onto the Ticket entity
// now that create/edit also need to carry a Customer Address block and a set of line items
// alongside the ticket's own fields (see the real form's checkAction()-driven blocks).
//
// address == null means "don't touch this ticket's existing address"; a present AddressInput
// means "create or update it to exactly this." Same null-means-leave-alone contract for
// lineItems: null means "don't touch existing line items," a (possibly empty) list means
// "replace the full set with exactly this."
//
// status and handlerUsername follow that same leave-alone contract, unlike every other
// scalar field here, which is written straight through: both back NOT NULL columns and neither
// form renders an input for them any more, so a null means "keep what the ticket already has"
// rather than a clear that the database would reject anyway. Status changes have their own
// endpoints (changeStatus / close), which is where a real status edit belongs.
//
// handlerUsername (audit follow-up, revised §2 "no raw user ids"): was Long handlerId
// until this change. crm.s2user.id has no meaning to legacy-app (a fresh value minted by the
// migration's username-merge), and typing a raw internal database id to assign a ticket was
// already poor UX for CRM's own users regardless — username is resolved to the real id
// server-side (TicketController#resolveHandlerUsername), same shape as createTicket's existing
// findByUsername(authentication.getName()) fallback for an unassigned new ticket.
//
// sourceSystem ('s channel picker): required on POST /api/tickets only — the caller's
// chosen one of TicketEnums.SOURCE_SYSTEM_CHANNELS (every "az-*" SalesChannel), replacing the
// TicketController.SOURCE_SYSTEM ("crm") placeholder every fresh ticket used to get. Ignored on
// PUT /api/tickets/{id}: channel is set once at creation and is never editable afterward, so
// TicketController#applyFields (shared by both create and edit) never reads this field — only
// createTicket() does.
public record TicketWriteRequest(
        String customerName,
        String email,
        String customerId,
        String orderNumber,
        String action,
        String status,
        String reason,
        String refundType,
        String trackingNumber,
        String replacementOrderNumber,
        String handlerUsername,
        Boolean starred,
        Boolean outOfWarrant,
        String description,
        String sourceSystem,
        AddressInput address,
        List<LineItemInput> lineItems
) {
}
