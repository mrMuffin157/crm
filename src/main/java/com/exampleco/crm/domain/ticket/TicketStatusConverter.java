package com.exampleco.crm.domain.ticket;

import com.exampleco.core.dto.ticket.TicketStatus;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// TicketStatus's ids are explicit (legacy-app's GORM `identity` enum type), so this can't be
// @Enumerated -- it has to convert by hand. exampleco-core's own javadoc calls the id "the value
// both databases store", but that describes legacy-app's own storage and the M2M wire format
// (CrmTicketTO, unaffected by this change) -- not a constraint on how this app's own column has
// to hold the value internally. This column stores the enum's name()
// instead (doc/0.7.0/convert_ticket_status_action_reason_refund_type_to_string.sql, run against
// every environment as of that script landing), same as CrmTicketTO/SalesOrderArchiveMigration's
// decodeOrWarn already represent these values.
@Converter
public class TicketStatusConverter implements AttributeConverter<TicketStatus, String> {

    @Override
    public String convertToDatabaseColumn(TicketStatus attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public TicketStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TicketStatus.valueOf(dbData);
    }
}
