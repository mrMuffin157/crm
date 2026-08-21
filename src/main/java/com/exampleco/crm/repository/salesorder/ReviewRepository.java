package com.exampleco.crm.repository.salesorder;

import org.springframework.data.jpa.repository.JpaRepository;
import com.exampleco.crm.domain.salesorder.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {
}
