package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.order.ShipToAddressTO;
import com.exampleco.core.dto.ticket.CrmTicketCustomerTO;
import com.exampleco.core.dto.ticket.CrmTicketHandlerTO;
import com.exampleco.core.dto.ticket.CrmTicketLineItemTO;
import com.exampleco.core.dto.ticket.CrmTicketTO;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.dto.ticket.AddressDetail;
import com.exampleco.crm.dto.ticket.CustomerDetail;
import com.exampleco.crm.dto.ticket.HandlerDetail;
import com.exampleco.crm.dto.ticket.LineItemSummary;

import java.util.List;

// Maps this app's own Ticket entity (plus whichever @Transient enrichment fields
// TicketController#enrich already populated) onto exampleco-core's CrmTicketTO -- the M2M ticket
// shape legacy-app consumes ("CRM ticket API 的 TO"). Only ever applied to a ROLE_SERVICE
// caller's response (see TicketController#toResponse/#toResponseList); a CRM session still gets
// the raw Ticket entity exactly as before, since the frontend already depends on that shape and
// there's no API versioning to cushion a change (see CLAUDE.md "No API versioning").
//
// Enum-valued fields carry the enum *name* (not the numeric code), matching CrmTicketTO's own
// javadoc -- legacy-app maps the name back to its own TicketAction/TicketStatus/TicketReason/
// RefundType. address/handler/customer/lineItems are left null when the corresponding
// TicketController#enrich `include` wasn't requested, same as the Ticket entity's own
// @Transient fields.
public final class CrmTicketMapper {

    private CrmTicketMapper() {
    }

    public static CrmTicketTO toTO(Ticket ticket) {
        CrmTicketTO to = new CrmTicketTO();
        to.setId(ticket.getId());
        to.setSourceSystem(ticket.getSourceSystem());
        // Straight assignment now that both sides share these enums: the TO used to hold
        // Strings, so this had to hand-serialize each one with .name() and trust legacy-app to
        // parse the same spelling back. Any mismatch is now a compile error over there.
        to.setAction(ticket.getAction());
        to.setStatus(ticket.getStatus());
        to.setReason(ticket.getReason());
        to.setRefundType(ticket.getRefundType());
        to.setOrderNumber(ticket.getOrderNumber());
        to.setReplacementOrderNumber(ticket.getReplacementOrderNumber());
        to.setTrackingNumber(ticket.getTrackingNumber());
        to.setEmail(ticket.getEmail());
        to.setCustomerName(ticket.getCustomerName());
        to.setDescription(ticket.getDescription());
        to.setLastException(ticket.getLastException());
        to.setStarred(Boolean.TRUE.equals(ticket.getStarred()));
        to.setOutOfWarrant(Boolean.TRUE.equals(ticket.getOutOfWarrant()));
        to.setDateCreated(ticket.getDateCreated());
        to.setLastUpdated(ticket.getLastUpdated());
        to.setAddress(toAddress(ticket.getAddress()));
        to.setHandler(toHandler(ticket.getHandler()));
        to.setCustomer(toCustomer(ticket.getCustomer()));
        to.setLineItems(toLineItems(ticket.getLineItems()));
        return to;
    }

    private static ShipToAddressTO toAddress(AddressDetail address) {
        if (address == null) {
            return null;
        }
        ShipToAddressTO to = new ShipToAddressTO();
        to.setName(address.name());
        to.setAddressLine1(address.addressLine1());
        to.setAddressLine2(address.addressLine2());
        to.setCity(address.city());
        to.setState(address.state());
        to.setZipCode(address.zipCode());
        to.setCountryCode(address.countryCode());
        to.setPhone(address.phone());
        return to;
    }

    private static CrmTicketHandlerTO toHandler(HandlerDetail handler) {
        if (handler == null) {
            return null;
        }
        CrmTicketHandlerTO to = new CrmTicketHandlerTO();
        to.setUsername(handler.username());
        to.setEmail(handler.email());
        to.setName(handler.name());
        return to;
    }

    private static CrmTicketCustomerTO toCustomer(CustomerDetail customer) {
        if (customer == null) {
            return null;
        }
        CrmTicketCustomerTO to = new CrmTicketCustomerTO();
        to.setId(customer.id());
        to.setName(customer.name());
        to.setEmail(customer.email());
        return to;
    }

    private static List<CrmTicketLineItemTO> toLineItems(List<LineItemSummary> lineItems) {
        if (lineItems == null) {
            return List.of();
        }
        return lineItems.stream().map(CrmTicketMapper::toLineItem).toList();
    }

    private static CrmTicketLineItemTO toLineItem(LineItemSummary item) {
        CrmTicketLineItemTO to = new CrmTicketLineItemTO();
        to.setSku(item.sku());
        to.setChannelSku(item.channelSku());
        to.setName(item.name());
        to.setQuantity(item.quantity());
        to.setRefundAmount(item.refundAmount());
        return to;
    }
}
