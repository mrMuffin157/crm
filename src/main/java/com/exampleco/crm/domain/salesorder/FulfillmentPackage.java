package com.exampleco.crm.domain.salesorder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// Read-only, minimal mapping. Used to back the old local-table Sales Orders flow's "Tracking
// Number" search shortcut and the order detail view's "Fulfillment" section, removed when the
// Sales Order page switched to a live legacy-app API lookup (which returns bare tracking number
// strings, no carrier/ETA, straight from legacy-app itself). Currently unreferenced by any
// controller — kept because it may still back real migrated data in crm_dev; see README's
// Known issues.
@Entity
@Table(name = "fulfillment_package")
@Getter
@Setter
public class FulfillmentPackage {

    @Id
    private Long id;

    private String carrier;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "estimate_arrival_date")
    private LocalDateTime estimateArrivalDate;

    @Column(name = "sales_order_id")
    private Long salesOrderId;
}
