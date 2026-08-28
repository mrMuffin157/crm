package com.exampleco.crm.domain.ticket;

import com.exampleco.core.dto.ticket.TicketReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// See TicketStatusConverter for why this stores the enum's name() rather than its legacy id.
@Converter
public class TicketReasonConverter implements AttributeConverter<TicketReason, String> {

    private static final Logger log = LoggerFactory.getLogger(TicketReasonConverter.class);

    @Override
    public String convertToDatabaseColumn(TicketReason attribute) {
        return attribute == null ? null : attribute.name();
    }

    // Unlike the status/action/refund_type converters, this one tolerates a value it cannot map.
    //
    // reason is the only one of the four whose column holds a value outside its enum: exactly one
    // migrated row (crm_dev.ticket id 230, an store1_dev ticket from 2018) carries reason = 0,
    // which TicketReason has no constant for -- its ids start at 1. doc/0.7.0's data-fix script
    // left that row's column holding the literal string "0" (there is no name to map it to), so
    // this converter still has to recognize it as the same unmappable value post-migration --
    // TicketReason.valueOf("0") throws IllegalArgumentException just like fromId(0) used to.
    //
    // A converter that throws here fails the whole result set, not the one row: materializing any
    // query that touches that ticket raises IllegalArgumentException, which is why
    // GET /api/tickets/search?sourceSystem=store1_dev answered
    // 400 "Unknown ticket reason id: 0" and returned none of its ~6000 tickets. That blast radius
    // is the actual defect. The row is not junk either — it is Closed/Replacement with an order
    // number and no tracking number, i.e. exactly what legacy-app's
    // getClosedReplacementTicketsMissingTracking work-list selects, so dropping or skipping it
    // would silently lose real work.
    //
    // So the unmappable value degrades this one field to null and says so in the log, rather than
    // taking the query down or resolving to some plausible-but-invented reason. Callers already
    // treat reason as optional (it is informational; none of legacy-app's three work-lists filter on
    // it). Saving such a ticket back still fails loudly on the NOT NULL column, which is correct —
    // the fix for the data is a data fix, not this.
    //
    // status/action/refund_type stay strict on purpose: their columns are clean today, and an
    // unmappable value there would be a genuine semantic unknown rather than a missing annotation.
    @Override
    public TicketReason convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return TicketReason.valueOf(dbData);
        } catch (IllegalArgumentException e) {
            log.warn("ticket.reason holds {}, which maps to no TicketReason; reading it as null", dbData);
            return null;
        }
    }
}
