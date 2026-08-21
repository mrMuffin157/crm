package com.exampleco.crm.repository.salesorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.exampleco.crm.domain.salesorder.FulfillmentPackage;

public interface FulfillmentPackageRepository extends JpaRepository<FulfillmentPackage, Long> {

    // A tracking number is expected to be unique in practice, but the table's own unique key
    // is (tracking_number, fulfillment_id) — not tracking_number alone — so this returns a
    // List rather than risking an IncorrectResultSizeDataAccessException on an Optional/single
    // result if more than one row happens to share it.
    List<FulfillmentPackage> findByTrackingNumber(String trackingNumber);

    // Backs the order detail view's "Fulfillment" section — batched by
    // SalesOrderController#enrich the same way order lines are.
    List<FulfillmentPackage> findBySalesOrderIdIn(List<Long> salesOrderIds);
}
