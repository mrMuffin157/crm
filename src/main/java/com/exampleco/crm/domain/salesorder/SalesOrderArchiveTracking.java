package com.exampleco.crm.domain.salesorder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

// One row per tracking number per archived order -- an order can have several (some archived
// orders have 150-350), so this can't be a column on SalesOrderArchive; see
// doc/0.5.0/create_sales_order_archive.sql for the full reasoning. Looked up by trackingNo to
// resolve which SalesOrderArchive row a tracking-number search matches.
@Entity
@Table(name = "sales_order_archive_tracking")
@Getter
@Setter
public class SalesOrderArchiveTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sales_order_archive_id", nullable = false)
    private Long salesOrderArchiveId;

    @Column(name = "tracking_no", nullable = false)
    private String trackingNo;
}
