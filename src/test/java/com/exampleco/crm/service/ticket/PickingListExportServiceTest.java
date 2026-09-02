package com.exampleco.crm.service.ticket;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import com.exampleco.crm.dto.ticket.TicketReportSourceRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Pure unit test, no Spring context — PickingListExportService takes a List<TicketReportSourceRow>
// (a Spring Data interface projection, stubbed here via Mockito rather than a real query) and
// returns a byte[], so this can be verified the same way SalesOrderArchiveMigrationTest verifies
// its own pure helper methods.
class PickingListExportServiceTest {

    private final PickingListExportService service = new PickingListExportService();

    private TicketReportSourceRow rowStub() {
        TicketReportSourceRow row = mock(TicketReportSourceRow.class);
        when(row.getTicketId()).thenReturn(42297L);
        when(row.getSourceSystem()).thenReturn("az-us");
        when(row.getSku()).thenReturn("BO-46B-H");
        when(row.getItemName()).thenReturn("Adhesive Set");
        when(row.getQuantity()).thenReturn(1.0);
        when(row.getOrderNumber()).thenReturn("111-0000000-0000001");
        when(row.getTicketDateCreated()).thenReturn(LocalDateTime.of(2026, 8, 30, 10, 15));
        when(row.getBuyerFullName()).thenReturn("Penny Taylor");
        when(row.getBuyerEmail()).thenReturn("buyer3@example.com");
        when(row.getAddressName()).thenReturn("Penny Taylor");
        when(row.getAddressLine1()).thenReturn("123 Example Road");
        when(row.getAddressLine2()).thenReturn(null);
        when(row.getCity()).thenReturn("PITTSFIELD");
        when(row.getState()).thenReturn("MA");
        when(row.getZipCode()).thenReturn("01201");
        when(row.getCountryCode()).thenReturn("US");
        when(row.getPhone()).thenReturn(null);
        return row;
    }

    private List<String> lines(byte[] csv) {
        return List.of(new String(csv, StandardCharsets.UTF_8).split("\r\n"));
    }

    @Test
    void generateWritesTheHeaderAndOneDataRowInTheReferenceColumnOrder() {
        byte[] csv = service.generate(List.of(rowStub()));
        List<String> lines = lines(csv);

        assertThat(lines.get(0)).isEqualTo(
                "Ticket ID,Ticket Custom Field 2,Customer Order Number,Order Custom Field 1,Order Date,"
                        + "Order Source,Buyer Full Name,Buyer Email,Recipient Full Name,Address Line 1,"
                        + "Address Line 2,City,State,Postal Code,Country Code,Recipient Phone,Item SKU,"
                        + "Item Name,Item Description,Item Quantity");
        assertThat(lines.get(1)).isEqualTo(
                "StoreUS-42297,42297,111-0000000-0000001,111-0000000-0000001,08/30/2026,StoreUS,"
                        + "Penny Taylor,buyer3@example.com,Penny Taylor,123 Example Road,,"
                        + "PITTSFIELD,MA,01201,US,,BO-46B-H,Adhesive Set,Adhesive Set,1");
    }

    @Test
    void nullSourceFieldsRenderAsEmptyNotTheLiteralNullString() {
        TicketReportSourceRow row = rowStub();
        when(row.getBuyerEmail()).thenReturn(null);
        when(row.getState()).thenReturn(null);
        when(row.getCity()).thenReturn(null);
        when(row.getCountryCode()).thenReturn(null);

        String csv = new String(service.generate(List.of(row)), StandardCharsets.UTF_8);

        assertThat(csv).doesNotContain("null");
    }

    @Test
    void anUnmappedSourceSystemFallsBackToTheRawChannelCodeInsteadOfThrowing() {
        TicketReportSourceRow row = rowStub();
        when(row.getSourceSystem()).thenReturn("wm-us");

        List<String> lines = lines(service.generate(List.of(row)));

        assertThat(lines.get(1)).startsWith("wm-us-42297,42297,");
        assertThat(lines.get(1)).contains(",wm-us,");
    }

    @Test
    void quantityRoundsToTheNearestIntAndNullBecomesZero() {
        TicketReportSourceRow fractional = rowStub();
        when(fractional.getQuantity()).thenReturn(2.6);
        TicketReportSourceRow nullQuantity = rowStub();
        when(nullQuantity.getQuantity()).thenReturn(null);

        List<String> lines = lines(service.generate(List.of(fractional, nullQuantity)));

        assertThat(lines.get(1)).endsWith(",3");
        assertThat(lines.get(2)).endsWith(",0");
    }

    @Test
    void itemDescriptionMirrorsItemName() {
        List<String> lines = lines(service.generate(List.of(rowStub())));

        assertThat(lines.get(1)).contains(",Adhesive Set,Adhesive Set,");
    }

    @Test
    void blankBuyerFullNameFallsBackToTheRecipientFullName() {
        TicketReportSourceRow row = rowStub();
        when(row.getBuyerFullName()).thenReturn(null);
        when(row.getAddressName()).thenReturn("Jordan Recipient");

        List<String> lines = lines(service.generate(List.of(row)));

        assertThat(lines.get(1)).contains(",Jordan Recipient,buyer3@example.com,Jordan Recipient,");
    }
}
