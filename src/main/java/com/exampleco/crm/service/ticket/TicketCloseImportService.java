package com.exampleco.crm.service.ticket;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.dto.ticket.CloseImportRowResult;
import com.exampleco.crm.dto.ticket.CloseImportRowResult.Outcome;
import com.exampleco.crm.dto.ticket.CloseImportSummary;
import com.exampleco.crm.repository.ticket.TicketRepository;

// Replaces legacy-app's importCloseTicketData — a "these all shipped" bulk close of
// a batch of tickets from the shipping-label vendor's export, the file CS actually
// gets back from the label vendor after uploading own Export Ticket Report CSV. That
// round trip is why this keys on ticket.id (renders it into "Custom - Field 2" and the
// vendor passes it straight through) rather than the legacy_id used by the M2M by-legacy lookup.
//
// A previous version of this service invented a ticketId,trackingNumber,comment
// layout because no real export was available to confirm one — that format never matched
// anything CS actually uploads and is gone. There is no comment column in the real file, so this
// import never adds a closing comment.
//
// Reuses TicketCloseService for the actual close — including the buyer-facing
// replacement-shipped mail (ReplacementShippedMailService) the single-ticket close
// endpoint also triggers, so a bulk import doesn't skip it just because it's bulk. Passes
// includeBcc=false on every row (see TicketCloseService#close's own comment): one upload can
// close hundreds of tickets, and BCCing CS on every single one would blast their inbox.
@Component
public class TicketCloseImportService {

    // Positional, not read from the file's own header text: setSkipHeaderRecord below discards
    // whatever the file's first row actually says, so a stray UTF-8 BOM or minor header-text
    // drift in the vendor's own export can't break column mapping. Column order is the
    // confirmed real layout — trailing columns beyond these 7 are tolerated, not
    // rejected, since this is a vendor export CRM doesn't control the shape of.
    private static final List<String> HEADERS = List.of("Order - Source", "Order - Number", "Carrier - Name",
            "Shipment - Tracking Number", "Date - Label Create Date", "Custom - Field 1", "Custom - Field 2");

    // legacy-app's own importCloseTicketData used this same Order-Source -> sourceSystem map.
    // Doubles as the row filter (§2): a row whose Order - Number doesn't start with one of these
    // keys isn't a ticket row at all (a plain shipping-label row for a non-ticket order, or a
    // different channel this app doesn't carry tickets for) and is reported SKIPPED, not
    // FAILURE.
    private static final Map<String, String> SOURCE_TO_CHANNEL = new LinkedHashMap<>();
    static {
        SOURCE_TO_CHANNEL.put("StoreUS", "az-us");
        SOURCE_TO_CHANNEL.put("StoreCA", "az-ca");
        SOURCE_TO_CHANNEL.put("StoreDeco", "az-deco");
        SOURCE_TO_CHANNEL.put("StoreAU", "az-au");
    }

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final TicketRepository ticketRepository;
    private final TicketCloseService ticketCloseService;

    public TicketCloseImportService(TicketRepository ticketRepository, TicketCloseService ticketCloseService) {
        this.ticketRepository = ticketRepository;
        this.ticketCloseService = ticketCloseService;
    }

    // authorUsername attributes any closing comment to the member who uploaded the file, same
    // as a single manual close attributes it to whoever clicked the button — there's no comment
    // column in this file, so today that only matters if a future column adds one.
    public CloseImportSummary importCsv(byte[] csvBytes, String authorUsername) {
        List<CloseImportRowResult> results = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(HEADERS.toArray(new String[0]))
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build();

        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(stripBom(csvBytes)), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            for (CSVRecord record : parser) {
                // +2: 1-based, and the header row itself isn't a data row — matches what a
                // person looking at this file in a spreadsheet would call this row.
                int rowNumber = (int) record.getRecordNumber() + 1;
                results.add(processRow(rowNumber, record, authorUsername));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read CSV file: " + e.getMessage());
        }

        long successCount = results.stream().filter(r -> r.outcome() == Outcome.SUCCESS).count();
        long skippedCount = results.stream().filter(r -> r.outcome() == Outcome.SKIPPED).count();
        long failureCount = results.size() - successCount - skippedCount;
        return new CloseImportSummary(results.size(), (int) successCount, (int) failureCount, (int) skippedCount, results);
    }

    private CloseImportRowResult processRow(int rowNumber, CSVRecord record, String authorUsername) {
        String orderNumber = trimToEmpty(get(record, "Order - Number"));

        // §2: not a ticket row at all (e.g. AMZUS/AMZEP label rows, or a blank-source row) —
        // skip rather than fail, since this file is the raw vendor export, not a CRM-authored
        // one, and most of it was never meant to close a ticket.
        String matchedPrefix = SOURCE_TO_CHANNEL.keySet().stream()
                .filter(prefix -> orderNumber.startsWith(prefix + "-"))
                .findFirst()
                .orElse(null);
        if (matchedPrefix == null) {
            return new CloseImportRowResult(rowNumber, null, Outcome.SKIPPED, "Not a ticket row (Order - Number: " +
                    (orderNumber.isEmpty() ? "blank" : orderNumber) + ")");
        }

        Long ticketId = null;
        try {
            String customField2 = trimToEmpty(get(record, "Custom - Field 2"));
            if (customField2.isEmpty()) {
                return new CloseImportRowResult(rowNumber, null, Outcome.FAILURE, "Custom - Field 2 is blank");
            }
            try {
                ticketId = Long.valueOf(customField2);
            } catch (NumberFormatException e) {
                return new CloseImportRowResult(rowNumber, null, Outcome.FAILURE,
                        "Custom - Field 2 is not a valid ticket id: " + customField2);
            }

            // §3: both Custom - Field 2 and the Order - Number suffix come from the same
            // ticket.id in export, so they agree on every well-formed row —
            // disagreement means the file was hand-edited or corrupted, and Custom - Field 2 is
            // the authoritative one.
            String orderNumberSuffix = orderNumber.substring(matchedPrefix.length() + 1);
            if (!orderNumberSuffix.equals(customField2)) {
                return new CloseImportRowResult(rowNumber, ticketId, Outcome.FAILURE,
                        "Custom - Field 2 (" + customField2 + ") does not match Order - Number suffix (" +
                                orderNumberSuffix + ")");
            }

            Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
            if (ticket == null) {
                return new CloseImportRowResult(rowNumber, ticketId, Outcome.FAILURE,
                        "No ticket found with id " + ticketId);
            }

            // §3 channel cross-check: this is what stops a stale legacy-app-era file (where Custom -
            // Field 2 was a legacy id, not this app's own ticket.id) from closing an unrelated
            // ticket in some other channel — see this class's own comment and 
            // StoreUS-30116 sample row.
            String orderSource = trimToEmpty(get(record, "Order - Source"));
            String expectedChannel = SOURCE_TO_CHANNEL.get(orderSource);
            if (expectedChannel == null) {
                return new CloseImportRowResult(rowNumber, ticketId, Outcome.FAILURE,
                        "Unknown Order - Source: " + (orderSource.isEmpty() ? "blank" : orderSource));
            }
            if (!expectedChannel.equals(ticket.getSourceSystem())) {
                return new CloseImportRowResult(rowNumber, ticketId, Outcome.FAILURE,
                        "Order - Source (" + orderSource + " -> " + expectedChannel +
                                ") does not match ticket's own channel (" + ticket.getSourceSystem() + ")");
            }

            // §4: the real carrier, verbatim — never the tracking-number-prefix heuristic the
            // manual close path falls back to, since that heuristic can't produce "FedEx" at
            // all and this file already carries the real carrier for every ticket row.
            String carrier = trimToEmpty(get(record, "Carrier - Name"));
            if (carrier.isEmpty()) {
                return new CloseImportRowResult(rowNumber, ticketId, Outcome.FAILURE, "Carrier - Name is blank");
            }

            String trackingNumber = blankToNull(get(record, "Shipment - Tracking Number"));
            ticketCloseService.close(ticketId, trackingNumber, null, authorUsername, carrier, false);
            return new CloseImportRowResult(rowNumber, ticketId, Outcome.SUCCESS, null);
        } catch (Exception e) {
            return new CloseImportRowResult(rowNumber, ticketId, Outcome.FAILURE, e.getMessage());
        }
    }

    // Tolerates a data row with fewer columns than HEADERS (a short/malformed row in the vendor
    // export) by treating a missing column as blank rather than letting CSVParser's own
    // IllegalArgumentException escape and abort the whole row's own try/catch classification.
    private static String get(CSVRecord record, String header) {
        return record.isMapped(header) && record.isSet(header) ? record.get(header) : "";
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static byte[] stripBom(byte[] bytes) {
        if (bytes.length >= UTF8_BOM.length && Arrays.equals(bytes, 0, UTF8_BOM.length, UTF8_BOM, 0, UTF8_BOM.length)) {
            return Arrays.copyOfRange(bytes, UTF8_BOM.length, bytes.length);
        }
        return bytes;
    }
}
