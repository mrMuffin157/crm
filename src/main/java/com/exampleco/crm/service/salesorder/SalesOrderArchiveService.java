package com.exampleco.crm.service.salesorder;

import com.exampleco.core.dto.order.SalesOrderInfoTO;
import com.exampleco.crm.domain.salesorder.SalesOrderArchive;
import com.exampleco.crm.repository.salesorder.SalesOrderArchiveRepository;
import com.exampleco.crm.repository.salesorder.SalesOrderArchiveTrackingRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

// Local fallback for orders that have aged out of legacy-app's 4 live storefront hosts (see
// LegacyApiClient) into a separate archive database, backfilled into sales_order_archive /
// sales_order_archive_tracking by SalesOrderArchiveMigration -- see
// doc/0.5.0/create_sales_order_archive.sql for the full design. Blocking JPA calls, so wrapped
// per this app's reactive-shell-over-blocking-JPA rule even though these are simple lookups.
@Component
public class SalesOrderArchiveService {

    // A single buyer's archived orders, as rendered by the Customer View. Every real buyer is
    // far under this; the cap is here so one pathological reseller account can't turn a page
    // open into an unbounded response. addArchivedOrderCount() below still reports the buyer's
    // TRUE total even when this cap trims the list, so a capped view understates what it lists,
    // never what it claims.
    private static final Limit MAX_ARCHIVED_ORDERS_PER_BUYER = Limit.of(200);

    private final SalesOrderArchiveRepository salesOrderArchiveRepository;
    private final SalesOrderArchiveTrackingRepository salesOrderArchiveTrackingRepository;
    private final ObjectMapper objectMapper;

    public SalesOrderArchiveService(SalesOrderArchiveRepository salesOrderArchiveRepository,
                                     SalesOrderArchiveTrackingRepository salesOrderArchiveTrackingRepository,
                                     ObjectMapper objectMapper) {
        this.salesOrderArchiveRepository = salesOrderArchiveRepository;
        this.salesOrderArchiveTrackingRepository = salesOrderArchiveTrackingRepository;
        this.objectMapper = objectMapper;
    }

    // Exactly one of amazonOrderId/trackingNumber is expected -- mirrors
    // LegacyApiClient#lookup's own contract, enforced by the caller (SalesOrderController).
    public Mono<SalesOrderInfoTO> lookup(String amazonOrderId, String trackingNumber) {
        return Mono.fromCallable(() -> findArchive(amazonOrderId, trackingNumber))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(found -> found.map(archive -> Mono.just(toDto(archive))).orElseGet(Mono::empty));
    }

    // Every archived order one buyer has placed, newest first. The by-email counterpart of
    // lookup() above, and the archive half of the Customer View: legacy-app's own by-email API only
    // knows the buyer's LIVE orders, so a buyer whose every order has aged out reads as a buyer
    // legacy-app has never seen. Empty (not an empty list) when the archive doesn't know the email
    // either, so the caller can fall through to its own not-found the same way lookup() does.
    public Mono<List<SalesOrderInfoTO>> lookupByEmail(String email) {
        if (email == null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> findArchivedOrdersByEmail(email))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(orders -> !orders.isEmpty());
    }

    // Adds this buyer's archived order count on top of whatever the order already carries.
    //
    // customerOrderCount is deliberately never stored in the archive -- a count frozen at
    // migration time would be wrong the moment it was written (see
    // doc/0.7.0/backfill_sales_order_archive_events_and_customer_id.sql), and because the field
    // is a primitive int, "not backfilled" deserializes as a confident-looking 0. Counting here
    // instead keeps that rule and still answers correctly, because the count is recomputed on
    // every read.
    //
    // Additive rather than assigned, so one method serves both sources: an archived order
    // arrives carrying 0, so it ends up with exactly its archived count, while a live order
    // arrives carrying legacy-app's own live count and ends up with the buyer's true total across
    // both. That sum is only sound because the live and archived order sets are disjoint --
    // verified, with the query to re-check it, at the bottom of
    // doc/0.7.0/add_sales_order_archive_buyer_email.sql.
    public Mono<SalesOrderInfoTO> addArchivedOrderCount(SalesOrderInfoTO order) {
        String email = buyerEmailOf(order);
        if (email == null) {
            return Mono.just(order);
        }
        return Mono.fromCallable(() -> salesOrderArchiveRepository.countByBuyerEmail(email))
                .subscribeOn(Schedulers.boundedElastic())
                .map(archived -> withCustomerOrderCount(order, order.getCustomerOrderCount() + archived.intValue()));
    }

    private List<SalesOrderInfoTO> findArchivedOrdersByEmail(String email) {
        List<SalesOrderArchive> archived = salesOrderArchiveRepository
                .findByBuyerEmailOrderBySourceSalesOrderIdDesc(email, MAX_ARCHIVED_ORDERS_PER_BUYER);
        if (archived.isEmpty()) {
            return List.of();
        }
        // One count for the whole list rather than one per order: they all share the buyer this
        // was looked up by, so they all share the answer. Can't be replaced by archived.size(),
        // which the cap above may have trimmed.
        int archivedOrderCount = (int) salesOrderArchiveRepository.countByBuyerEmail(email);
        return archived.stream()
                .map(this::toDto)
                .map(order -> withCustomerOrderCount(order, order.getCustomerOrderCount() + archivedOrderCount))
                .toList();
    }

    private SalesOrderInfoTO withCustomerOrderCount(SalesOrderInfoTO order, int customerOrderCount) {
        order.setCustomerOrderCount(customerOrderCount);
        return order;
    }

    private String buyerEmailOf(SalesOrderInfoTO order) {
        if (order.getCustomer() == null) {
            return null;
        }
        String email = order.getCustomer().getEmail();
        return (email == null || email.isBlank()) ? null : email;
    }

    private Optional<SalesOrderArchive> findArchive(String amazonOrderId, String trackingNumber) {
        if (amazonOrderId != null) {
            return salesOrderArchiveRepository.findFirstByAmazonOrderId(amazonOrderId);
        }
        return salesOrderArchiveTrackingRepository.findFirstByTrackingNo(trackingNumber)
                .flatMap(tracking -> salesOrderArchiveRepository.findById(tracking.getSalesOrderArchiveId()));
    }

    private SalesOrderInfoTO toDto(SalesOrderArchive archive) {
        try {
            return objectMapper.readValue(archive.getSalesOrderInfo(), SalesOrderInfoTO.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Corrupt sales_order_info JSON for sales_order_archive.id=" + archive.getId(), e);
        }
    }
}
