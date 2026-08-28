package com.exampleco.crm.domain.ticket;

import com.exampleco.core.dto.ticket.TicketAction;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// See TicketStatusConverter for why this stores the enum's name() rather than its legacy id.
@Converter
public class TicketActionConverter implements AttributeConverter<TicketAction, String> {

    @Override
    public String convertToDatabaseColumn(TicketAction attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public TicketAction convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TicketAction.valueOf(dbData);
    }
}
