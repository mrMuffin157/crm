package com.exampleco.crm.repository.ticket;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.core.dto.ticket.TicketReason;
import com.exampleco.core.dto.ticket.TicketStatus;
import com.exampleco.crm.domain.ticket.LegacyTicketCode;
import com.exampleco.crm.domain.ticket.Ticket;

// Custom implementation backing TicketRepositoryCustom (Spring Data's "<RepositoryName>Impl"
// convention). Needed because sorting requires a dynamic ORDER BY, and SQL doesn't allow a
// column name/direction to be a bind parameter the way the other filters are — a plain
// @Query native query has no way to parameterize that safely.
@Repository
public class TicketRepositoryImpl implements TicketRepositoryCustom {

    // Client-facing sort keys mapped to real columns via a fixed whitelist: the ORDER BY
    // clause is built by string concatenation (unavoidable for a dynamic column/direction),
    // so it must never contain client-supplied text directly. Every value here is a literal
    // written in this file, not the caller's sortField/sortDir. "state" lives on the joined
    // address table (see FROM_CLAUSE), not on ticket itself.
    //
    // status/action/reason now store the enum's name() rather than its legacy numeric id (see
    // TicketStatusConverter and doc/0.7.0/convert_ticket_status_action_reason_refund_type_to_
    // string.sql), so a plain "ORDER BY t.status" would sort alphabetically by name instead of
    // by the id order users see today (and TicketStatus's ids aren't even declared in numeric
    // order — Cancelled/ProcessFailed are negative). orderByLegacyId's FIELD(...) expression is
    // still built entirely from this enum's own compile-time values, never client input, so it's
    // just as safe a literal as the plain column names above.
    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("id", "t.id"),
            Map.entry("reason", orderByLegacyId("t.reason", TicketReason.values(), TicketReason::getId)),
            Map.entry("orderNumber", "t.order_number"),
            Map.entry("customerName", "t.customer_name"),
            Map.entry("state", "a.state"),
            Map.entry("action", orderByLegacyId("t.action", TicketAction.values(), TicketAction::getId)),
            Map.entry("status", orderByLegacyId("t.status", TicketStatus.values(), TicketStatus::getId)),
            Map.entry("lastUpdated", "t.last_updated"),
            Map.entry("handlerId", "t.handler_id")
    );

    private static <E extends Enum<E>> String orderByLegacyId(String column, E[] values, ToIntFunction<E> idOf) {
        String namesInIdOrder = Arrays.stream(values)
                .sorted(Comparator.comparingInt(idOf::applyAsInt))
                .map(e -> "'" + e.name() + "'")
                .collect(Collectors.joining(","));
        return "FIELD(" + column + "," + namesInIdOrder + ")";
    }

    private static final String DEFAULT_ORDER_BY = "t.order_number DESC";

    // "Show All Records" used to load every matching row into memory at once; capped instead
    // so a broad/unfiltered search can't pull tens of thousands of tickets in one request.
    private static final int SEARCH_ALL_LIMIT = 100;

    // LEFT JOIN so a ticket with no matching address still appears. The result mapping is
    // "SELECT t.*", never "SELECT *" — both ticket and address have an "id" column, and an
    // unqualified "*" would return two same-named "id" columns, silently corrupting which one
    // Hibernate binds to Ticket.id.
    private static final String FROM_CLAUSE = "FROM ticket t LEFT JOIN address a ON a.id = t.customer_address_id ";

    private static final String WHERE_CLAUSE = """
            WHERE (:status IS NULL OR t.status = :status)
              AND (:action IS NULL OR t.action = :action)
              AND (:starred IS NULL OR t.starred = :starred)
              AND (:handlerId IS NULL OR t.handler_id = :handlerId)
              AND (:search IS NULL
                   OR LOWER(t.search_text) LIKE LOWER(CONCAT('%', :search, '%')) ESCAPE '!'
                   OR t.id = :searchId)
              AND (:orderNumber IS NULL OR t.order_number = :orderNumber)
              AND (:sourceSystem IS NULL OR t.source_system = :sourceSystem)
              AND (:createdAfter IS NULL OR t.date_created >= :createdAfter)
              AND (:createdBefore IS NULL OR t.date_created <= :createdBefore)
              AND t.source_system IN (:allowedChannels)
            """;

    @PersistenceContext
    private EntityManager entityManager;

    private String resolveOrderBy(String sortField, String sortDir) {
        String column = sortField == null ? null : SORT_COLUMNS.get(sortField);
        if (column == null) {
            return DEFAULT_ORDER_BY;
        }
        String direction = "desc".equalsIgnoreCase(sortDir) ? "DESC" : "ASC";
        return column + " " + direction;
    }

    // t.status/t.action now store the enum's name() (see SORT_COLUMNS's comment above), and every
    // caller here — the browser's ticketEnums.js and legacy-app's own M2M query params — now sends
    // that same name. An older cached client can still send the legacy numeric code, though, so
    // both forms are normalized to the stored name in this one place rather than assumed away.
    private static String normalizeStatus(String value) {
        if (value == null) {
            return null;
        }
        return LegacyTicketCode.isNumeric(value) ? TicketStatus.fromId(Integer.parseInt(value)).name() : TicketStatus.valueOf(value).name();
    }

    private static String normalizeAction(String value) {
        if (value == null) {
            return null;
        }
        return LegacyTicketCode.isNumeric(value) ? TicketAction.fromId(Integer.parseInt(value)).name() : TicketAction.valueOf(value).name();
    }

    // The free-text box doubles as a ticket-id lookup: search_text is a plain column written at
    // save time (TicketController#updateSearchText) and never contained the ticket's own id, so a
    // backfill would be the only way to make the LIKE match one. Matching t.id directly instead
    // costs no migration and can't go stale. Derived here rather than passed in as another
    // parameter, for the same reason normalizeStatus/normalizeAction are: the id is a different
    // reading of the same caller-supplied value, and both readings belong in one place.
    //
    // Safe to read off the already-normalized `search` string: TicketController#normalizeSearch
    // only escapes LIKE wildcards and strips '-', neither of which touches an all-digit value.
    // Anything longer than a Long is not an id this table can hold, so it stays a text-only
    // search rather than throwing.
    // Package-private, not private, so TicketSearchIdTest can pin the rule directly — there is no
    // datasource-backed test here to exercise the query itself (see CLAUDE.md on this project's
    // test conventions).
    static Long parseTicketId(String search) {
        if (search == null || search.isEmpty() || search.length() > 18
                || !search.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.valueOf(search);
    }

    private void bindFilterParams(Query query, String status, String action, Boolean starred, Long handlerId,
                                   String search, String orderNumber, String sourceSystem,
                                   LocalDateTime createdAfter, LocalDateTime createdBefore, Collection<String> allowedChannels) {
        query.setParameter("status", normalizeStatus(status));
        query.setParameter("action", normalizeAction(action));
        query.setParameter("starred", starred);
        query.setParameter("handlerId", handlerId);
        query.setParameter("search", search);
        query.setParameter("searchId", parseTicketId(search));
        query.setParameter("orderNumber", orderNumber);
        query.setParameter("sourceSystem", sourceSystem);
        query.setParameter("createdAfter", createdAfter);
        query.setParameter("createdBefore", createdBefore);
        query.setParameter("allowedChannels", allowedChannels);
    }

    @Override
    public Page<Ticket> search(String status, String action, Boolean starred, Long handlerId, String search,
                                String orderNumber, String sourceSystem, LocalDateTime createdAfter, LocalDateTime createdBefore,
                                String sortField, String sortDir, Collection<String> allowedChannels, Pageable pageable) {
        String orderBy = resolveOrderBy(sortField, sortDir);

        Query dataQuery = entityManager.createNativeQuery(
                "SELECT t.* " + FROM_CLAUSE + WHERE_CLAUSE + " ORDER BY " + orderBy, Ticket.class);
        bindFilterParams(dataQuery, status, action, starred, handlerId, search, orderNumber, sourceSystem, createdAfter, createdBefore, allowedChannels);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());
        @SuppressWarnings("unchecked")
        List<Ticket> content = dataQuery.getResultList();

        Query countQuery = entityManager.createNativeQuery("SELECT COUNT(*) " + FROM_CLAUSE + WHERE_CLAUSE);
        bindFilterParams(countQuery, status, action, starred, handlerId, search, orderNumber, sourceSystem, createdAfter, createdBefore, allowedChannels);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public List<Ticket> searchAll(String status, String action, Boolean starred, Long handlerId, String search,
                                   String orderNumber, String sourceSystem, LocalDateTime createdAfter, LocalDateTime createdBefore,
                                   String sortField, String sortDir, Collection<String> allowedChannels) {
        String orderBy = resolveOrderBy(sortField, sortDir);
        Query query = entityManager.createNativeQuery(
                "SELECT t.* " + FROM_CLAUSE + WHERE_CLAUSE + " ORDER BY " + orderBy, Ticket.class);
        bindFilterParams(query, status, action, starred, handlerId, search, orderNumber, sourceSystem, createdAfter, createdBefore, allowedChannels);
        query.setMaxResults(SEARCH_ALL_LIMIT);
        @SuppressWarnings("unchecked")
        List<Ticket> result = query.getResultList();
        return result;
    }

    // "null"/"notnull" (case-insensitive) select a fixed IS NULL / IS NOT NULL clause; a null
    // filterValue means the caller didn't ask to filter on this column at all; anything else is
    // an exact match, bound as a real parameter. The IS NULL / IS NOT NULL SQL text itself is
    // never built from filterValue directly — only this method's own literal clause fragments
    // are — same whitelist-not-concatenation shape as SORT_COLUMNS above.
    private void appendPresenceOrEquals(StringBuilder sql, Map<String, Object> params, String column,
                                         String paramName, String filterValue) {
        if (filterValue == null) {
            return;
        }
        if ("null".equalsIgnoreCase(filterValue)) {
            sql.append(" AND ").append(column).append(" IS NULL");
        } else if ("notnull".equalsIgnoreCase(filterValue)) {
            sql.append(" AND ").append(column).append(" IS NOT NULL");
        } else {
            sql.append(" AND ").append(column).append(" = :").append(paramName);
            params.put(paramName, filterValue);
        }
    }

    @Override
    public List<Ticket> searchForService(String sourceSystem, List<String> statusCodes, List<String> actionCodes,
                                          String trackingNumberFilter, String orderNumberFilter,
                                          LocalDateTime lastUpdatedFrom, LocalDateTime lastUpdatedTo) {
        StringBuilder sql = new StringBuilder("SELECT t.* FROM ticket t WHERE t.source_system = :sourceSystem");
        Map<String, Object> params = new HashMap<>();
        params.put("sourceSystem", sourceSystem);

        if (statusCodes != null && !statusCodes.isEmpty()) {
            sql.append(" AND t.status IN (:statusCodes)");
            params.put("statusCodes", statusCodes.stream().map(TicketRepositoryImpl::normalizeStatus).toList());
        }
        if (actionCodes != null && !actionCodes.isEmpty()) {
            sql.append(" AND t.action IN (:actionCodes)");
            params.put("actionCodes", actionCodes.stream().map(TicketRepositoryImpl::normalizeAction).toList());
        }
        appendPresenceOrEquals(sql, params, "t.tracking_number", "trackingNumber", trackingNumberFilter);
        appendPresenceOrEquals(sql, params, "t.order_number", "orderNumber", orderNumberFilter);
        if (lastUpdatedFrom != null) {
            sql.append(" AND t.last_updated >= :lastUpdatedFrom");
            params.put("lastUpdatedFrom", lastUpdatedFrom);
        }
        if (lastUpdatedTo != null) {
            sql.append(" AND t.last_updated <= :lastUpdatedTo");
            params.put("lastUpdatedTo", lastUpdatedTo);
        }
        sql.append(" ORDER BY t.id ASC");

        Query query = entityManager.createNativeQuery(sql.toString(), Ticket.class);
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Ticket> result = query.getResultList();
        return result;
    }

    @Override
    public List<Object[]> countByStatusGroupedBySourceSystem(Collection<String> statusCodes, Collection<String> allowedChannels) {
        Query query = entityManager.createNativeQuery(
                "SELECT t.status, t.source_system, COUNT(*) FROM ticket t "
                        + "WHERE t.status IN (:codes) AND t.source_system IN (:allowedChannels) "
                        + "GROUP BY t.status, t.source_system");
        query.setParameter("codes", statusCodes.stream().map(TicketRepositoryImpl::normalizeStatus).toList());
        query.setParameter("allowedChannels", allowedChannels);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    // Also filtered to a single status (Processing/"in progress" — see TicketController's own
    // comment on why) unlike the status breakdown above, which needs no such extra filter since
    // status IS the thing being grouped there.
    @Override
    public List<Object[]> countByActionGroupedBySourceSystem(Collection<String> actionCodes, String statusCode, Collection<String> allowedChannels) {
        Query query = entityManager.createNativeQuery(
                "SELECT t.action, t.source_system, COUNT(*) FROM ticket t "
                        + "WHERE t.action IN (:codes) AND t.status = :statusCode AND t.source_system IN (:allowedChannels) "
                        + "GROUP BY t.action, t.source_system");
        query.setParameter("codes", actionCodes.stream().map(TicketRepositoryImpl::normalizeAction).toList());
        query.setParameter("statusCode", normalizeStatus(statusCode));
        query.setParameter("allowedChannels", allowedChannels);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }
}
