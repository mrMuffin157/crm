package com.exampleco.crm.domain.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.Audited;

import java.time.LocalDateTime;

// Read-write now (was read-only): still backs the ticket report CSV export and the ticket detail
// view's "Line Items" list, but TicketController's Create/Edit ticket form also writes rows
// here directly for Replacement/ManualHandling tickets (see TicketController#saveLineItems).
// @Audited: tracked into ticket_line_item_aud, correlated back to its ticket via
// ticketId — see TicketHistoryService. persistLineItems() reconciles by sku against what's
// already on the ticket, so a row whose fields are unchanged keeps its id and generates no
// audit event at all — only a genuinely added/changed/removed row does, and a changed row's
// audit event reflects just the fields that actually changed.
@Entity
@Table(name = "ticket_line_item")
@Getter
@Setter
@Audited
public class TicketLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // NOT NULL on the real table with no DB-side default, same reasoning as Ticket.sourceSystem.
    // Always matches the owning ticket's own sourceSystem (the caller-chosen channel on create,
    // ; the existing ticket's channel on edit) — only ever set on a brand-new row in
    // persistLineItems(), never touched again on an existing row, since a ticket's sourceSystem
    // doesn't change after creation.
    @Column(name = "source_system")
    private String sourceSystem;

    @Column(name = "ticket_id")
    private Long ticketId;

    private String sku;

    // The seller SKU as listed on the sales channel (Amazon), distinct from the internal `sku`
    // above — legacy-app's SalesOrderService.createReplacementOrders/FbaOutboundApiService key off
    // this, not `sku`, to submit an MCF order. CRM has no channel/inventory lookup of its own
    // (see 's resolveLineItems), so this is populate-later: today it only round-trips
    // whatever value a row already had (migrated data, or a future resolveLineItems response) —
    // persistLineItems() re-resolves it fresh against legacy-app on every save regardless of whether
    // anything else on the row changed (see that method's own comment), so a resolution that
    // comes back different from what's stored still overwrites it even on an otherwise-unchanged
    // row.
    @Column(name = "channelsku")
    private String channelSku;

    private String name;

    // The channel (Amazon) catalog's own display name for this line's channelSku, as of the
    // most recent save — distinct from `name` above, which is either the human-typed
    // ManualHandling description or legacy-app's Replacement product name (see
    // TicketController#persistLineItems). Feeds the "Item Name" line of the buyer-facing
    // replacement-shipped mail (ReplacementShippedMailService); null on every row saved before
    // this column existed, and omitted from that mail rather than rendered as the literal
    // "null" — see that service's own comment.
    @Column(name = "channel_product_name")
    private String channelProductName;

    private Double quantity;

    // NOT NULL on the real table with no DB-side default. Was always 0 while Refund was
    // excluded from the create/edit Action dropdown (see ticketEnums.js's history); 
    // unblocked Refund and added the sales-order line-item picker (TicketFormFields'
    // RefundItemRows, backed by LegacyApiClient#salesOrderLines), so a Refund row now carries
    // whatever amount the picker derived/the user entered. Still always 0 for every
    // Replacement/ManualHandling row — saveLineItems() falls back to 0 for a null
    // LineItemInput#refundAmount.
    @Column(name = "refund_amount")
    private Double refundAmount;

    @Column(name = "date_created")
    private LocalDateTime dateCreated;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;
}
