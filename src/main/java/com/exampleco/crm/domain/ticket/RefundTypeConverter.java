package com.exampleco.crm.domain.ticket;

import com.exampleco.core.dto.ticket.RefundType;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// See TicketStatusConverter for why this stores the enum's name() rather than its legacy id.
@Converter
public class RefundTypeConverter implements AttributeConverter<RefundType, String> {

    @Override
    public String convertToDatabaseColumn(RefundType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public RefundType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : RefundType.valueOf(dbData);
    }
}
