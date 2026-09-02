package com.exampleco.crm.service.ticket;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import com.exampleco.crm.dto.ticket.TicketReportSourceRow;

// Builds the CSV backing TicketController#exportTicketReport, replacing the old nightly
// ticket_report table/page and the PDF picking-list export with one on-demand download in the
// exact column shape of the reference export the user provided ("Legacy  EP (1).csv"). No
// Spring/JPA dependency of its own (plain byte[] in, byte[] out) so it's unit-testable without a
// Spring context, same as SalesOrderArchiveMigration's pure helper methods.
@Component
public class PickingListExportService {

    private static final String[] HEADERS = {
            "Ticket ID", "Ticket Custom Field 2", "Customer Order Number", "Order Custom Field 1",
            "Order Date", "Order Source", "Buyer Full Name", "Buyer Email", "Recipient Full Name",
            "Address Line 1", "Address Line 2", "City", "State", "Postal Code", "Country Code",
            "Recipient Phone", "Item SKU", "Item Name", "Item Description", "Item Quantity"
    };

    private static final DateTimeFormatter ORDER_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    // Ported from the now-deleted TicketReportEnums.ORDER_RESOURCE_BY_CHANNEL (legacy-app's
    // TicketConsumer.groovy:109 channel->label map) — the human-readable "which storefront"
    // label, keyed by the same channel codes Ticket.sourceSystem normalizes to.
    private static final Map<String, String> ORDER_SOURCE_LABELS = Map.of(
            "az-us", "StoreUS",
            "az-deco", "StoreDeco",
            "az-ca", "StoreCA",
            "az-au", "StoreAU"
    );

    public byte[] generate(List<TicketReportSourceRow> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(HEADERS).build())) {
            for (TicketReportSourceRow row : rows) {
                printer.printRecord(toRecord(row));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private List<String> toRecord(TicketReportSourceRow row) {
        String orderSourceLabel = orderSourceLabel(row.getSourceSystem());
        return List.of(
                orderSourceLabel + "-" + row.getTicketId(),
                String.valueOf(row.getTicketId()),
                blankIfNull(row.getOrderNumber()),
                blankIfNull(row.getOrderNumber()),
                row.getTicketDateCreated() == null ? "" : row.getTicketDateCreated().format(ORDER_DATE_FORMAT),
                orderSourceLabel,
                buyerFullName(row),
                blankIfNull(row.getBuyerEmail()),
                blankIfNull(row.getAddressName()),
                blankIfNull(row.getAddressLine1()),
                blankIfNull(row.getAddressLine2()),
                blankIfNull(row.getCity()),
                blankIfNull(row.getState()),
                blankIfNull(row.getZipCode()),
                blankIfNull(row.getCountryCode()),
                blankIfNull(row.getPhone()),
                blankIfNull(row.getSku()),
                blankIfNull(row.getItemName()),
                blankIfNull(row.getItemName()),
                String.valueOf(row.getQuantity() == null ? 0 : Math.round(row.getQuantity()))
        );
    }

    private String orderSourceLabel(String sourceSystem) {
        return ORDER_SOURCE_LABELS.getOrDefault(sourceSystem, sourceSystem);
    }

    // buyerFullName's own SQL-level COALESCE (customer.name, ticket.customer_name) can still come
    // back null/blank — fall back to the recipient's name off the shipping address in that case.
    private String buyerFullName(TicketReportSourceRow row) {
        String buyerFullName = row.getBuyerFullName();
        return (buyerFullName == null || buyerFullName.isBlank()) ? blankIfNull(row.getAddressName()) : buyerFullName;
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
