package com.exampleco.crm.dto.ticket;

import java.util.List;
import com.exampleco.crm.domain.ticket.Ticket;

public record TicketPageResponse(List<Ticket> content, long totalElements, int totalPages, int page, int size) {
}
