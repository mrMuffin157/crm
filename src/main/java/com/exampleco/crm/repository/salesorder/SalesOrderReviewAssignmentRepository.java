package com.exampleco.crm.repository.salesorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.exampleco.crm.domain.salesorder.SalesOrderReviewAssignment;

public interface SalesOrderReviewAssignmentRepository extends JpaRepository<SalesOrderReviewAssignment, Long> {
    List<SalesOrderReviewAssignment> findBySalesOrderId(Long salesOrderId);
}
