package com.exampleco.crm.repository.salesorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import com.exampleco.crm.domain.salesorder.ReportProductDailySales;

public interface ReportProductDailySalesRepository extends JpaRepository<ReportProductDailySales, Long> {

    // Batched per order: one date (the order's purchase date) against all of that order's
    // distinct SKUs at once, rather than one query per line item.
    List<ReportProductDailySales> findByDatePurchasedAndSkuIn(LocalDate datePurchased, List<String> skus);
}
