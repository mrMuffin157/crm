package com.exampleco.crm.tools;

import com.exampleco.core.dto.customer.CustomerTO;
import com.exampleco.core.dto.order.FulfillmentPackageTO;
import com.exampleco.core.dto.order.SalesOrderInfoTO;
import com.exampleco.core.dto.order.SalesOrderLineItemTO;
import com.exampleco.core.dto.order.ShipToAddressTO;
import com.exampleco.core.order.SalesChannel;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * One-time, manually-run tool -- NOT wired into the Spring application context (plain
 * {@code main()}, no {@code @Component}/{@code @SpringBootApplication} scanning involved, same
 * reasoning as doc/0.2.0/migrate_tickets_to_crm_dev.sql being a standalone script rather than
 * app code). See doc/0.5.0/create_sales_order_archive.sql for the target schema and the full
 * design rationale (why two tables, why this can't be plain cross-database SQL like the ticket
 * migration).
 * <p>
 * Reads sales_order / sales_order_line_item / fulfillment_package / customer / address from the
 * store1_dev archive (a separate physical MySQL server from crm_dev -- no cross-database SQL
 * join is possible), and writes crm_dev.sales_order_archive / sales_order_archive_tracking in
 * the exact JSON shape legacy-app's own SalesOrderInfoService.groovy produces for the live lookup,
 * so archived and live orders deserialize into the same {@link SalesOrderInfoTO}. customer/
 * address are looked up the same batched, chunk-scoped way as line items and fulfillment
 * packages (a bulk {@code IN (...)} keyed off the distinct ids in the current chunk) rather than
 * joined into the master {@code sales_order} query -- doc/0.5.0's design note on avoiding a
 * whole-table join against those two tables (11G/15G on the archive host) still applies; a
 * chunk-scoped lookup only ever touches the handful of rows the chunk actually references.
 * <p>
 * Batched by keyset pagination on sales_order.id (required at this table's real size --
 * OFFSET-based paging would degrade badly) and idempotent via
 * {@code ON DUPLICATE KEY UPDATE} against {@code uk_source_system_sales_order_id}, so a run
 * that dies partway through (this archive host has already shown real network flakiness) can
 * simply be re-run: it resumes from {@code MAX(source_sales_order_id)} already committed in
 * crm_dev rather than restarting from the top or double-inserting. That same resume-by-max
 * logic means re-running this fixed version only backfills buyer/recipient/ship-to/fulfillment-
 * package data onto rows this run is newly reaching -- it does NOT retroactively enrich rows a
 * prior run of the old version already committed, since those ids are all below the resume
 * point. Backfilling those requires a deliberate full re-walk (temporarily treating
 * {@link #findResumePoint} as 0) so the existing {@code ON DUPLICATE KEY UPDATE} overwrites
 * {@code sales_order_info} on every already-migrated row.
 * <p>
 * <p>
 * status/salesChannel/fulfillmentType decode from raw legacy int codes to the enum NAME strings
 * SalesOrderInfoTO expects; an id none of the three lookups recognize degrades that one field to
 * null and logs {@code sales_order.id} + the raw code, rather than throwing and aborting the
 * whole chunk (see {@link #decodeOrWarn}). Unlike {@code ticket.reason}'s equivalent fix
 * (TicketReasonConverter) -- which only tolerated an unmappable id where a specific bad
 * row had already been confirmed, and left status/action/refund_type strict since those drive
 * live business logic -- this data is a read-only, already-settled archived order's display
 * fields, not something any code branches on. A single retired code anywhere in this very large
 * table aborting the entire run (and blocking every order after it) is a worse outcome
 * than one archived order occasionally showing a blank status/channel/fulfillment-type.
 * <p>
 * Required env vars (no defaults, same reasoning as DB_USER/DB_PASSWORD elsewhere in this app --
 * real credentials should never have a literal default):
 * ARCHIVE_DB_HOST, ARCHIVE_DB_PORT, ARCHIVE_DB_NAME, ARCHIVE_DB_USER, ARCHIVE_DB_PASSWORD,
 * DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD (the last five match this app's own
 * dev/test/prod datasource var names -- point them at whichever crm_dev you're migrating into).
 * <p>
 * Optional env vars: ARCHIVE_DB_ALLOW_PUBLIC_KEY_RETRIEVAL and DB_ALLOW_PUBLIC_KEY_RETRIEVAL
 * (both default {@code false}) -- set the one for whichever host runs MySQL 8's
 * caching_sha2_password, which otherwise rejects a plaintext login with "Public Key Retrieval
 * is not allowed". DB_ALLOW_PUBLIC_KEY_RETRIEVAL is the same var application-{dev,test}
 * .properties already reads for the app's own datasource. See the comment on archiveUrl in
 * {@link #main} for why this is opt-in per host rather than always on.
 * <p>
 * Run directly (IDE "Run" on {@code main}, or {@code java -cp <classpath>
 * com.exampleco.crm.tools.SalesOrderArchiveMigration}) with those env vars set -- it is not
 * part of {@code bootRun} and does not start the Spring Boot application.
 */
public final class SalesOrderArchiveMigration {

    private static final String SOURCE_SYSTEM = "store1_dev";
    private static final int CHUNK_SIZE = 5000;
    private static final int MAX_CHUNK_RETRIES = 3;

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");

        // allowPublicKeyRetrieval mirrors application-{dev,test}.properties' own
        // DB_ALLOW_PUBLIC_KEY_RETRIEVAL knob, and defaults off for the same reason the driver
        // itself defaults it off: with useSSL=false, turning it on lets the client fetch the
        // server's RSA public key over a plaintext channel, so a man-in-the-middle could serve
        // its own key and recover the password. A caching_sha2_password server (MySQL 8's
        // default) rejects a first-time plaintext login without it -- "Public Key Retrieval is
        // not allowed" -- so it's opt-in per host, never hardcoded true.
        String archiveUrl = "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=%s&serverTimezone=UTC".formatted(
                requiredEnv("ARCHIVE_DB_HOST"), requiredEnv("ARCHIVE_DB_PORT"), requiredEnv("ARCHIVE_DB_NAME"),
                optionalEnv("ARCHIVE_DB_ALLOW_PUBLIC_KEY_RETRIEVAL", "false"));
        // rewriteBatchedStatements=true is required for addBatch()/executeBatch() below to
        // actually collapse into a handful of multi-row statements -- without it,
        // mysql-connector-j still sends one network round-trip per row in the batch, which is
        // exactly what made the first version of this job run at ~50 orders/sec (days of total
        // runtime against this very large table) instead of the bulk operations this
        // version uses.
        String crmUrl = "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=%s&serverTimezone=UTC&rewriteBatchedStatements=true".formatted(
                requiredEnv("DB_HOST"), requiredEnv("DB_PORT"), requiredEnv("DB_NAME"),
                optionalEnv("DB_ALLOW_PUBLIC_KEY_RETRIEVAL", "false"));

        // Jackson 3's own default already has WRITE_DATES_AS_TIMESTAMPS off (unlike Jackson 2,
        // where Spring Boot had to disable it itself) -- confirmed directly against this
        // project's actual jackson-databind 3.1.4: a bare `new ObjectMapper()` already writes
        // java.util.Date as an ISO-8601 string, matching what the live
        // /api/salesApi/getSalesOrderInfo endpoint's Spring-managed mapper produces. No extra
        // config needed for archived and live JSON to stay date-format-identical.
        ObjectMapper objectMapper = new ObjectMapper();

        try (Connection archiveConn = DriverManager.getConnection(archiveUrl, requiredEnv("ARCHIVE_DB_USER"), requiredEnv("ARCHIVE_DB_PASSWORD"));
             Connection crmConn = DriverManager.getConnection(crmUrl, requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"))) {
            crmConn.setAutoCommit(false);

            long resumeAfterId = findResumePoint(crmConn);
            System.out.println("Resuming after source sales_order.id = " + resumeAfterId);

            long lastIdInChunk = resumeAfterId;
            int totalMigrated = 0;
            List<SourceSalesOrder> chunk;
            while (!(chunk = fetchChunk(archiveConn, lastIdInChunk)).isEmpty()) {
                runChunkWithRetries(archiveConn, crmConn, objectMapper, chunk);
                lastIdInChunk = chunk.get(chunk.size() - 1).id;
                totalMigrated += chunk.size();
                System.out.println("Migrated up to source sales_order.id = " + lastIdInChunk
                        + " (" + totalMigrated + " orders this run)");
            }
            System.out.println("Done. " + totalMigrated + " orders migrated this run.");
        }
    }

    private static void runChunkWithRetries(Connection archiveConn, Connection crmConn, ObjectMapper objectMapper,
                                             List<SourceSalesOrder> chunk) throws SQLException {
        List<Long> orderIds = chunk.stream().map(o -> o.id).toList();
        Map<Long, List<SalesOrderLineItemTO>> lineItemsByOrderId = fetchLineItems(archiveConn, orderIds);
        Map<Long, List<FulfillmentPackageTO>> fulfillmentPackagesByOrderId = fetchFulfillmentPackages(archiveConn, orderIds);

        List<String> customerIds = chunk.stream().map(o -> o.customerId)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        Map<String, CustomerRow> customersById = fetchCustomers(archiveConn, customerIds);

        List<String> shippingAddressIds = chunk.stream().map(o -> o.shippingAddressId)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        Map<String, AddressRow> addressesById = fetchAddresses(archiveConn, shippingAddressIds);

        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_CHUNK_RETRIES; attempt++) {
            try {
                writeChunk(crmConn, objectMapper, chunk, lineItemsByOrderId, fulfillmentPackagesByOrderId,
                        customersById, addressesById);
                return;
            } catch (SQLException e) {
                lastFailure = e;
                crmConn.rollback();
                System.err.println("Chunk write failed (attempt " + attempt + "/" + MAX_CHUNK_RETRIES + "): " + e);
            }
        }
        throw new IllegalStateException("Giving up on chunk ending at id=" + chunk.get(chunk.size() - 1).id
                + " after " + MAX_CHUNK_RETRIES + " attempts", lastFailure);
    }

    // Bulk per-chunk instead of per-order: the original version did one upsert + one delete
    // round-trip to crm_dev PER ORDER (retrieving each row's generated/matched id individually
    // via LAST_INSERT_ID so the tracking child rows could reference it), which meant ~2 network
    // round-trips per order -- ~1000 for a 500-order chunk. That measured at ~50 orders/sec,
    // which against this very large table is well over a week of runtime. This version
    // does the whole chunk in 4 round-trips total: one batched upsert, one bulk id lookup, one
    // bulk delete, one batched insert.
    private static void writeChunk(Connection crmConn, ObjectMapper objectMapper, List<SourceSalesOrder> chunk,
                                    Map<Long, List<SalesOrderLineItemTO>> lineItemsByOrderId,
                                    Map<Long, List<FulfillmentPackageTO>> fulfillmentPackagesByOrderId,
                                    Map<String, CustomerRow> customersById,
                                    Map<String, AddressRow> addressesById) throws SQLException {
        String upsertSql = """
                INSERT INTO sales_order_archive
                    (source_system, source_sales_order_id, amazon_order_id, sales_order_info, date_created)
                VALUES (?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                    amazon_order_id = VALUES(amazon_order_id),
                    sales_order_info = VALUES(sales_order_info)
                """;
        try (PreparedStatement upsert = crmConn.prepareStatement(upsertSql)) {
            for (SourceSalesOrder order : chunk) {
                List<SalesOrderLineItemTO> lineItems = lineItemsByOrderId.getOrDefault(order.id, List.of());
                List<FulfillmentPackageTO> fulfillmentPackages = fulfillmentPackagesByOrderId.getOrDefault(order.id, List.of());
                CustomerRow customer = order.customerId == null ? null : customersById.get(order.customerId);
                AddressRow shippingAddress = order.shippingAddressId == null ? null : addressesById.get(order.shippingAddressId);
                String json = toJson(objectMapper, order, lineItems, fulfillmentPackages, customer, shippingAddress);

                upsert.setString(1, SOURCE_SYSTEM);
                upsert.setLong(2, order.id);
                upsert.setString(3, order.amazonOrderId);
                upsert.setString(4, json);
                upsert.addBatch();
            }
            upsert.executeBatch();
        }

        List<Long> orderIds = chunk.stream().map(o -> o.id).toList();
        Map<Long, Long> archiveIdBySourceId = new LinkedHashMap<>();
        String selectIdsSql = "SELECT id, source_sales_order_id FROM sales_order_archive WHERE source_system = ? AND source_sales_order_id IN (%s)"
                .formatted(placeholders(orderIds.size()));
        try (PreparedStatement ps = crmConn.prepareStatement(selectIdsSql)) {
            ps.setString(1, SOURCE_SYSTEM);
            for (int i = 0; i < orderIds.size(); i++) {
                ps.setLong(i + 2, orderIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    archiveIdBySourceId.put(rs.getLong("source_sales_order_id"), rs.getLong("id"));
                }
            }
        }

        // Guarded on non-empty rather than assumed: every id in orderIds was just upserted above,
        // so archiveIds is only empty if that upsert silently affected zero rows -- shouldn't
        // happen, but "IN ()" is a MySQL syntax error, not a harmless no-op, so this chunk would
        // otherwise fail in a way retrying can never fix.
        List<Long> archiveIds = new ArrayList<>(archiveIdBySourceId.values());
        if (!archiveIds.isEmpty()) {
            try (PreparedStatement deleteTracking = crmConn.prepareStatement(
                    "DELETE FROM sales_order_archive_tracking WHERE sales_order_archive_id IN (%s)".formatted(placeholders(archiveIds.size())))) {
                bindLongs(deleteTracking, archiveIds);
                deleteTracking.executeUpdate();
            }
        }

        try (PreparedStatement insertTracking = crmConn.prepareStatement(
                "INSERT INTO sales_order_archive_tracking (sales_order_archive_id, tracking_no) VALUES (?, ?)")) {
            boolean hasAny = false;
            for (SourceSalesOrder order : chunk) {
                Long archiveId = archiveIdBySourceId.get(order.id);
                List<FulfillmentPackageTO> fulfillmentPackages = fulfillmentPackagesByOrderId.getOrDefault(order.id, List.of());
                for (String trackingNo : trackingNumbersOf(fulfillmentPackages)) {
                    insertTracking.setLong(1, archiveId);
                    insertTracking.setString(2, trackingNo);
                    insertTracking.addBatch();
                    hasAny = true;
                }
            }
            if (hasAny) {
                insertTracking.executeBatch();
            }
        }

        crmConn.commit();
    }

    private static String toJson(ObjectMapper objectMapper, SourceSalesOrder order,
                                  List<SalesOrderLineItemTO> lineItems,
                                  List<FulfillmentPackageTO> fulfillmentPackages,
                                  CustomerRow customer, AddressRow shippingAddress) {
        SalesOrderInfoTO to = new SalesOrderInfoTO();
        to.setMerchantOrderId(order.merchantOrderId);
        to.setAmazonOrderId(order.amazonOrderId);
        to.setStatus(decodeOrWarn("status", order.id, order.status, ArchivedOrderStatus::nameForId));
        to.setSalesChannel(decodeOrWarn("salesChannel", order.id, order.salesChannel, SalesOrderArchiveMigration::salesChannelNameForId));
        to.setFulfillmentType(decodeOrWarn("fulfillmentType", order.id, order.fulfillmentType, ArchivedFulfillmentType::nameForId));
        to.setDatePurchased(order.datePurchased);
        to.setDateShipped(order.dateShipped);
        to.setGrandTotalPrice(order.grandTotalPrice);
        to.setShippingFee(order.shippingFee);
        to.setTaxFee(order.taxFee);
        // Buyer of record -- sales_order.customer_id. Left null for an order with no linked
        // customer row, exactly as SalesOrderInfoTO#customer documents. isReviewDisabled and
        // customerOrderCount are deliberately not backfilled: both are live legacy-app state (a
        // review block can be applied or lifted at any time, and the order count grows with
        // every later order), so a value frozen at migration time would be wrong the moment it
        // was written. An archive hit therefore reports "review not disabled, count 0", which
        // the Sales Order page renders as "no review action recorded" rather than as a claim.
        to.setCustomer(toCustomerTO(customer));
        // Ship-to recipient -- sales_order.shipping_address_id (never billing_address_id;
        // SalesOrderInfoTO's recipientName/shipToAddress/shippingAddress are all documented
        // against salesOrder.shippingAddress specifically).
        to.setRecipientName(shippingAddress != null ? shippingAddress.name : null);
        to.setShipToAddress(formatShipToAddress(shippingAddress));
        to.setShippingAddress(toShipToAddressTO(shippingAddress));
        to.setTrackingNumbers(trackingNumbersOf(fulfillmentPackages));
        to.setFulfillmentPackages(fulfillmentPackages);
        to.setLineItems(lineItems);
        try {
            return objectMapper.writeValueAsString(to);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize sales_order.id=" + order.id, e);
        }
    }

    private static CustomerTO toCustomerTO(CustomerRow customer) {
        if (customer == null) {
            return null;
        }
        CustomerTO to = new CustomerTO(customer.name);
        to.setEmail(customer.email);
        return to;
    }

    // Mirrors this app's own address-block formatting for the ticket copy-address feature (see
    // frontend/src/App.jsx#buildAddressLines, itself mirroring legacy-app's copyAddress button):
    // name, address line 1, address line 2 if present, then city/state/zip on one line.
    static String formatShipToAddress(AddressRow address) {
        if (address == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        addIfNotBlank(lines, address.name);
        addIfNotBlank(lines, address.addressLine1);
        addIfNotBlank(lines, address.addressLine2);
        String cityState = joinNonBlank(", ", address.city, address.state);
        String lastLine = joinNonBlank(" ", cityState, address.zipCode);
        addIfNotBlank(lines, lastLine);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static ShipToAddressTO toShipToAddressTO(AddressRow address) {
        if (address == null) {
            return null;
        }
        ShipToAddressTO to = new ShipToAddressTO();
        to.setName(address.name);
        to.setAddressLine1(address.addressLine1);
        to.setAddressLine2(address.addressLine2);
        to.setAddressLine3(address.addressLine3);
        to.setCity(address.city);
        to.setState(address.state);
        to.setZipCode(address.zipCode);
        to.setCountryCode(address.countryCode);
        to.setPhone(address.phone);
        return to;
    }

    static List<String> trackingNumbersOf(List<FulfillmentPackageTO> fulfillmentPackages) {
        List<String> trackingNumbers = new ArrayList<>();
        for (FulfillmentPackageTO fulfillmentPackage : fulfillmentPackages) {
            String trackingNumber = fulfillmentPackage.getTrackingNumber();
            if (trackingNumber != null && !trackingNumber.isBlank()) {
                trackingNumbers.add(trackingNumber);
            }
        }
        return trackingNumbers;
    }

    private static void addIfNotBlank(List<String> lines, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(value);
        }
    }

    private static String joinNonBlank(String delimiter, String... parts) {
        List<String> nonBlank = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                nonBlank.add(part);
            }
        }
        return String.join(delimiter, nonBlank);
    }

    // com.exampleco.core.order.SalesChannel is a real shared dependency (libs/exampleco-core),
    // unlike OrderStatus/FulfillmentType below -- no hand-mirroring needed, just a lookup by id.
    private static String salesChannelNameForId(int id) {
        for (SalesChannel channel : SalesChannel.values()) {
            if (channel.getId() == id) {
                return channel.name();
            }
        }
        throw new IllegalStateException("Unknown SalesChannel id: " + id);
    }

    // See the class javadoc's note on why this tolerates an unmappable id here (status/
    // salesChannel/fulfillmentType) where TicketReasonConverter's equivalent fix only tolerated
    // one specific confirmed-bad column. rawId is passed through separately from decoder so the
    // log line always names the offending code even though the *ArchivedXxx::nameForId method
    // references have already closed over it.
    // Package-private (not private) so SalesOrderArchiveMigrationTest can exercise it directly.
    static String decodeOrWarn(String fieldName, long sourceOrderId, int rawId, IntFunction<String> decoder) {
        try {
            return decoder.apply(rawId);
        } catch (IllegalStateException e) {
            System.err.println("sales_order.id=" + sourceOrderId + ": " + e.getMessage()
                    + " -- storing " + fieldName + " as null and continuing");
            return null;
        }
    }

    private static long findResumePoint(Connection crmConn) throws SQLException {
        try (PreparedStatement ps = crmConn.prepareStatement(
                "SELECT COALESCE(MAX(source_sales_order_id), 0) FROM sales_order_archive WHERE source_system = ?")) {
            ps.setString(1, SOURCE_SYSTEM);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<SourceSalesOrder> fetchChunk(Connection archiveConn, long afterId) throws SQLException {
        String sql = """
                SELECT id, merchant_order_id, amazon_order_id, status, sales_channel, fulfillment_type,
                       date_purchased, date_shipped, grand_total_price, shipping_fee, tax_fee,
                       customer_id, shipping_address_id
                FROM sales_order
                WHERE id > ?
                ORDER BY id ASC
                LIMIT ?
                """;
        List<SourceSalesOrder> result = new ArrayList<>();
        try (PreparedStatement ps = archiveConn.prepareStatement(sql)) {
            ps.setLong(1, afterId);
            ps.setInt(2, CHUNK_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SourceSalesOrder order = new SourceSalesOrder();
                    order.id = rs.getLong("id");
                    order.merchantOrderId = rs.getString("merchant_order_id");
                    order.amazonOrderId = rs.getString("amazon_order_id");
                    order.status = rs.getInt("status");
                    order.salesChannel = rs.getInt("sales_channel");
                    order.fulfillmentType = rs.getInt("fulfillment_type");
                    order.datePurchased = toDate(rs.getTimestamp("date_purchased"));
                    order.dateShipped = toDate(rs.getTimestamp("date_shipped"));
                    order.grandTotalPrice = rs.getDouble("grand_total_price");
                    order.shippingFee = rs.getDouble("shipping_fee");
                    order.taxFee = rs.getDouble("tax_fee");
                    order.customerId = rs.getString("customer_id");
                    order.shippingAddressId = rs.getString("shipping_address_id");
                    result.add(order);
                }
            }
        }
        return result;
    }

    private static Map<Long, List<SalesOrderLineItemTO>> fetchLineItems(Connection archiveConn, List<Long> orderIds) throws SQLException {
        Map<Long, List<SalesOrderLineItemTO>> result = new LinkedHashMap<>();
        if (orderIds.isEmpty()) {
            return result;
        }
        String sql = """
                SELECT sales_order_id, productsku, amazonsku, asin, name, amazon_order_item_code,
                       quantity, unit_price, unit_tax, shipment_date
                FROM sales_order_line_item
                WHERE sales_order_id IN (%s)
                """.formatted(placeholders(orderIds.size()));
        try (PreparedStatement ps = archiveConn.prepareStatement(sql)) {
            bindLongs(ps, orderIds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SalesOrderLineItemTO item = new SalesOrderLineItemTO();
                    item.setProductsku(rs.getString("productsku"));
                    item.setAmazonsku(rs.getString("amazonsku"));
                    item.setAsin(rs.getString("asin"));
                    item.setName(rs.getString("name"));
                    item.setAmazonOrderItemCode(rs.getString("amazon_order_item_code"));
                    item.setQuantity(rs.getInt("quantity"));
                    item.setUnitPrice(rs.getDouble("unit_price"));
                    item.setUnitTax(rs.getDouble("unit_tax"));
                    item.setShipmentDate(toDate(rs.getTimestamp("shipment_date")));
                    result.computeIfAbsent(rs.getLong("sales_order_id"), k -> new ArrayList<>()).add(item);
                }
            }
        }
        return result;
    }

    private static Map<Long, List<FulfillmentPackageTO>> fetchFulfillmentPackages(Connection archiveConn, List<Long> orderIds) throws SQLException {
        Map<Long, List<FulfillmentPackageTO>> result = new LinkedHashMap<>();
        if (orderIds.isEmpty()) {
            return result;
        }
        // sales_order_id sits directly on fulfillment_package -- no join through fulfillment
        // needed, matching legacy-app's own SalesOrderInfoService.groovy
        // (FulfillmentPackage.findAllBySalesOrder). carrier/estimate_arrival_date are read
        // alongside tracking_number now -- the original version only ever selected
        // tracking_number, leaving SalesOrderInfoTO.fulfillmentPackages permanently empty.
        String sql = "SELECT sales_order_id, carrier, tracking_number, estimate_arrival_date FROM fulfillment_package WHERE sales_order_id IN (%s)"
                .formatted(placeholders(orderIds.size()));
        try (PreparedStatement ps = archiveConn.prepareStatement(sql)) {
            bindLongs(ps, orderIds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FulfillmentPackageTO fulfillmentPackage = new FulfillmentPackageTO();
                    fulfillmentPackage.setCarrier(rs.getString("carrier"));
                    fulfillmentPackage.setTrackingNumber(rs.getString("tracking_number"));
                    fulfillmentPackage.setEstimateArrivalDate(toDate(rs.getTimestamp("estimate_arrival_date")));
                    result.computeIfAbsent(rs.getLong("sales_order_id"), k -> new ArrayList<>()).add(fulfillmentPackage);
                }
            }
        }
        return result;
    }

    // customer.id / address.id are VARCHAR(255) on the legacy Amazon schema (confirmed against
    // store1_dev directly), not BIGINT like every other id here -- same as
    // doc/0.2.0/migrate_tickets_to_crm_dev.sql's customer_id/customer_address_id columns.
    private static Map<String, CustomerRow> fetchCustomers(Connection archiveConn, List<String> customerIds) throws SQLException {
        Map<String, CustomerRow> result = new LinkedHashMap<>();
        if (customerIds.isEmpty()) {
            return result;
        }
        String sql = "SELECT id, name, email FROM customer WHERE id IN (%s)".formatted(placeholders(customerIds.size()));
        try (PreparedStatement ps = archiveConn.prepareStatement(sql)) {
            bindStrings(ps, customerIds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CustomerRow row = new CustomerRow();
                    row.name = rs.getString("name");
                    row.email = rs.getString("email");
                    result.put(rs.getString("id"), row);
                }
            }
        }
        return result;
    }

    private static Map<String, AddressRow> fetchAddresses(Connection archiveConn, List<String> addressIds) throws SQLException {
        Map<String, AddressRow> result = new LinkedHashMap<>();
        if (addressIds.isEmpty()) {
            return result;
        }
        String sql = """
                SELECT id, name, address_line1, address_line2, address_line3, city, state,
                       zip_code, country_code, phone
                FROM address
                WHERE id IN (%s)
                """.formatted(placeholders(addressIds.size()));
        try (PreparedStatement ps = archiveConn.prepareStatement(sql)) {
            bindStrings(ps, addressIds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AddressRow row = new AddressRow();
                    row.name = rs.getString("name");
                    row.addressLine1 = rs.getString("address_line1");
                    row.addressLine2 = rs.getString("address_line2");
                    row.addressLine3 = rs.getString("address_line3");
                    row.city = rs.getString("city");
                    row.state = rs.getString("state");
                    row.zipCode = rs.getString("zip_code");
                    row.countryCode = rs.getString("country_code");
                    row.phone = rs.getString("phone");
                    result.put(rs.getString("id"), row);
                }
            }
        }
        return result;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static void bindLongs(PreparedStatement ps, List<Long> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            ps.setLong(i + 1, values.get(i));
        }
    }

    private static void bindStrings(PreparedStatement ps, List<String> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            ps.setString(i + 1, values.get(i));
        }
    }

    private static java.util.Date toDate(Timestamp timestamp) {
        return timestamp == null ? null : new java.util.Date(timestamp.getTime());
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + name);
        }
        return value;
    }

    private static String optionalEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static final class SourceSalesOrder {
        long id;
        String merchantOrderId;
        String amazonOrderId;
        int status;
        int salesChannel;
        int fulfillmentType;
        java.util.Date datePurchased;
        java.util.Date dateShipped;
        double grandTotalPrice;
        double shippingFee;
        double taxFee;
        String customerId;
        String shippingAddressId;
    }

    private static final class CustomerRow {
        String name;
        String email;
    }

    // Package-private (not private) so SalesOrderArchiveMigrationTest can construct one directly.
    static final class AddressRow {
        String name;
        String addressLine1;
        String addressLine2;
        String addressLine3;
        String city;
        String state;
        String zipCode;
        String countryCode;
        String phone;
    }

    /**
     * Mirrored from legacy-app's {@code com.exampleco.legacy.amazon.OrderStatus} (pkg-amazon module) --
     * not a shared dependency like SalesChannel, so this has to be hand-kept in sync, same as
     * ticketEnums.js mirrors legacy-app's TicketStatus.groovy (see CLAUDE.md). Only the Amazon
     * module's OrderStatus applies here -- store1_dev's sales_order is backed by
     * com.exampleco.legacy.domain.amazon.salesOrder.SalesOrder.
     */
    private enum ArchivedOrderStatus {
        Canceled(-1), Unfulfillable(-2), PendingAvailability(-3),
        New(1), Processing(2), Shipped(5), PartiallyShipped(7), UnShipped(8), Pending(9);

        private final int id;

        ArchivedOrderStatus(int id) {
            this.id = id;
        }

        static String nameForId(int id) {
            for (ArchivedOrderStatus status : values()) {
                if (status.id == id) {
                    return status.name();
                }
            }
            throw new IllegalStateException("Unknown OrderStatus id: " + id);
        }
    }

    /**
     * Mirrored from legacy-app's {@code com.exampleco.legacy.core.fulfillment.FulfillmentType}
     * (pkg-legacy-core module) -- same hand-mirroring caveat as ArchivedOrderStatus above.
     */
    private enum ArchivedFulfillmentType {
        Seller(1), FBA(2), MCF(3), DropShp(4), FBW(5);

        private final int id;

        ArchivedFulfillmentType(int id) {
            this.id = id;
        }

        static String nameForId(int id) {
            for (ArchivedFulfillmentType type : values()) {
                if (type.id == id) {
                    return type.name();
                }
            }
            throw new IllegalStateException("Unknown FulfillmentType id: " + id);
        }
    }

    private SalesOrderArchiveMigration() {
    }
}
