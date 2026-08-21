package com.exampleco.crm.domain.ticket;

import com.exampleco.core.dto.ticket.RefundType;
import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.core.dto.ticket.TicketReason;
import com.exampleco.core.dto.ticket.TicketStatus;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.Audited;

import java.time.LocalDateTime;
import java.util.List;
import com.exampleco.crm.dto.ticket.AddressDetail;
import com.exampleco.crm.dto.ticket.CommentSummary;
import com.exampleco.crm.dto.ticket.CustomerDetail;
import com.exampleco.crm.dto.ticket.HandlerDetail;
import com.exampleco.crm.dto.ticket.LineItemSummary;

// Audit follow-up (revised §2, "no raw user ids"): handlerId is @JsonIgnore'd below
// rather than removed outright -- it stays a real JPA column (still how handler_id is written
// and queried) and Java-level getter/setter (still used internally, e.g. TicketRepository's own
// filter-by-handler search), it's just never serialized into an API response. Every response
// exposes the handler as HandlerDetail (see #handler below) instead, keyed by username -- the
// only identifier that means the same thing on both sides of the CRM/legacy-app boundary.
// @Audited records every INSERT/UPDATE to this table's own columns into ticket_aud
// via Hibernate Envers -- see TicketHistoryService for how that becomes the ticket detail
// view's history tab. @Transient fields below are never columns, so Envers never sees them.
@Entity
@Table(name = "ticket")
@Getter
@Setter
@Audited
public class Ticket {

    @Id
    private Long id;

    // Nullable: only set on rows the doc/0.2.0 migration inserted (the original legacy-app ticket
    // id in whichever of the 4 source schemas it came from) — see that script's own header
    // comment. Null for every ticket created directly in CRM since. Needed for the M2M API's
    // GET /api/tickets/by-legacy, which is how legacy-app remaps an id it already
    // knows about into this table's own auto-generated id.
    @Column(name = "legacy_id")
    private Long legacyId;

    // NOT NULL on the real table with no DB-side default — the brand/tenant tag carried over
    // from the 4-storefront migration (see doc/0.2.0/migrate_tickets_to_crm_dev.sql). Only
    // ever set on brand-new tickets (TicketController#createTicket); existing tickets keep
    // whichever legacy storefront they actually migrated from.
    @Column(name = "source_system")
    private String sourceSystem;

    @Convert(converter = TicketActionConverter.class)
    private TicketAction action;

    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "date_created")
    private LocalDateTime dateCreated;

    @Lob
    private String description;

    private String email;

    @JsonIgnore
    @Column(name = "handler_id")
    private Long handlerId;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    @Convert(converter = TicketStatusConverter.class)
    private TicketStatus status;

    @Column(name = "customer_address_id")
    private String customerAddressId;

    @Column(name = "order_number")
    private String orderNumber;

    @Convert(converter = TicketReasonConverter.class)
    private TicketReason reason;

    @Column(name = "refund_type")
    @Convert(converter = RefundTypeConverter.class)
    private RefundType refundType;

    @Column(name = "replacement_order_number")
    private String replacementOrderNumber;

    @Column(name = "tracking_number")
    private String trackingNumber;

    private Boolean starred;

    @Lob
    @Column(name = "search_text")
    private String searchText;

    @Column(name = "out_of_warrant")
    private Boolean outOfWarrant;

    // Not columns on this table — populated after the fact by TicketController#enrich via
    // batched lookups (customer_address_id -> address.state, handler_id -> s2user.username,
    // ticket_line_item/ticket_comment joined by ticket id) so the list/search queries above
    // stay simple single-table queries instead of row-multiplying joins.
    @Transient
    private String state;

    // Full address detail (name/line1/line2/city/state/zip/country/phone), for the edit
    // form's Customer Address block to prefill from — Ticket.state alone (used by the
    // compact list column above) isn't enough for that.
    @Transient
    private AddressDetail address;

    @Transient
    private String handlerUsername;

    // {username, email, name} — the M2M API's include=handler shape (revised §2).
    // handlerUsername above is kept alongside this (not replaced by it) since the existing
    // CRM-only list/table columns already read that flat field; this is additive, not a rename.
    @Transient
    private HandlerDetail handler;

    @Transient
    private List<LineItemSummary> lineItems;

    // Sum of lineItems[].refundAmount — computed by TicketController#enrich in the
    // same batched lineItems pass rather than a separate query, mirroring how lastException
    // below is derived from the comments batch instead of its own lookup. 0 for a ticket with
    // no Refund rows (every Replacement/ManualHandling row's refundAmount is itself always 0 —
    // see TicketLineItem#refundAmount).
    @Transient
    private Double totalRefundAmount;

    @Transient
    private List<CommentSummary> comments;

    // Newest comment whose text contains "[Exception]", if any — mirrors legacy-app's
    // Ticket.groovy#getLastException(). Computed by TicketController#enrich from the same
    // comments batch above rather than a separate query, since requires it be
    // derivable from every ticket payload the M2M API returns.
    @Transient
    private String lastException;

    // Live customer record (id/name/email), populated only when the "customer" include is
    // requested — distinct from customerName/email above, which are a
    // ticket-creation-time snapshot.
    @Transient
    private CustomerDetail customer;

    // Set by TicketCloseService#close so ReplacementShippedMailService can
    // include it in the buyer-facing replacement-shipped mail — never persisted (no
    // ticket.carrier column: the value is only needed to render one buyer email). Manual close
    // derives it from the tracking number's "1Z" prefix; the close-ticket CSV import passes its
    // own Carrier - Name column verbatim instead, since the 1Z heuristic can't produce "FedEx"
    // at all.
    @Transient
    private String carrier;
}
