package com.exampleco.crm.service.ticket;

import com.exampleco.core.dto.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.dto.ticket.CloseImportRowResult;
import com.exampleco.crm.dto.ticket.CloseImportRowResult.Outcome;
import com.exampleco.crm.dto.ticket.CloseImportSummary;
import com.exampleco.crm.repository.ticket.TicketRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// TicketCloseImportService parses the real shipping-label vendor export,
// not the invented ticketId,trackingNumber,comment layout shipped with no confirmed
// format to check against. Two fixtures, matching this ticket's own acceptance criteria: a
// file (column layout + skip-filter + stale-file rejection) and a small
// file (the real happy path, keyed on this app's own ticket.id).
//
// folded the buyer-facing mail into TicketCloseService#close itself (no more separate
// LegacyTicketHandleClient notify step here) — every close() stub/verify below now passes
// includeBcc=false, since the CSV bulk path must never BCC the notify list on every one of a
// 248-row import (see TicketCloseService's own comment).
@ExtendWith(MockitoExtension.class)
class TicketCloseImportServiceTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketCloseService ticketCloseService;

    private TicketCloseImportService newService() {
        return new TicketCloseImportService(ticketRepository, ticketCloseService);
    }

    private Ticket ticketStub(Long id, String sourceSystem) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setSourceSystem(sourceSystem);
        ticket.setStatus(TicketStatus.Processing);
        return ticket;
    }

    private static byte[] utf8(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withBom(String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        out.writeBytes(utf8(content));
        return out.toByteArray();
    }

    // --- fixture: column layout, the skip filter, and stale-file rejection ---

    private static final String LEGACY_1116_HEADER =
            "Order - Source,Order - Number,Carrier - Name,Shipment - Tracking Number,Date - Label Create Date," +
                    "Custom - Field 1,Custom - Field 2\n";

    @Test
    void staleLegacyAppEraRow_channelMismatch_isAFailureNotASuccess() {
        // id=30116 is really az-ca in crm_dev (migrated from a different storefront than this
        // 2022-era file's own Order - Source implies) — the exact hazard this cross-check exists
        // to catch own sample row.
        when(ticketRepository.findById(30116L)).thenReturn(Optional.of(ticketStub(30116L, "az-ca")));
        String csv = LEGACY_1116_HEADER +
                "\"StoreUS\",\"StoreUS-30116\",\"USPS\",\"9400100000000000000001\",\"10/3/2022 3:04:08 PM\"," +
                "\"FBA16TCKDZX1\",\"30116\"\n";

        CloseImportSummary summary = newService().importCsv(utf8(csv), "csagent");

        assertThat(summary.totalRows()).isEqualTo(1);
        assertThat(summary.failureCount()).isEqualTo(1);
        CloseImportRowResult row = summary.rows().get(0);
        assertThat(row.outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(row.ticketId()).isEqualTo(30116L);
        assertThat(row.message()).contains("az-ca");
        verifyNoInteractions(ticketCloseService);
    }

    @Test
    void nonLegacyRows_areSkippedNotFailed() {
        String csv = LEGACY_1116_HEADER +
                // AMZUS label row for a non-ticket order — not a Store* order number at all.
                "\"AMZUS\",\"AMZUS-702-7427625\",\"USPS\",\"9400111111111111111111\",\"10/3/2022 3:04:08 PM\",\"\",\"\"\n" +
                // Blank-source label row (no Order - Number prefix match either).
                "\"\",\"100001\",\"USPS\",\"9400100000000000000002\",\"10/3/2022 3:04:08 PM\",\"\",\"\"\n";

        CloseImportSummary summary = newService().importCsv(utf8(csv), "csagent");

        assertThat(summary.totalRows()).isEqualTo(2);
        assertThat(summary.skippedCount()).isEqualTo(2);
        assertThat(summary.failureCount()).isZero();
        assertThat(summary.successCount()).isZero();
        assertThat(summary.rows()).allSatisfy(row -> assertThat(row.outcome()).isEqualTo(Outcome.SKIPPED));
        verifyNoInteractions(ticketRepository, ticketCloseService);
    }

    @Test
    void blankCustomField2_isAFailure() {
        String csv = LEGACY_1116_HEADER +
                "\"StoreDeco\",\"StoreDeco-1374\",\"USPS\",\"9400111111111111111111\",\"10/3/2022 3:04:08 PM\",\"\",\"\"\n";

        CloseImportSummary summary = newService().importCsv(utf8(csv), "csagent");

        assertThat(summary.failureCount()).isEqualTo(1);
        CloseImportRowResult row = summary.rows().get(0);
        assertThat(row.outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(row.ticketId()).isNull();
        assertThat(row.message()).contains("Custom - Field 2");
        verifyNoInteractions(ticketRepository, ticketCloseService);
    }

    @Test
    void customField2DisagreeingWithOrderNumberSuffix_isAFailure() {
        String csv = LEGACY_1116_HEADER +
                "\"StoreUS\",\"StoreUS-30116\",\"USPS\",\"9400100000000000000001\",\"10/3/2022 3:04:08 PM\"," +
                "\"FBA16TCKDZX1\",\"99999\"\n";

        CloseImportSummary summary = newService().importCsv(utf8(csv), "csagent");

        assertThat(summary.failureCount()).isEqualTo(1);
        CloseImportRowResult row = summary.rows().get(0);
        assertThat(row.outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(row.ticketId()).isEqualTo(99999L);
        assertThat(row.message()).contains("does not match");
        verifyNoInteractions(ticketRepository, ticketCloseService);
    }

    @Test
    void ticketNotFound_isAFailure() {
        when(ticketRepository.findById(42297L)).thenReturn(Optional.empty());
        String csv = LEGACY_1116_HEADER +
                "\"StoreUS\",\"StoreUS-42297\",\"USPS\",\"9400100000000000000001\",\"10/3/2022 3:04:08 PM\"," +
                "\"FBA16TCKDZX1\",\"42297\"\n";

        CloseImportSummary summary = newService().importCsv(utf8(csv), "csagent");

        assertThat(summary.failureCount()).isEqualTo(1);
        assertThat(summary.rows().get(0).message()).contains("No ticket found");
        verifyNoInteractions(ticketCloseService);
    }

    @Test
    void unknownOrderSource_isAFailure() {
        // Order - Number's own prefix passed the row filter, but Order - Source itself doesn't
        // resolve to any known channel — a malformed/hand-edited row, not a real export row.
        when(ticketRepository.findById(42297L)).thenReturn(Optional.of(ticketStub(42297L, "az-us")));
        String csv = LEGACY_1116_HEADER +
                "\"StoreXX\",\"StoreUS-42297\",\"USPS\",\"9400100000000000000001\",\"10/3/2022 3:04:08 PM\"," +
                "\"FBA16TCKDZX1\",\"42297\"\n";

        CloseImportSummary summary = newService().importCsv(utf8(csv), "csagent");

        assertThat(summary.failureCount()).isEqualTo(1);
        assertThat(summary.rows().get(0).message()).contains("Unknown Order - Source");
        verifyNoInteractions(ticketCloseService);
    }

    @Test
    void blankCarrierName_isAFailureNotASilentDeriveFallback() {
        when(ticketRepository.findById(42297L)).thenReturn(Optional.of(ticketStub(42297L, "az-us")));
        String csv = LEGACY_1116_HEADER +
                "\"StoreUS\",\"StoreUS-42297\",\"\",\"1Z999AA10123456784\",\"10/3/2022 3:04:08 PM\"," +
                "\"FBA16TCKDZX1\",\"42297\"\n";

        CloseImportSummary summary = newService().importCsv(utf8(csv), "csagent");

        assertThat(summary.failureCount()).isEqualTo(1);
        assertThat(summary.rows().get(0).message()).contains("Carrier - Name");
        verifyNoInteractions(ticketCloseService);
    }

    @Test
    void utf8Bom_andQuotedFields_areParsedCorrectly() {
        Ticket ticket = ticketStub(42297L, "az-us");
        when(ticketRepository.findById(42297L)).thenReturn(Optional.of(ticket));
        when(ticketCloseService.close(eq(42297L), eq("9400100000000000000001"), isNull(), eq("csagent"), eq("USPS"), eq(false)))
                .thenReturn(ticket);
        String csv = LEGACY_1116_HEADER +
                "\"StoreUS\",\"StoreUS-42297\",\"USPS\",\"9400100000000000000001\",\"10/3/2022 3:04:08 PM\"," +
                "\"FBA16TCKDZX1\",\"42297\"\n";

        CloseImportSummary summary = newService().importCsv(withBom(csv), "csagent");

        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.rows().get(0).outcome()).isEqualTo(Outcome.SUCCESS);
        verify(ticketCloseService).close(42297L, "9400100000000000000001", null, "csagent", "USPS", false);
    }

    // --- fixture: the real happy path, keyed on this app's own ticket.id ---

    @Test
    void happyPath_closesWithTheRowsOwnCarrierVerbatimAndNoBcc() {
        Ticket ticket = ticketStub(43112L, "az-ca");
        when(ticketRepository.findById(43112L)).thenReturn(Optional.of(ticket));
        when(ticketCloseService.close(eq(43112L), eq("1Z999AA10123456784"), isNull(), eq("csagent"), eq("FedEx"), eq(false)))
                .thenReturn(ticket);
        String csv = LEGACY_1116_HEADER +
                // Tracking number looks like a UPS ("1Z") number, but Carrier - Name says FedEx
                // — the whole reason this import can't use the manual close path's 1Z heuristic.
                "\"StoreCA\",\"StoreCA-43112\",\"FedEx\",\"1Z999AA10123456784\",\"9/2/2026 3:04:08 PM\"," +
                "\"FBA1EXAMPLE1\",\"43112\"\n";

        CloseImportSummary summary = newService().importCsv(utf8(csv), "csagent");

        assertThat(summary.totalRows()).isEqualTo(1);
        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.failureCount()).isZero();
        assertThat(summary.skippedCount()).isZero();
        verify(ticketCloseService).close(43112L, "1Z999AA10123456784", null, "csagent", "FedEx", false);
    }

    @Test
    void multipleRows_summaryCountsEachOutcomeIndependently() {
        Ticket goodTicket = ticketStub(43112L, "az-ca");
        when(ticketRepository.findById(43112L)).thenReturn(Optional.of(goodTicket));
        when(ticketCloseService.close(eq(43112L), eq("1Z999AA10123456784"), isNull(), eq("csagent"), eq("FedEx"), eq(false)))
                .thenReturn(goodTicket);
        when(ticketRepository.findById(30116L)).thenReturn(Optional.of(ticketStub(30116L, "az-ca")));
        String csv = LEGACY_1116_HEADER +
                // success
                "\"StoreCA\",\"StoreCA-43112\",\"FedEx\",\"1Z999AA10123456784\",\"9/2/2026 3:04:08 PM\"," +
                "\"FBA1EXAMPLE1\",\"43112\"\n" +
                // failure: stale-file channel mismatch (StoreUS maps to az-us, ticket is az-ca)
                "\"StoreUS\",\"StoreUS-30116\",\"USPS\",\"9400100000000000000001\",\"10/3/2022 3:04:08 PM\"," +
                "\"FBA16TCKDZX1\",\"30116\"\n" +
                // skipped: not a Store* order
                "\"AMZUS\",\"AMZUS-702-7427625\",\"USPS\",\"9400111111111111111111\",\"10/3/2022 3:04:08 PM\",\"\",\"\"\n";

        CloseImportSummary summary = newService().importCsv(utf8(csv), "csagent");

        assertThat(summary.totalRows()).isEqualTo(3);
        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.failureCount()).isEqualTo(1);
        assertThat(summary.skippedCount()).isEqualTo(1);
        assertThat(summary.rows()).extracting(CloseImportRowResult::outcome)
                .containsExactly(Outcome.SUCCESS, Outcome.FAILURE, Outcome.SKIPPED);
    }
}
