package com.exampleco.crm.dto.ticket;

// Request body for POST /api/tickets/{id}/fail — a dedicated record rather than
// reusing AddCommentRequest, even though the shape matches today, so the two request bodies
// stay free to evolve independently (they back conceptually different endpoints: a plain
// comment add vs. an atomic comment-plus-status-change).
public record FailTicketRequest(String comment) {
}
