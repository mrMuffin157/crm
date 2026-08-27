package com.exampleco.crm.domain.salesorder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// One row per archived sales order, backfilled by SalesOrderArchiveMigration from a legacy
// per-storefront archive database that legacy-app's live lookup (LegacyApiClient) can no longer
// reach -- it only ever queries legacy-app's 4 current storefront hosts. sourceSystem/
// sourceSalesOrderId identify which archive DB and which original row this came from; see
// doc/0.5.0/create_sales_order_archive.sql for the full design. salesOrderInfo holds the
// complete SalesOrderInfoTO JSON (everything except trackingNumbers -- see
// SalesOrderArchiveTracking) so a hit here deserializes into the exact same DTO the live lookup
// already returns.
@Entity
@Table(name = "sales_order_archive")
@Getter
@Setter
public class SalesOrderArchive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_system", nullable = false)
    private String sourceSystem;

    @Column(name = "source_sales_order_id", nullable = false)
    private Long sourceSalesOrderId;

    @Column(name = "amazon_order_id")
    private String amazonOrderId;

    @Column(name = "sales_order_info", nullable = false, columnDefinition = "json")
    private String salesOrderInfo;

    @Column(name = "date_created", nullable = false)
    private LocalDateTime dateCreated;

    // Read-only projection of sales_order_info's own $.customer.email, so the archive can be
    // queried by buyer and not only by order -- see
    // doc/0.7.0/add_sales_order_archive_buyer_email.sql. This is a VIRTUAL generated column in
    // MySQL, deliberately not a stored copy: it is derived by the database from the JSON on
    // every read, so it can never drift from it and no writer here (nor
    // tools.SalesOrderArchiveMigrationV2) sets it. insertable/updatable=false is what keeps
    // Hibernate from trying -- MySQL rejects any write to a generated column.
    // Null for an archived order with no linked customer.
    @Column(name = "buyer_email", insertable = false, updatable = false)
    private String buyerEmail;
}
