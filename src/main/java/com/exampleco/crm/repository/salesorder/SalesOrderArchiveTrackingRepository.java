package com.exampleco.crm.repository.salesorder;

import com.exampleco.crm.domain.salesorder.SalesOrderArchiveTracking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SalesOrderArchiveTrackingRepository extends JpaRepository<SalesOrderArchiveTracking, Long> {
    Optional<SalesOrderArchiveTracking> findFirstByTrackingNo(String trackingNo);
}
