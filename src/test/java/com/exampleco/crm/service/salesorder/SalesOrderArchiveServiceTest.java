package com.exampleco.crm.service.salesorder;

import com.exampleco.core.dto.customer.CustomerTO;
import com.exampleco.core.dto.order.SalesOrderInfoTO;
import com.exampleco.crm.domain.salesorder.SalesOrderArchive;
import com.exampleco.crm.repository.salesorder.SalesOrderArchiveRepository;
import com.exampleco.crm.repository.salesorder.SalesOrderArchiveTrackingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The archive's buyer-keyed reads. What's worth pinning here is customerOrderCount: the archive
// deliberately never stores it (a count frozen at migration time would be wrong the moment it
// was written -- see doc/0.7.0/backfill_sales_order_archive_events_and_customer_id.sql), and
// because SalesOrderInfoTO declares it as a primitive int, "not backfilled" deserializes as a
// confident-looking 0 rather than an absent value. That is exactly how an archived order came to
// render "0 orders" next to a buyer legacy-app's own page counted 5 for.
//
// Plain Mockito with a real JsonMapper over real archived JSON, no Spring context and no
// datasource, per this tree's convention.
class SalesOrderArchiveServiceTest {

    private final SalesOrderArchiveRepository archiveRepository = mock(SalesOrderArchiveRepository.class);
    private final SalesOrderArchiveTrackingRepository trackingRepository =
            mock(SalesOrderArchiveTrackingRepository.class);
    private final SalesOrderArchiveService service = new SalesOrderArchiveService(
            archiveRepository, trackingRepository, JsonMapper.builder().build());

    private static final String BUYER_EMAIL = "buyer1@example.com";

    // Shaped exactly like a real migrated row: a customer, and no customerOrderCount key at all.
    private static SalesOrderArchive archivedOrder(long id, String amazonOrderId) {
        SalesOrderArchive archive = new SalesOrderArchive();
        archive.setId(id);
        archive.setSourceSystem("store1_dev");
        archive.setSourceSalesOrderId(id);
        archive.setAmazonOrderId(amazonOrderId);
        archive.setSalesOrderInfo("""
                {
                  "amazonOrderId": "%s",
                  "status": "Shipped",
                  "salesChannel": "AmazonBrandD",
                  "customer": {
                    "id": "00000000000000000000000000000001",
                    "name": "John Smith",
                    "email": "%s",
                    "reviewDisabled": false
                  }
                }
                """.formatted(amazonOrderId, BUYER_EMAIL));
        return archive;
    }

    private static SalesOrderInfoTO liveOrder(String email, int liveCount) {
        SalesOrderInfoTO order = new SalesOrderInfoTO();
        order.setAmazonOrderId("111-0000000-0000002");
        order.setCustomerOrderCount(liveCount);
        if (email != null) {
            CustomerTO customer = new CustomerTO();
            customer.setEmail(email);
            order.setCustomer(customer);
        }
        return order;
    }

    // The reported bug, in one assertion: an archived order carries a literal 0 in its JSON, so
    // the count has to come from counting the archive at read time.
    @Test
    void countsAnArchivedOrdersBuyerInsteadOfTrustingTheStoredZero() {
        when(archiveRepository.findFirstByAmazonOrderId("111-0000000-0000003"))
                .thenReturn(java.util.Optional.of(archivedOrder(1164723L, "111-0000000-0000003")));
        when(archiveRepository.countByBuyerEmail(BUYER_EMAIL)).thenReturn(5L);

        SalesOrderInfoTO order = service.lookup("111-0000000-0000003", null).block();
        assertEquals(0, order.getCustomerOrderCount(), "the stored JSON really does say 0");

        assertEquals(5, service.addArchivedOrderCount(order).block().getCustomerOrderCount());
    }

    // Additive, not assigned, so one method serves both sources. A live order arrives carrying
    // legacy-app's own count of the orders it can still see; the archived ones are the orders it
    // can't. The sum is only sound because the two sets are disjoint -- see the verification at
    // the bottom of doc/0.7.0/add_sales_order_archive_buyer_email.sql.
    @Test
    void addsArchivedOrdersOnTopOfTheLiveCountRatherThanReplacingIt() {
        when(archiveRepository.countByBuyerEmail(BUYER_EMAIL)).thenReturn(5L);

        SalesOrderInfoTO enriched = service.addArchivedOrderCount(liveOrder(BUYER_EMAIL, 2)).block();

        assertEquals(7, enriched.getCustomerOrderCount());
    }

    // sales_order_archive is very large and only buyer_email is indexed, so a null email must
    // never reach the repository -- that is a full scan of the whole archive per page open.
    @Test
    void neverCountsTheArchiveForAnOrderWithNoLinkedBuyer() {
        SalesOrderInfoTO enriched = service.addArchivedOrderCount(liveOrder(null, 0)).block();

        assertEquals(0, enriched.getCustomerOrderCount());
        verify(archiveRepository, never()).countByBuyerEmail(anyString());
    }

    @Test
    void listsABuyersArchivedOrdersNewestFirst() {
        when(archiveRepository.findByBuyerEmailOrderBySourceSalesOrderIdDesc(eq(BUYER_EMAIL), any(Limit.class)))
                .thenReturn(List.of(archivedOrder(1164723L, "111-0000000-0000003"),
                        archivedOrder(998001L, "114-0000000-0000001")));
        when(archiveRepository.countByBuyerEmail(BUYER_EMAIL)).thenReturn(2L);

        List<SalesOrderInfoTO> orders = service.lookupByEmail(BUYER_EMAIL).block();

        assertEquals(2, orders.size());
        assertEquals("111-0000000-0000003", orders.get(0).getAmazonOrderId());
        assertEquals("114-0000000-0000001", orders.get(1).getAmazonOrderId());
    }

    // The per-buyer cap trims what is LISTED, never what is CLAIMED: every order in a trimmed
    // list still reports the buyer's true total, so the Customer View can't quietly understate
    // how many orders someone has placed.
    @Test
    void reportsTheTrueTotalEvenWhenTheListedOrdersAreCapped() {
        when(archiveRepository.findByBuyerEmailOrderBySourceSalesOrderIdDesc(eq(BUYER_EMAIL), any(Limit.class)))
                .thenReturn(List.of(archivedOrder(1164723L, "111-0000000-0000003")));
        when(archiveRepository.countByBuyerEmail(BUYER_EMAIL)).thenReturn(4321L);

        List<SalesOrderInfoTO> orders = service.lookupByEmail(BUYER_EMAIL).block();

        assertEquals(1, orders.size());
        assertEquals(4321, orders.get(0).getCustomerOrderCount());
    }

    // Empty, not an empty list, so SalesOrderController#salesOrdersByEmail's switchIfEmpty chain
    // falls through to its own 404 the way it does for a buyer neither source knows.
    @Test
    void reportsABuyerTheArchiveDoesNotKnowAsEmptyRatherThanAnEmptyList() {
        when(archiveRepository.findByBuyerEmailOrderBySourceSalesOrderIdDesc(eq("nobody@example.com"), any(Limit.class)))
                .thenReturn(List.of());

        assertNull(service.lookupByEmail("nobody@example.com").block());
        verify(archiveRepository, never()).countByBuyerEmail("nobody@example.com");
    }
}
