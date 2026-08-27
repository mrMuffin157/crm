package com.exampleco.crm.service.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import com.exampleco.crm.controller.ticket.TicketController;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.domain.ticket.TicketLineItem;
import com.exampleco.core.dto.ticket.TicketStatus;
import com.exampleco.crm.dto.ticket.LineItemSummary;
import com.exampleco.crm.exception.ticket.TicketNotFoundException;
import com.exampleco.crm.repository.ticket.CustomerRepository;
import com.exampleco.crm.repository.ticket.TicketLineItemRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;

// Extracted out of TicketController#closeTicket — the single place a ticket
// actually gets closed, now shared by the existing POST /api/tickets/{id}/close endpoint and
// the new admin CSV bulk-close import (TicketCloseImportService), so the two can't drift on what
// "closing a ticket" means. Mirrors doCloseTicket(): sets an optional tracking number, moves the
// ticket to Closed, and — if given — adds a closing comment via TicketCommentService (same
// id-generation lock as every other comment write). folded in the two other things
// legacy-app's own closeTicket/handleTicket pair used to do around this same moment — the
// customer.name == "n/a" backfill (see backfillPlaceholderCustomerName) and the buyer-facing
// replacement-shipped mail (ReplacementShippedMailService) — so "closing a ticket" stays one
// place, not three.
@Component
public class TicketCloseService {

    private static final Logger log = LoggerFactory.getLogger(TicketCloseService.class);

    // legacy-app's own customer.name placeholder — see backfillPlaceholderCustomerName.
    private static final String PLACEHOLDER_CUSTOMER_NAME = "n/a";

    private final TicketRepository ticketRepository;
    private final TicketLineItemRepository ticketLineItemRepository;
    private final TicketCommentService ticketCommentService;
    private final CustomerRepository customerRepository;
    private final ReplacementShippedMailService replacementShippedMailService;

    public TicketCloseService(TicketRepository ticketRepository, TicketLineItemRepository ticketLineItemRepository,
                               TicketCommentService ticketCommentService, CustomerRepository customerRepository,
                               ReplacementShippedMailService replacementShippedMailService) {
        this.ticketRepository = ticketRepository;
        this.ticketLineItemRepository = ticketLineItemRepository;
        this.ticketCommentService = ticketCommentService;
        this.customerRepository = customerRepository;
        this.replacementShippedMailService = replacementShippedMailService;
    }

    // trackingNumber/comment are both optional, matching CloseTicketRequest's existing
    // semantics: blank trackingNumber leaves the ticket's existing one alone, blank/absent
    // comment adds none. The returned Ticket has its line items populated (unlike a plain
    // ticketRepository.save() result) so the manual close endpoint's own response carries them
    // without a second query — the same fetch also feeds ReplacementShippedMailService below.
    //
    // carrier is nullable: null means "derive it from the tracking number",
    // the manual single-ticket close path's behavior — see deriveCarrier. The close-ticket CSV
    // import (TicketCloseImportService) passes its own Carrier - Name column value instead,
    // since that heuristic can never produce "FedEx" and the import's source data carries the
    // real carrier already. Never persisted — see Ticket#carrier's own comment.
    //
    // includeBcc is the manual-close-only CS notify BCC (see
    // ReplacementShippedMailService) — true from TicketController#closeTicket, false from the
    // CSV import, matching carrier's own per-path split and for the same reason: a 248-row
    // import must never blast CS with 248 BCC copies.
    public Ticket close(Long id, String trackingNumber, String comment, String authorUsername, String carrier,
                         boolean includeBcc) {
        Ticket ticket = ticketRepository.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
        if (isLocked(ticket)) {
            throw new IllegalStateException("Cannot close a Closed or Cancelled ticket");
        }
        if (trackingNumber != null && !trackingNumber.isBlank()) {
            ticket.setTrackingNumber(trackingNumber);
        }
        ticket.setStatus(TicketStatus.Closed);
        ticket.setLastUpdated(LocalDateTime.now());
        Ticket saved = ticketRepository.save(ticket);
        if (comment != null && !comment.isBlank()) {
            ticketCommentService.addComment(saved.getId(), TicketController.SOURCE_SYSTEM, comment, authorUsername);
        }
        List<TicketLineItem> lineItems = ticketLineItemRepository.findByTicketIdIn(List.of(saved.getId()));
        saved.setLineItems(lineItems.stream()
                .map(item -> new LineItemSummary(item.getId(), item.getSku(), item.getName(), item.getQuantity(),
                        item.getChannelSku(), item.getRefundAmount()))
                .toList());
        String resolvedCarrier = carrier != null ? carrier : deriveCarrier(saved.getTrackingNumber());
        saved.setCarrier(resolvedCarrier);
        backfillPlaceholderCustomerName(saved);
        replacementShippedMailService.sendReplacementShipped(saved, lineItems, resolvedCarrier, includeBcc);
        return saved;
    }

    // Ports legacy-app's own "if (ticketInstance.customer && ticketInstance.customer.name == 'n/a')"
    // backfill (both its UI close and CSV-import copies did this, and CRM stopped doing it
    // entirely at cutover — see this issue's own §5.2) — the customer-merge migration
    // (doc/0.2.0/migrate_tickets_to_crm_dev.sql) could only ever see a customer referenced by a
    // ticket, so its name can still be that migration's own placeholder until something resolves
    // the real one. A blank ticket.customerName is left alone rather than overwriting "n/a" with
    // "" — that would be worse than the placeholder it replaced. Failure here (or simply having
    // no linked customer at all) must never fail the close itself: this is bookkeeping, not part
    // of closing the ticket.
    private void backfillPlaceholderCustomerName(Ticket ticket) {
        if (ticket.getCustomerId() == null || ticket.getCustomerName() == null || ticket.getCustomerName().isBlank()) {
            return;
        }
        try {
            customerRepository.findById(ticket.getCustomerId()).ifPresent(customer -> {
                if (PLACEHOLDER_CUSTOMER_NAME.equals(customer.getName())) {
                    customer.setName(ticket.getCustomerName());
                    customerRepository.save(customer);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to backfill placeholder customer name for ticket {}: {}", ticket.getId(), e.toString());
        }
    }

    private boolean isLocked(Ticket ticket) {
        return ticket.getStatus() == TicketStatus.Closed || ticket.getStatus() == TicketStatus.Cancelled;
    }

    // UPS's own tracking-number format reliably starts with "1Z"; everything else this app
    // ships is USPS. Only used when the caller has no better answer of its own (see close's
    // carrier param) — it can't produce "FedEx" at all, which is exactly why the CSV import
    // path never relies on it.
    private static String deriveCarrier(String trackingNumber) {
        return trackingNumber != null && trackingNumber.startsWith("1Z") ? "UPS" : "USPS";
    }
}
