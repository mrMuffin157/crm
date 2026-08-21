package com.exampleco.crm.domain.salesorder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

// Read-only in this app — the (order, review) junction table that used to back the old
// local-table Sales Orders order detail view's "Reviews" section (review_asin/review_author
// are NOT part of this join path), removed when the Sales Order page switched to a live
// legacy-app API lookup. Currently unreferenced by any controller — kept because it may still back
// real migrated data in crm_dev; see README's Known issues.
@Entity
@Table(name = "sales_order_review_assignment")
@Getter
@Setter
public class SalesOrderReviewAssignment {

    @Id
    private Long id;

    @Column(name = "sales_order_id")
    private Long salesOrderId;

    @Column(name = "review_id")
    private Long reviewId;
}
