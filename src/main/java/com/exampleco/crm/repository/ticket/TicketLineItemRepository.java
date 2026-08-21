package com.exampleco.crm.repository.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import com.exampleco.crm.domain.ticket.TicketLineItem;
import com.exampleco.crm.dto.ticket.TicketReportSourceRow;

public interface TicketLineItemRepository extends JpaRepository<TicketLineItem, Long> {

    // Backs the ticket list's "Export ticket report" CSV (TicketController#exportTicketReport /
    // PickingListExportService): every line item on a ticket that is ManualHandling and
    // Processing right now (t.action/t.status hold those enum's name() strings — see
    // TicketStatusConverter/TicketActionConverter), joined with buyer/address detail. LEFT JOIN
    // address/customer, same reasoning as TicketRepositoryImpl's FROM_CLAUSE: a ticket without
    // one shouldn't disappear from this query.
    //
    // Scoped to allowedChannels like every other ticket read: this export is a ticket
    // list in CSV clothing, so it has to answer to the same row-level channel rule
    // TicketRepositoryImpl's WHERE_CLAUSE applies — an admin/service caller resolves to the full
    // channel universe there, which makes it a no-op for them. The caller never passes an empty
    // collection (TicketController#exportTicketReport short-circuits first), since an empty
    // "IN ()" is invalid SQL — same guard the ticket list already has.
    @Query(value = """
            SELECT tli.ticket_id AS ticketId,
                   t.source_system AS sourceSystem,
                   tli.sku AS sku,
                   tli.name AS itemName,
                   tli.quantity AS quantity,
                   t.order_number AS orderNumber,
                   t.date_created AS ticketDateCreated,
                   COALESCE(c.name, t.customer_name) AS buyerFullName,
                   COALESCE(c.email, t.email) AS buyerEmail,
                   a.name AS addressName,
                   a.address_line1 AS addressLine1,
                   a.address_line2 AS addressLine2,
                   a.city AS city,
                   a.state AS state,
                   a.zip_code AS zipCode,
                   a.country_code AS countryCode,
                   a.phone AS phone
            FROM ticket_line_item tli
            JOIN ticket t ON t.id = tli.ticket_id
            LEFT JOIN address a ON a.id = t.customer_address_id
            LEFT JOIN customer c ON c.id = t.customer_id
            WHERE t.action = :action AND t.status = :status
              AND t.source_system IN (:allowedChannels)
            """, nativeQuery = true)
    List<TicketReportSourceRow> findReportSource(@Param("action") String action, @Param("status") String status,
                                                  @Param("allowedChannels") Collection<String> allowedChannels);

    // Backs the ticket list's "Line Items" column/detail view (batched by TicketController so
    // listing a page of tickets costs one extra query, not one per ticket) and
    // TicketController#persistLineItems' own by-sku reconciliation against a ticket's existing
    // rows.
    List<TicketLineItem> findByTicketIdIn(List<Long> ticketIds);
}
