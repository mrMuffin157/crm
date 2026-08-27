package com.exampleco.crm.dto.ticket;

import java.time.LocalDateTime;

// Projection for TicketLineItemRepository#findReportSource — one row per line item on a
// currently ManualHandling/Processing ticket, joined with its ticket/address/customer, in the
// shape PickingListExportService needs to build a CSV row for TicketController#exportTicketReport.
// Getter names match the native query's column aliases (Spring Data's interface-projection
// convention).
public interface TicketReportSourceRow {
    Long getTicketId();

    String getSourceSystem();

    String getSku();

    String getItemName();

    Double getQuantity();

    String getOrderNumber();

    LocalDateTime getTicketDateCreated();

    String getBuyerFullName();

    String getBuyerEmail();

    String getAddressName();

    String getAddressLine1();

    String getAddressLine2();

    String getCity();

    String getState();

    String getZipCode();

    String getCountryCode();

    String getPhone();
}
