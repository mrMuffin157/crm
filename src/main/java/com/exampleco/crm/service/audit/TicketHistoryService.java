package com.exampleco.crm.service.audit;

import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.hibernate.envers.query.criteria.AuditCriterion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import com.exampleco.crm.domain.audit.CrmRevisionEntity;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.domain.ticket.TicketComment;
import com.exampleco.crm.domain.ticket.TicketLineItem;
import com.exampleco.crm.dto.audit.TicketHistoryEntry;

// Envers stores one full entity snapshot per revision, not a property-level diff -- this service
// is what turns that into the "who changed which property from what to what, and when" rows
// GET /api/tickets/{id}/history returns, by comparing each entity id's own consecutive
// revisions. Covers Ticket itself plus its two child tables (TicketComment/TicketLineItem),
// correlated back to the ticket via their own ticketId property rather than by first collecting
// child ids, since AuditQuery can filter directly on a non-id property across every revision of
// an entity type.
@Service
public class TicketHistoryService {

    private final EntityManager entityManager;

    public TicketHistoryService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // open-in-view=false (see application.properties) means the injected EntityManager has no
    // persistence context at all outside an active transaction -- Envers' AuditReader makes
    // several separate calls into it (createQuery/add/addOrder/getResultList per entity type),
    // and without @Transactional here each of those would open and immediately close its own
    // context, so the second call finds it already closed.
    @Transactional(readOnly = true)
    public List<TicketHistoryEntry> getHistory(Long ticketId) {
        AuditReader reader = AuditReaderFactory.get(entityManager);
        List<TicketHistoryEntry> entries = new ArrayList<>();
        entries.addAll(diffEntity(reader, Ticket.class, "Ticket", AuditEntity.id().eq(ticketId),
                Ticket::getId, TICKET_FIELDS));
        entries.addAll(diffEntity(reader, TicketComment.class, "TicketComment",
                AuditEntity.property("ticketId").eq(ticketId), TicketComment::getId, COMMENT_FIELDS));
        entries.addAll(diffEntity(reader, TicketLineItem.class, "TicketLineItem",
                AuditEntity.property("ticketId").eq(ticketId), TicketLineItem::getId, LINE_ITEM_FIELDS));
        entries.sort(Comparator.comparing(TicketHistoryEntry::dateCreated).reversed());
        return entries;
    }

    @SuppressWarnings("unchecked")
    private <T> List<TicketHistoryEntry> diffEntity(AuditReader reader, Class<T> entityClass, String className,
                                                      AuditCriterion criterion, Function<T, Long> idExtractor,
                                                      Map<String, Function<T, Object>> fields) {
        List<Object[]> rows = (List<Object[]>) (List<?>) reader.createQuery()
                .forRevisionsOfEntity(entityClass, false, true)
                .add(criterion)
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        Map<Long, T> lastStateById = new HashMap<>();
        List<TicketHistoryEntry> entries = new ArrayList<>();
        for (Object[] row : rows) {
            T entity = (T) row[0];
            CrmRevisionEntity revision = (CrmRevisionEntity) row[1];
            RevisionType revisionType = (RevisionType) row[2];
            Long entityId = entity != null ? idExtractor.apply(entity) : null;
            if (entityId == null) {
                continue;
            }
            boolean hasPriorRevision = lastStateById.containsKey(entityId);
            T previous = lastStateById.get(entityId);
            LocalDateTime revisionDate = toLocalDateTime(revision.getTimestamp());
            String event = eventName(revisionType);
            // Envers only started capturing this row's history at the audit rollout, with
            // no backfilled baseline snapshot for rows that already existed -- so a pre-existing
            // row's first captured revision can be a MOD with nothing to diff against. Treating
            // that as "every field changed from blank" would be fabricated, not observed, so skip
            // the diff for that one revision; a real ADD (row created after the rollout) still
            // shows every field, since blank-to-value is exactly what happened there.
            if (hasPriorRevision || revisionType == RevisionType.ADD) {
                for (Map.Entry<String, Function<T, Object>> field : fields.entrySet()) {
                    Object oldValue = previous != null ? field.getValue().apply(previous) : null;
                    Object newValue = entity != null ? field.getValue().apply(entity) : null;
                    if (!Objects.equals(oldValue, newValue)) {
                        entries.add(new TicketHistoryEntry(revision.getUsername(), className, entityId,
                                field.getKey(), event, str(oldValue), str(newValue), revisionDate));
                    }
                }
            }
            lastStateById.put(entityId, entity);
        }
        return entries;
    }

    private static String eventName(RevisionType type) {
        return switch (type) {
            case ADD -> "INSERT";
            case MOD -> "UPDATE";
            case DEL -> "DELETE";
        };
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    @SafeVarargs
    private static <K, V> Map<K, V> linked(Map.Entry<K, V>... entries) {
        Map<K, V> map = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    private static final Map<String, Function<Ticket, Object>> TICKET_FIELDS = linked(
            Map.entry("legacyId", Ticket::getLegacyId),
            Map.entry("sourceSystem", Ticket::getSourceSystem),
            Map.entry("action", Ticket::getAction),
            Map.entry("customerId", Ticket::getCustomerId),
            Map.entry("customerName", Ticket::getCustomerName),
            Map.entry("dateCreated", Ticket::getDateCreated),
            Map.entry("description", Ticket::getDescription),
            Map.entry("email", Ticket::getEmail),
            Map.entry("handlerId", Ticket::getHandlerId),
            Map.entry("lastUpdated", Ticket::getLastUpdated),
            Map.entry("status", Ticket::getStatus),
            Map.entry("customerAddressId", Ticket::getCustomerAddressId),
            Map.entry("orderNumber", Ticket::getOrderNumber),
            Map.entry("reason", Ticket::getReason),
            Map.entry("refundType", Ticket::getRefundType),
            Map.entry("replacementOrderNumber", Ticket::getReplacementOrderNumber),
            Map.entry("trackingNumber", Ticket::getTrackingNumber),
            Map.entry("starred", Ticket::getStarred),
            Map.entry("searchText", Ticket::getSearchText),
            Map.entry("outOfWarrant", Ticket::getOutOfWarrant)
    );

    private static final Map<String, Function<TicketComment, Object>> COMMENT_FIELDS = linked(
            Map.entry("sourceSystem", TicketComment::getSourceSystem),
            Map.entry("comment", TicketComment::getComment),
            Map.entry("createdById", TicketComment::getCreatedById),
            Map.entry("dateCreated", TicketComment::getDateCreated),
            Map.entry("lastUpdated", TicketComment::getLastUpdated)
    );

    private static final Map<String, Function<TicketLineItem, Object>> LINE_ITEM_FIELDS = linked(
            Map.entry("sourceSystem", TicketLineItem::getSourceSystem),
            Map.entry("sku", TicketLineItem::getSku),
            Map.entry("channelSku", TicketLineItem::getChannelSku),
            Map.entry("name", TicketLineItem::getName),
            Map.entry("quantity", TicketLineItem::getQuantity),
            Map.entry("refundAmount", TicketLineItem::getRefundAmount),
            Map.entry("dateCreated", TicketLineItem::getDateCreated),
            Map.entry("lastUpdated", TicketLineItem::getLastUpdated)
    );
}
