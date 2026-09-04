package com.exampleco.crm.service.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.core.dto.ticket.TicketStatus;
import com.exampleco.core.order.SalesChannel;
import com.exampleco.core.shipment.CountryCode;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.domain.ticket.TicketLineItem;
import com.exampleco.crm.repository.ticket.TicketLineItemRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;

// Replaces legacy-app's own NotifyRefundTicketsJob/SalesOrderService#notifyRefundTickets +
// CrmTicketService#getOpenRefundTickets — a read-only job over ticket data that's
// fully in crm_dev now, so unlike legacy-app's per-storefront instances (one BasicProfile country
// per app), a single CRM run sees every channel at once and must split the recipients itself:
// a US channel's open Refund tickets go to NOTIFY_REFUND_TICKET_MEMBER, everything else
// (including an unmapped/unknown channel, a safe default) goes to gsmAlarmGroup — see
// SalesOrderService#notifyRefundTickets's own US-vs-else branch in legacy-app.
@Component
public class NotifyRefundTicketsService {

    private static final Logger log = LoggerFactory.getLogger(NotifyRefundTicketsService.class);

    // "已結案" complement, mirroring legacy-app's own CrmTicketService#getOpenRefundTickets comment:
    // derived from the enum rather than hardcoded, so a future TicketStatus addition doesn't
    // silently vanish from this digest's definition of "open".
    private static final List<TicketStatus> CLOSED_STATUSES = List.of(TicketStatus.Closed, TicketStatus.Cancelled);

    private final TicketRepository ticketRepository;
    private final TicketLineItemRepository ticketLineItemRepository;
    private final RefundTicketNotificationService notificationService;
    private final String memberChatIdsCsv;
    private final String gsmAlarmGroupChatId;

    public NotifyRefundTicketsService(TicketRepository ticketRepository,
                                       TicketLineItemRepository ticketLineItemRepository,
                                       RefundTicketNotificationService notificationService,
                                       @Value("${app.notify-refund-tickets.member-chat-ids:}") String memberChatIdsCsv,
                                       @Value("${app.notify-refund-tickets.gsm-alarm-group-chat-id:}") String gsmAlarmGroupChatId) {
        this.ticketRepository = ticketRepository;
        this.ticketLineItemRepository = ticketLineItemRepository;
        this.notificationService = notificationService;
        this.memberChatIdsCsv = memberChatIdsCsv;
        this.gsmAlarmGroupChatId = gsmAlarmGroupChatId;
    }

    // Called by NotifyRefundTicketsJob's daily Quartz trigger. Returns the number of open
    // Refund tickets found (regardless of whether either digest actually sent — see
    // RefundTicketNotificationService#sendDigest), for the caller to log.
    public int run() {
        List<Ticket> tickets = ticketRepository.findByActionAndOrderNumberIsNotNullAndStatusNotIn(
                TicketAction.Refund, CLOSED_STATUSES);
        if (!tickets.isEmpty()) {
            Map<Long, List<String>> skusByTicketId = loadSkus(tickets);
            List<Ticket> usTickets = new ArrayList<>();
            List<Ticket> nonUsTickets = new ArrayList<>();
            for (Ticket ticket : tickets) {
                (isUsChannel(ticket) ? usTickets : nonUsTickets).add(ticket);
            }
            notificationService.sendDigest(usTickets, skusByTicketId, parseChatIds(memberChatIdsCsv));
            notificationService.sendDigest(nonUsTickets, skusByTicketId, parseChatIds(gsmAlarmGroupChatId));
        }
        return tickets.size();
    }

    // Mirrors TicketController#enrich's lineItems batch (same shape, scoped down to just the
    // SKUs this digest needs) — no chunking, same reasoning as
    // NotifyFailedTicketsService#attachLastException's own unchunked comment lookup for a daily
    // digest's expected volume.
    private Map<Long, List<String>> loadSkus(List<Ticket> tickets) {
        List<Long> ticketIds = tickets.stream().map(Ticket::getId).distinct().toList();
        Map<Long, List<String>> skusByTicketId = new HashMap<>();
        for (TicketLineItem item : ticketLineItemRepository.findByTicketIdIn(ticketIds)) {
            skusByTicketId.computeIfAbsent(item.getTicketId(), k -> new ArrayList<>()).add(item.getSku());
        }
        return skusByTicketId;
    }

    // Non-US is the safe default for an unmapped/unknown sourceSystem (e.g. the "crm" write
    // placeholder, or a channel not yet in exampleco-core's SalesChannel) — legacy-app's own
    // BasicProfile.basicProfile.channel.countryCode never has this gap since one app instance is
    // always exactly one channel, but a merged CRM job has to account for it explicitly.
    private boolean isUsChannel(Ticket ticket) {
        SalesChannel channel = SalesChannel.findByChannelCode(ticket.getSourceSystem());
        return channel != null && channel.getCountryCode() == CountryCode.US;
    }

    private List<Long> parseChatIds(String csv) {
        List<Long> chatIds = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return chatIds;
        }
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                chatIds.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                log.warn("Ignoring non-numeric refund-ticket notify chat id: {}", trimmed);
            }
        }
        return chatIds;
    }
}
