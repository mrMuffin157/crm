package com.exampleco.crm.domain.salesorder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

// Read-only, minimal mapping — a same-day (sku, date_purchased) sales aggregate. Used to back
// the old local-table "Cost & Profit" breakdown (removed along with CostProfitCalculator when
// the Sales Order page switched to a live legacy-app API lookup, which doesn't return this data).
// Currently unreferenced by any controller — kept because it may still back real migrated data
// in crm_dev; see README's Known issues.
@Entity
@Table(name = "report_product_daily_sales")
@Getter
@Setter
public class ReportProductDailySales {

    @Id
    private Long id;

    private String sku;

    @Column(name = "date_purchased")
    private LocalDate datePurchased;

    private Integer salescount;

    @Column(name = "daily_fba_storage_fee")
    private Double dailyFbaStorageFee;

    @Column(name = "return_amount")
    private Double returnAmount;

    @Column(name = "ad_cost")
    private Double adCost;
}
