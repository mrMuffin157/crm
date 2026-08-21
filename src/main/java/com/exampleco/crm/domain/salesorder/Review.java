package com.exampleco.crm.domain.salesorder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// Read-only in this app. Used to back the old local-table Sales Orders order detail view's
// "Reviews" section, joined via SalesOrderReviewAssignment (a per (order, review) junction row,
// not review_asin/review_author as the table names might suggest); removed when the Sales
// Order page switched to a live legacy-app API lookup, which returns no review data. "Author" in
// the real UI is authorInfoSnapShot — a point-in-time snapshot text field, not a live join to
// review_author. Currently unreferenced by any controller — kept because it may still back
// real migrated data in crm_dev; see README's Known issues.
@Entity
@Table(name = "review")
@Getter
@Setter
public class Review {

    @Id
    private Long id;

    @Column(name = "amazon_review_id")
    private String amazonReviewId;

    private String title;

    @Column(name = "review_date")
    private LocalDateTime reviewDate;

    private Integer score;

    @Lob
    private String content;

    @Lob
    @Column(name = "author_info_snap_shot")
    private String authorInfoSnapShot;
}
