package com.exampleco.crm.repository.salesorder;

import com.exampleco.crm.domain.salesorder.SalesOrderArchive;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalesOrderArchiveRepository extends JpaRepository<SalesOrderArchive, Long> {

    Optional<SalesOrderArchive> findFirstByAmazonOrderId(String amazonOrderId);

    // Both buyer-keyed reads below are served by idx_buyer_email_source_sales_order_id (see
    // doc/0.7.0/add_sales_order_archive_buyer_email.sql). buyer_email is a generated column, so
    // there is nothing to keep in sync -- but this table is very large, so neither of these may
    // ever be called with a null email: that would be an unindexed scan of the whole archive.
    // SalesOrderArchiveService is what enforces it.
    long countByBuyerEmail(String buyerEmail);

    // Newest first, matching the order legacy-app's own by-email API returns and the Customer View
    // renders. source_sales_order_id ascends with time on the archive host, so it stands in for
    // purchase order -- and being the index's second column, it sorts without a filesort.
    List<SalesOrderArchive> findByBuyerEmailOrderBySourceSalesOrderIdDesc(String buyerEmail, Limit limit);
}
