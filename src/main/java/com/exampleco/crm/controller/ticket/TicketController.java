package com.exampleco.crm.controller.ticket;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.exampleco.core.dto.ticket.ResolvedLineItemTO;
import com.exampleco.core.dto.ticket.TicketLineItemInputTO;
import com.exampleco.crm.domain.ticket.Address;
import com.exampleco.crm.domain.ticket.Customer;
import com.exampleco.core.dto.ticket.RefundType;
import com.exampleco.crm.domain.ticket.S2User;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.core.dto.ticket.TicketAction;
import com.exampleco.crm.domain.ticket.TicketComment;
import com.exampleco.crm.domain.ticket.TicketEnums;
import com.exampleco.crm.domain.ticket.TicketLineItem;
import com.exampleco.core.dto.ticket.TicketReason;
import com.exampleco.core.dto.ticket.TicketStatus;
import com.exampleco.crm.dto.ticket.AddCommentRequest;
import com.exampleco.crm.dto.ticket.AddressDetail;
import com.exampleco.crm.dto.ticket.AddressInput;
import com.exampleco.crm.dto.ticket.CloseTicketRequest;
import com.exampleco.crm.dto.ticket.CommentSummary;
import com.exampleco.crm.dto.ticket.CustomerDetail;
import com.exampleco.crm.dto.ticket.FailTicketRequest;
import com.exampleco.crm.dto.ticket.HandlerDetail;
import com.exampleco.crm.dto.ticket.LineItemInput;
import com.exampleco.crm.dto.ticket.LineItemSummary;
import com.exampleco.crm.dto.ticket.PatchTicketRequest;
import com.exampleco.crm.dto.ticket.TicketChannelBreakdownResponse;
import com.exampleco.crm.dto.ticket.TicketPageResponse;
import com.exampleco.crm.dto.ticket.TicketReportSourceRow;
import com.exampleco.crm.dto.ticket.TicketWriteRequest;
import com.exampleco.crm.repository.ticket.AddressRepository;
import com.exampleco.crm.exception.ticket.ChannelNotAuthorizedException;
import com.exampleco.crm.exception.ticket.LineItemResolutionException;
import com.exampleco.crm.exception.ticket.LegacyAppUnavailableException;
import com.exampleco.crm.exception.ticket.TicketConflictException;
import com.exampleco.crm.exception.ticket.TicketNotFoundException;
import com.exampleco.crm.repository.ticket.CustomerRepository;
import com.exampleco.crm.repository.ticket.S2UserRepository;
import com.exampleco.crm.repository.ticket.TicketCommentRepository;
import com.exampleco.crm.repository.ticket.TicketLineItemRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;
import com.exampleco.crm.service.audit.AuditActorContext;
import com.exampleco.crm.service.auth.ChannelAccessService;
import com.exampleco.crm.service.auth.LegacyServiceAuthenticationFilter;
import com.exampleco.crm.service.ticket.CrmTicketMapper;
import com.exampleco.crm.service.ticket.LastExceptionResolver;
import com.exampleco.crm.service.ticket.LegacyLineItemResolutionClient;
import com.exampleco.crm.service.ticket.TicketCloseService;
import com.exampleco.crm.service.ticket.TicketCommentService;
import com.exampleco.crm.service.ticket.TicketFailService;
import com.exampleco.crm.service.ticket.TicketIdLock;
import com.exampleco.crm.service.ticket.PickingListExportService;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origins}")
public class TicketController {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 1000;

    // The brand/tenant tag every ticket/address/ticket_line_item/ticket_comment row is
    // NOT NULL on (see the doc/0.2.0 migration + doc/0.5.0/normalize_ticket_source_system.sql,
    // which rewrote the migration's raw legacy schema names to legacy-app's own channel codes:
    // az-us/az-deco/az-ca/az-au) — "crm" is this app's own placeholder for writes that aren't a
    // specific new ticket and so have no real channel to stamp. It is NOT itself a channel code
    // (see TicketEnums.SOURCE_SYSTEM_CHANNELS, which deliberately excludes it) and every §2 API
    // endpoint that scopes by sourceSystem still needs a real one. A brand-new ticket itself no
    // longer uses this constant: createTicket() requires and validates a real channel via the
    // TicketWriteRequest#sourceSystem channel picker, stamping the same value onto
    // the ticket's own address/line-item rows too. This constant remains in use for writes on an
    // *existing* ticket that don't carry their own channel context — failTicket()'s and
    // addTicketComment()'s comments, and TicketCloseService's close-comment.
    public static final String SOURCE_SYSTEM = "crm";

    // Batch size for the IN-clause lookups in enrich() — keeps any single query's IN list
    // bounded instead of inlining every id at once. Matters less now that "Show All Records"
    // (searchAll) is itself capped at 100 rows, but a paged request can still exceed this.
    private static final int ENRICH_BATCH_SIZE = 1000;

    // What the existing UI-facing endpoints have always enriched. The M2M API's include=
    // query param lets legacy-app's jobs ask for a subset instead — comments (and the
    // lastException derived from them) are always loaded regardless, since no caller has asked
    // to skip them and they're cheap relative to the other batched lookups.
    private static final Set<String> DEFAULT_INCLUDES = Set.of("lineItems", "address", "handler", "customer");

    // Serializes the read-max-id/assign/save sequence in createTicket() so two concurrent
    // creates can't compute the same "next id" and collide (or silently overwrite one
    // another). Only guards against a race within this single app instance — acceptable
    // for a low-concurrency internal tool; a real auto-increment column would remove the
    // need for this entirely, but the real table's generation strategy is unconfirmed.
    // A shared bean rather than a private field, so TicketCommentService's
    // TicketComment.id generation reuses the exact same monitor instead of a second lock —
    // see TicketIdLock's own comment.
    private final TicketIdLock ticketIdLock;

    private final TicketRepository ticketRepository;
    private final TicketLineItemRepository ticketLineItemRepository;
    private final TicketCommentRepository ticketCommentRepository;
    private final AddressRepository addressRepository;
    private final S2UserRepository s2UserRepository;
    private final CustomerRepository customerRepository;
    private final PickingListExportService pickingListExportService;
    private final TicketCommentService ticketCommentService;
    private final TicketCloseService ticketCloseService;
    private final TicketFailService ticketFailService;
    private final LegacyLineItemResolutionClient legacyLineItemResolutionClient;
    private final ChannelAccessService channelAccessService;

    public TicketController(TicketRepository ticketRepository, TicketLineItemRepository ticketLineItemRepository,
                             TicketCommentRepository ticketCommentRepository, AddressRepository addressRepository,
                             S2UserRepository s2UserRepository, CustomerRepository customerRepository,
                             PickingListExportService pickingListExportService, TicketIdLock ticketIdLock,
                             TicketCommentService ticketCommentService, TicketCloseService ticketCloseService,
                             TicketFailService ticketFailService,
                             LegacyLineItemResolutionClient legacyLineItemResolutionClient,
                             ChannelAccessService channelAccessService) {
        this.ticketRepository = ticketRepository;
        this.ticketLineItemRepository = ticketLineItemRepository;
        this.ticketCommentRepository = ticketCommentRepository;
        this.addressRepository = addressRepository;
        this.s2UserRepository = s2UserRepository;
        this.customerRepository = customerRepository;
        this.pickingListExportService = pickingListExportService;
        this.ticketIdLock = ticketIdLock;
        this.ticketCommentService = ticketCommentService;
        this.ticketCloseService = ticketCloseService;
        this.ticketFailService = ticketFailService;
        this.legacyLineItemResolutionClient = legacyLineItemResolutionClient;
        this.channelAccessService = channelAccessService;
    }

    // "Export ticket report": every line item on a ticket that's ManualHandling + Processing
    // right now, as a CSV (replaces both the old nightly ticket_report table/page and the PDF
    // picking-list export — see PickingListExportService for the column shape).
    //
    // Channel-scoped like listTickets: this is the ticket list in CSV clothing, and
    // leaving it unscoped handed a member a CSV of every channel's tickets — including the ones
    // an admin had deliberately not granted them — while the very table it exports was filtered
    // for that same user. Route-level rules in SecurityConfig can't express this, so it is
    // enforced here like every other row-level channel check (see CLAUDE.md's SecurityConfig
    // section).
    @GetMapping(value = "/api/tickets/report-export")
    public Mono<ResponseEntity<byte[]>> exportTicketReport(Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            // A member with no channel grants yet gets the same 204 an empty result does, rather
            // than a query with an invalid empty "IN ()" clause — same short-circuit listTickets
            // makes, and the same answer from the frontend's point of view: nothing to download.
            Set<String> allowedChannels = channelAccessService.resolveAllowedChannels(authentication);
            if (allowedChannels.isEmpty()) {
                return ResponseEntity.noContent().<byte[]>build();
            }
            List<TicketReportSourceRow> rows = ticketLineItemRepository.findReportSource(
                    TicketAction.ManualHandling.name(), TicketStatus.Processing.name(), allowedChannels);
            // Nothing to export: 204 instead of a CSV containing only a header row, so the
            // frontend can tell the user there's nothing to download instead of handing them
            // a file with no data in it.
            if (rows.isEmpty()) {
                return ResponseEntity.noContent().<byte[]>build();
            }
            byte[] csv = pickingListExportService.generate(rows);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("ticket-report.csv").build().toString())
                    .body(csv);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping(value = "/api/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<TicketPageResponse> listTickets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Boolean starred,
            @RequestParam(required = false) String handlerUsername,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String orderNumber,
            @RequestParam(required = false) String sourceSystem,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdAfter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdBefore,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(defaultValue = "false") boolean listAll,
            Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() ->
                findTickets(status, action, starred, handlerUsername, search, orderNumber, sourceSystem, createdAfter, createdBefore, sortField, sortDir, page, size, listAll, authentication)
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    // Also what feeds the Dashboard's stat-card counts (dashboardStats.js calls this same
    // endpoint with status+page=0+size=1) — restricting it here restricts the Dashboard for free,
    // with no separate backend path to keep in sync.
    private TicketPageResponse findTickets(String status, String action, Boolean starred, String handlerUsername, String search,
                                            String orderNumber, String sourceSystem, LocalDate createdAfter, LocalDate createdBefore,
                                            String sortField, String sortDir,
                                            int page, int size, boolean listAll, Authentication authentication) {
        String normStatus = blankToNull(status);
        String normAction = blankToNull(action);
        String normSearch = normalizeSearch(search);
        String normOrderNumber = blankToNull(orderNumber);
        String normSourceSystem = blankToNull(sourceSystem);
        String normSortField = blankToNull(sortField);
        String normSortDir = blankToNull(sortDir);
        LocalDateTime after = createdAfter != null ? createdAfter.atStartOfDay() : null;
        LocalDateTime before = createdBefore != null ? createdBefore.atTime(LocalTime.MAX) : null;

        // a member sees only the channels an admin has explicitly granted (see
        // ChannelAccessService) — an empty set (no grants yet) short-circuits to an empty page
        // rather than running a query with an invalid empty "IN ()" clause. An admin/service
        // caller resolves to the full channel universe, a no-op restriction.
        Set<String> allowedChannels = channelAccessService.resolveAllowedChannels(authentication);
        if (allowedChannels.isEmpty()) {
            return new TicketPageResponse(List.of(), 0, 1, Math.max(page, 0), size);
        }

        // The UI never exposes a raw handler id to pick from (see Ticket#handlerId's own
        // @JsonIgnore comment — "no raw user ids"), so this filters by username instead and
        // resolves it to the real handler_id column server-side, same as
        // resolveHandlerUsername() does for create/update. An unknown username can't match any
        // ticket, so short-circuit to an empty page instead of running a query that would
        // filter on a null handlerId and return everyone's tickets.
        String normHandlerUsername = blankToNull(handlerUsername);
        Long handlerId = null;
        if (normHandlerUsername != null) {
            handlerId = s2UserRepository.findByUsername(normHandlerUsername).map(S2User::getId).orElse(null);
            if (handlerId == null) {
                return new TicketPageResponse(List.of(), 0, 1, Math.max(page, 0), size);
            }
        }

        if (listAll) {
            List<Ticket> all = ticketRepository.searchAll(normStatus, normAction, starred, handlerId, normSearch, normOrderNumber, normSourceSystem, after, before, normSortField, normSortDir, allowedChannels);
            enrich(all);
            return new TicketPageResponse(all, all.size(), 1, 0, all.size());
        }

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        var result = ticketRepository.search(normStatus, normAction, starred, handlerId, normSearch, normOrderNumber, normSourceSystem, after, before, normSortField, normSortDir, allowedChannels, pageable);
        enrich(result.getContent());
        return new TicketPageResponse(result.getContent(), result.getTotalElements(), result.getTotalPages(), pageable.getPageNumber(), pageable.getPageSize());
    }

    // Dashboard's channel breakdown widgets: the 3-status set dashboardStats.js already tracks
    // (New/Processing/ProcessFailed) plus the Refund/ManualHandling actions, each grouped by
    // channel rather than dashboardStats.js's existing single-number-per-status shape. Scoped to
    // the caller's own ChannelAccessService allowance same as every other tickets endpoint — an
    // empty allowance (no grants yet) short-circuits to two empty maps rather than an invalid
    // "IN ()" query, same reasoning as findTickets().
    private static final List<String> BREAKDOWN_STATUS_NAMES = List.of(
            TicketStatus.New.name(),
            TicketStatus.Processing.name(),
            TicketStatus.ProcessFailed.name());

    private static final List<String> BREAKDOWN_ACTION_NAMES = List.of(
            TicketAction.Refund.name(),
            TicketAction.ManualHandling.name());

    // The Refund/ManualHandling breakdown is scoped to Processing ("In Progress") tickets only —
    // a Refund/ManualHandling ticket that's already Closed/Cancelled/ProcessFailed isn't the
    // active work queue this widget is for, and mixing every status in would double-count a
    // ticket the status breakdown above already surfaces once it fails or closes.
    private static final String BREAKDOWN_ACTION_STATUS_NAME = TicketStatus.Processing.name();

    @GetMapping(value = "/api/tickets/stats/channel-breakdown", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<TicketChannelBreakdownResponse> getChannelBreakdown(Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            Set<String> allowedChannels = channelAccessService.resolveAllowedChannels(authentication);
            if (allowedChannels.isEmpty()) {
                return new TicketChannelBreakdownResponse(Map.of(), Map.of());
            }
            Map<String, Map<String, Long>> byStatus = groupCountsByName(
                    ticketRepository.countByStatusGroupedBySourceSystem(BREAKDOWN_STATUS_NAMES, allowedChannels));
            Map<String, Map<String, Long>> byAction = groupCountsByName(
                    ticketRepository.countByActionGroupedBySourceSystem(BREAKDOWN_ACTION_NAMES, BREAKDOWN_ACTION_STATUS_NAME, allowedChannels));
            return new TicketChannelBreakdownResponse(byStatus, byAction);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Reshapes a flat [name, sourceSystem, count] row list into name -> sourceSystem -> count.
    // t.status/t.action hold the enum's name() (see TicketStatusConverter/TicketRepositoryImpl),
    // and that's also the dashboard response's own wire format now — the same one every other
    // status/action value uses (see Ticket's now-default enum serialization) — so the raw column
    // value is used as the map key directly, with no code translation.
    private Map<String, Map<String, Long>> groupCountsByName(List<Object[]> rows) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (Object[] row : rows) {
            String name = (String) row[0];
            String sourceSystem = (String) row[1];
            long count = ((Number) row[2]).longValue();
            result.computeIfAbsent(name, k -> new HashMap<>()).put(sourceSystem, count);
        }
        return result;
    }

    // Fills in the display data that isn't a plain column on ticket: state (via
    // customer_address_id -> address), handler/comment-author usernames (via s2user), and
    // line items/comments (via their own tables, keyed by ticket_id). Done as a handful of
    // batched IN-clause queries after the main fetch — not a JOIN in the search query itself
    // — because line items/comments are one-to-many; joining them there would multiply each
    // ticket row per child row instead of attaching a list.
    private void enrich(List<Ticket> tickets) {
        enrich(tickets, DEFAULT_INCLUDES);
    }

    // include-aware variant backing the M2M API, where legacy-app's jobs often only
    // need a subset of what the UI always loads in full — see DEFAULT_INCLUDES.
    private void enrich(List<Ticket> tickets, Set<String> includes) {
        if (tickets.isEmpty()) return;

        // s2user is a small table (employee accounts) — one full fetch serves both the
        // handler lookup below and the comment-author lookup, since both reference it.
        Map<Long, S2User> s2UserById = new HashMap<>();
        Map<Long, String> usernameById = new HashMap<>();
        for (S2User user : s2UserRepository.findAll()) {
            s2UserById.put(user.getId(), user);
            usernameById.put(user.getId(), user.getUsername());
        }
        if (includes.contains("handler")) {
            for (Ticket ticket : tickets) {
                S2User handler = s2UserById.get(ticket.getHandlerId());
                ticket.setHandlerUsername(handler == null ? null : handler.getUsername());
                ticket.setHandler(handler == null ? null
                        : new HandlerDetail(handler.getUsername(), handler.getEmail(), handler.getName()));
            }
        }

        if (includes.contains("address")) {
            List<String> addressIds = tickets.stream().map(Ticket::getCustomerAddressId)
                    .filter(Objects::nonNull).distinct().toList();
            Map<String, Address> addressById = new HashMap<>();
            for (List<String> batch : chunk(addressIds)) {
                for (Address address : addressRepository.findAllById(batch)) {
                    addressById.put(address.getId(), address);
                }
            }
            for (Ticket ticket : tickets) {
                Address address = addressById.get(ticket.getCustomerAddressId());
                ticket.setState(address != null ? address.getState() : null);
                ticket.setAddress(address == null ? null : new AddressDetail(
                        address.getName(), address.getAddressLine1(), address.getAddressLine2(), address.getCity(),
                        address.getState(), address.getZipCode(), address.getCountryCode(), address.getPhone()));
            }
        }

        if (includes.contains("customer")) {
            List<String> customerIds = tickets.stream().map(Ticket::getCustomerId)
                    .filter(Objects::nonNull).distinct().toList();
            Map<String, Customer> customerById = new HashMap<>();
            for (List<String> batch : chunk(customerIds)) {
                for (Customer customer : customerRepository.findAllById(batch)) {
                    customerById.put(customer.getId(), customer);
                }
            }
            for (Ticket ticket : tickets) {
                Customer customer = customerById.get(ticket.getCustomerId());
                ticket.setCustomer(customer == null ? null
                        : new CustomerDetail(customer.getId(), customer.getName(), customer.getEmail()));
            }
        }

        List<Long> ticketIds = tickets.stream().map(Ticket::getId).distinct().toList();

        if (includes.contains("lineItems")) {
            Map<Long, List<LineItemSummary>> lineItemsByTicketId = new HashMap<>();
            for (List<Long> batch : chunk(ticketIds)) {
                for (TicketLineItem item : ticketLineItemRepository.findByTicketIdIn(batch)) {
                    lineItemsByTicketId.computeIfAbsent(item.getTicketId(), k -> new ArrayList<>())
                            .add(new LineItemSummary(item.getId(), item.getSku(), item.getName(), item.getQuantity(),
                                    item.getChannelSku(), item.getRefundAmount()));
                }
            }
            for (Ticket ticket : tickets) {
                List<LineItemSummary> lineItems = lineItemsByTicketId.getOrDefault(ticket.getId(), List.of());
                ticket.setLineItems(lineItems);
                // See Ticket#totalRefundAmount — 0 for a ticket with no Refund rows, since every
                // other action's rows always carry a 0 refundAmount themselves.
                ticket.setTotalRefundAmount(lineItems.stream()
                        .map(LineItemSummary::refundAmount)
                        .filter(Objects::nonNull)
                        .mapToDouble(Double::doubleValue)
                        .sum());
            }
        }

        // Always loaded (not gated by `includes`) so lastException can always be derived — see
        // Ticket#lastException.
        Map<Long, List<CommentSummary>> commentsByTicketId = new HashMap<>();
        for (List<Long> batch : chunk(ticketIds)) {
            for (TicketComment comment : ticketCommentRepository.findByTicketIdInOrderByDateCreatedAsc(batch)) {
                commentsByTicketId.computeIfAbsent(comment.getTicketId(), k -> new ArrayList<>())
                        .add(new CommentSummary(comment.getComment(), usernameById.get(comment.getCreatedById()), comment.getDateCreated()));
            }
        }
        for (Ticket ticket : tickets) {
            List<CommentSummary> comments = commentsByTicketId.getOrDefault(ticket.getId(), List.of());
            ticket.setComments(comments);
            ticket.setLastException(LastExceptionResolver.resolve(comments.stream().map(CommentSummary::comment).toList()));
        }
    }

    private <T> List<List<T>> chunk(List<T> items) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += ENRICH_BATCH_SIZE) {
            chunks.add(items.subList(i, Math.min(i + ENRICH_BATCH_SIZE, items.size())));
        }
        return chunks;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // Parses the M2M API's include= query param (comma-separated: lineItems, address,
    // handler, customer) into the set enrich() expects. Omitted/blank means "give
    // me everything", matching the UI endpoints' existing behavior.
    private Set<String> parseIncludes(String include) {
        String normalized = blankToNull(include);
        if (normalized == null) {
            return DEFAULT_INCLUDES;
        }
        return java.util.Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    // The search query wraps this value as LIKE '%<value>%' ESCAPE '!' (see
    // TicketRepository). Without escaping, a literal '%' or '_' in someone's search text
    // (e.g. searching for "50% off") would act as a SQL wildcard instead of a literal
    // character, silently broadening/breaking the match. Escapes '!' itself first so it
    // doesn't double-escape the characters it introduces.
    private String escapeLike(String value) {
        if (value == null) return null;
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    // updateSearchText() strips every "-" out of the stored search_text blob, so a query left
    // with hyphens in it (e.g. a hyphenated Amazon order number like 112-1234567-1234567 — the
    // exact thing the search box's own placeholder invites) could never match anything. Applying
    // the same stripping here keeps both sides of the LIKE comparison in the same canonical form.
    private String normalizeSearch(String search) {
        String escaped = escapeLike(blankToNull(search));
        return escaped == null ? null : escaped.replace("-", "");
    }

    private boolean isLocked(Ticket ticket) {
        return ticket.getStatus() == TicketStatus.Closed || ticket.getStatus() == TicketStatus.Cancelled;
    }

    // Ticket-not-found, locked-ticket-edit, and malformed request parameters are all
    // expected client errors, not server bugs — map them to clean 4xx responses instead of
    // a generic 500 with a leaked stack trace. Kept as three distinct exception types
    // (rather than one catch-all) on purpose: NumberFormatException and other
    // request-parameter conversion failures are themselves IllegalArgumentException
    // subtypes, so folding "not found" into that same handler previously mislabeled a bad
    // handlerId/date/starred query param as a 404 "ticket not found" instead of a 400.
    @ExceptionHandler(TicketNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TicketNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TicketConflictException.class)
    public ResponseEntity<Map<String, String>> handleIfMatchMismatch(TicketConflictException e) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid request: " + e.getMessage()));
    }

    // 's own rule: "a resolution failure blocks the save (400), showing legacy-app's
    // message verbatim" — a dedicated handler rather than folding this into
    // IllegalArgumentException's, whose "Invalid request: " prefix would break that verbatim
    // requirement.
    @ExceptionHandler(LineItemResolutionException.class)
    public ResponseEntity<Map<String, String>> handleLineItemResolutionFailure(LineItemResolutionException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    // a member tried to create a ticket under a channel they haven't been granted — a
    // real, valid channel, just not one this caller may use, so 403 (not 400/404) is the accurate
    // status.
    @ExceptionHandler(ChannelNotAuthorizedException.class)
    public ResponseEntity<Map<String, String>> handleChannelNotAuthorized(ChannelNotAuthorizedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    // legacy-app's M2M API couldn't be reached at all (as opposed to reaching it and being told no)
    // — 502 Bad Gateway is the accurate status for "the upstream service this request depended on
    // failed", distinct from a 400 (the caller's request was wrong) or a 500 (CRM itself is
    // broken). See LegacyAppUnavailableException's own comment.
    @ExceptionHandler(LegacyAppUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleLegacyAppUnavailable(LegacyAppUnavailableException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    // A required-but-blank field (e.g. handlerId, status) or a made-up foreign key value
    // (e.g. a handlerId that isn't a real s2user id) fails at the database as a constraint
    // violation, not a Java-level validation error — surface that as a clean 4xx instead of
    // a generic 500 with a leaked stack trace, same reasoning as the handlers above.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid request: a required field is missing or references a value that doesn't exist."));
    }

    // =====================================================================================
    // M2M read API for legacy-app. Authenticated either by a CRM session (ROLE_ADMIN/
    // ROLE_MEMBER) or by LegacyServiceAuthenticationFilter's shared-secret Bearer token
    // (ROLE_SERVICE) — SecurityConfig's anyExchange().authenticated() already admits both, so
    // no extra role check is needed here.
    // =====================================================================================

    // True only for a caller authenticated via LegacyServiceAuthenticationFilter's shared bearer
    // token (legacy-app's own jobs), never for a CRM session (ROLE_ADMIN/ROLE_MEMBER) — a
    // session-authenticated CS agent is a human working across every channel, not one job scoped
    // to a single one, so the sourceSystem ownership check below (and the author-override check
    // in addTicketComment) must only ever apply to the service principal, not to them.
    private boolean isService(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> LegacyServiceAuthenticationFilter.AUTHORITY.equals(a.getAuthority()));
    }

    // Gives legacy-app the CrmTicketTO shape it asks for; a CRM session still gets the raw
    // Ticket entity, unchanged from before -- see CrmTicketMapper's own comment for why the two
    // must not be unified.
    private Object toResponse(Ticket ticket, Authentication authentication) {
        return isService(authentication) ? CrmTicketMapper.toTO(ticket) : ticket;
    }

    private Object toResponseList(List<Ticket> tickets, Authentication authentication) {
        return isService(authentication) ? tickets.stream().map(CrmTicketMapper::toTO).toList() : tickets;
    }

    // Audit follow-up (revised §2): "sourceSystem is REQUIRED on every ticket query and
    // mutation... never default to 'all channels'" — without this, az-us's 03:00 job could fetch
    // (or PATCH) an az-ca ticket just by guessing its id, since ids are globally unique
    // post-migration and no longer imply a channel the way "which of the 4 databases" used to.
    // Scoped to service callers only (see isService): a CRM session's existing
    // fetchTicketById/EditTicketModal usage must keep working unscoped, since a human CS agent
    // legitimately works across every channel and passes no sourceSystem today. A mismatch is
    // reported identically to "not found" (404), not a distinct "wrong channel" error — same
    // reasoning the by-legacy/by-order lookups already follow: never confirm to a caller that a
    // ticket exists in a channel it isn't allowed to see.
    private void enforceSourceSystemForService(Authentication authentication, Ticket ticket, String sourceSystem) {
        if (!isService(authentication)) {
            return;
        }
        if (sourceSystem == null || sourceSystem.isBlank()) {
            throw new IllegalArgumentException("sourceSystem is required");
        }
        if (!sourceSystem.equals(ticket.getSourceSystem())) {
            throw new TicketNotFoundException(ticket.getId());
        }
    }

    // a caller not authorized for this ticket's own channel gets the same 404 a
    // mismatched service-caller sourceSystem already gets above — never a distinct "wrong
    // channel" error, so a member can't use this to probe which channels exist. A no-op for
    // ROLE_ADMIN/ROLE_SERVICE, since ChannelAccessService already resolves both of those to the
    // full channel universe.
    private void enforceChannelAuthorized(Authentication authentication, Ticket ticket) {
        if (!channelAccessService.isChannelAuthorized(authentication, ticket.getSourceSystem())) {
            throw new TicketNotFoundException(ticket.getId());
        }
    }

    // Single-ticket fetch by this table's own id. sourceSystem is optional for a CRM session
    // (see enforceSourceSystemForService) but required, and checked against the ticket's own
    // value, for a service caller.
    @GetMapping(value = "/api/tickets/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Object> getTicket(@PathVariable Long id, @RequestParam(required = false) String include,
                                   @RequestParam(required = false) String sourceSystem,
                                   Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            Ticket ticket = ticketRepository.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
            enforceSourceSystemForService(authentication, ticket, sourceSystem);
            enforceChannelAuthorized(authentication, ticket);
            enrich(List.of(ticket), parseIncludes(include));
            return toResponse(ticket, authentication);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // How legacy-app remaps a pre-migration id it already knows about (from its own DB, before the
    // 4-channel merge) into this table's id — see Ticket#legacyId.
    @GetMapping(value = "/api/tickets/by-legacy", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Object> getTicketByLegacyId(@RequestParam String sourceSystem, @RequestParam Long legacyId,
                                             @RequestParam(required = false) String include,
                                             Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            Ticket ticket = ticketRepository.findBySourceSystemAndLegacyId(sourceSystem, legacyId)
                    .orElseThrow(() -> new TicketNotFoundException(
                            "Ticket not found: sourceSystem=" + sourceSystem + " legacyId=" + legacyId));
            enforceChannelAuthorized(authentication, ticket);
            enrich(List.of(ticket), parseIncludes(include));
            return toResponse(ticket, authentication);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // orderNumber isn't unique (see TicketRepository#findByOrderNumber), so this returns every
    // ticket against that order rather than a single one. sourceSystem is required and enforced
    // for a service caller only (see enforceSourceSystemForService):
    // unlike /{id} and /search, orderNumber wasn't originally scoped at all, which would let one
    // channel's job pull another channel's ticket just by knowing its order number. A CRM session
    // stays unscoped by sourceSystem, same as /{id}, since a human CS agent legitimately works
    // across channels — but every returned ticket is still filtered down to the caller's own
    // channel authorization, same as every other lookup here.
    @GetMapping(value = "/api/tickets/by-order", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Object> getTicketsByOrder(@RequestParam String orderNumber,
                                           @RequestParam(required = false) String sourceSystem,
                                           @RequestParam(required = false) String include,
                                           Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            List<Ticket> tickets;
            if (isService(authentication)) {
                if (sourceSystem == null || sourceSystem.isBlank()) {
                    throw new IllegalArgumentException("sourceSystem is required");
                }
                tickets = ticketRepository.findBySourceSystemAndOrderNumber(sourceSystem, orderNumber);
            } else {
                tickets = ticketRepository.findByOrderNumber(orderNumber);
            }
            tickets = tickets.stream()
                    .filter(t -> channelAccessService.isChannelAuthorized(authentication, t.getSourceSystem()))
                    .toList();
            enrich(tickets, parseIncludes(include));
            return toResponseList(tickets, authentication);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // The main work-list query legacy-app's scheduled jobs poll. sourceSystem is
    // required, not optional — without it, one channel's job could pick up another channel's
    // tickets now that they all live in one shared table instead of 4 separate databases.
    // status/action accept legacy-app's own enum constant names (TicketStatus.valueOf/
    // TicketAction.valueOf), comma-separated for an OR match; trackingNumber/orderNumber accept
    // "null"/"notnull" as well as an exact
    // value (see TicketRepositoryImpl#appendPresenceOrEquals).
    @GetMapping(value = "/api/tickets/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Object> searchTicketsForService(
            @RequestParam String sourceSystem,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String trackingNumber,
            @RequestParam(required = false) String orderNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime lastUpdatedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime lastUpdatedTo,
            @RequestParam(required = false) String include,
            Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            if (sourceSystem == null || sourceSystem.isBlank()) {
                throw new IllegalArgumentException("sourceSystem is required");
            }
            List<String> statusCodes = splitAndTranslate(status, name -> TicketStatus.valueOf(name).name());
            List<String> actionCodes = splitAndTranslate(action, name -> TicketAction.valueOf(name).name());
            List<Ticket> tickets = ticketRepository.searchForService(sourceSystem, statusCodes, actionCodes,
                    blankToNull(trackingNumber), blankToNull(orderNumber), lastUpdatedFrom, lastUpdatedTo);
            enrich(tickets, parseIncludes(include));
            return toResponseList(tickets, authentication);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Splits a comma-separated query param and translates each piece through the given
    // function (TicketStatus/TicketAction's valueOf, failing fast on an unknown name rather
    // than letting a bad value reach the query) — null in, null out, so
    // "not provided" stays distinct from "provided but empty" the same way the rest of this
    // controller's optional filters work.
    private List<String> splitAndTranslate(String commaSeparated, java.util.function.Function<String, String> translate) {
        String normalized = blankToNull(commaSeparated);
        if (normalized == null) {
            return null;
        }
        return java.util.Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(translate)
                .toList();
    }

    // Partial ticket update for legacy-app's retained jobs — status/trackingNumber/
    // replacementOrderNumber only, each optional (see PatchTicketRequest). If-Match carries the
    // lastUpdated value the caller read the ticket at (as returned in its own JSON field, ISO-
    // 8601 — no separate ETag header round-trip); a mismatch means a CS agent edited the ticket
    // in between, and the write is rejected rather than silently clobbering that edit. Omitting
    // If-Match entirely skips the check, for callers that intentionally don't need it.
    @PatchMapping(value = "/api/tickets/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Object> patchTicketForService(@PathVariable Long id,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestParam(required = false) String sourceSystem,
                                               @RequestBody PatchTicketRequest request,
                                               Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() ->
                AuditActorContext.runAs(authentication.getName(), () -> {
                    Ticket ticket = ticketRepository.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
                    enforceSourceSystemForService(authentication, ticket, sourceSystem);
                    if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.equals(etag(ticket))) {
                        throw new TicketConflictException(id);
                    }
                    if (isLocked(ticket)) {
                        throw new IllegalStateException("Cannot edit a Closed or Cancelled ticket");
                    }
                    if (request.status() != null) {
                        ticket.setStatus(TicketStatus.valueOf(request.status()));
                    }
                    if (request.trackingNumber() != null) {
                        ticket.setTrackingNumber(request.trackingNumber());
                    }
                    if (request.replacementOrderNumber() != null) {
                        ticket.setReplacementOrderNumber(request.replacementOrderNumber());
                    }
                    ticket.setLastUpdated(LocalDateTime.now());
                    Ticket saved = ticketRepository.save(ticket);
                    return toResponse(saved, authentication);
                })
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    private String etag(Ticket ticket) {
        return ticket.getLastUpdated() == null ? null : ticket.getLastUpdated().toString();
    }

    // =====================================================================================
    // Ticket mutation endpoints. Mirrors the real legacy-app Grails TicketController's
    // newTicket/updateTicket/setStarred/setOutOfWarrant/changeStatus actions, adapted to
    // this REST API. Ticket.id is not a @GeneratedValue column (the real table's
    // auto-increment status is unconfirmed, and CSV bulk-imports set ids explicitly), so a
    // new ticket's id is computed here rather than left to the database.
    // =====================================================================================

    // Matches the legacy form's own behavior: a brand-new ticket defaults to status New and
    // is left unassigned (handler resolved to whoever's creating it) rather than forcing the
    // creator to pick both up front — see CreateTicketForm.jsx, which omits those two inputs
    // entirely and relies on this defaulting.
    @PostMapping(value = "/api/tickets", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Ticket> createTicket(@RequestBody TicketWriteRequest request, Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() ->
                AuditActorContext.runAs(authentication.getName(), () -> {
                    // Only the read-max-id/assign/save sequence needs ticketIdLock's protection against
                    // a concurrent-create id collision (see that field's own comment) — line-item
                    // resolution below now includes a blocking call out to legacy-app
                    // (LegacyLineItemResolutionClient), which must not serialize every other
                    // concurrent ticket creation behind it, so it stays outside this lock's scope.
                    String channel = TicketEnums.requireValidSourceSystem(request.sourceSystem());
                    if (!channelAccessService.isChannelAuthorized(authentication, channel)) {
                        throw new ChannelNotAuthorizedException(channel);
                    }
                    Ticket ticket = new Ticket();
                    ticket.setSourceSystem(channel);
                    applyFields(ticket, request);

                    // Resolve/validate line items against legacy-app before the ticket header (or its
                    // address) is ever persisted below — unlike updateTicket, there is no pre-existing
                    // row here for a resolution failure to safely leave untouched, so a failure must
                    // happen before any write, not after, or it leaves behind an orphan ticket row with
                    // no line items. Only needs sourceSystem/orderNumber/action, all set above already.
                    boolean isRefund = ticket.getAction() == TicketAction.Refund;
                    Map<String, ResolvedLineItemTO> resolved = request.lineItems() != null && !isRefund
                            ? resolveChannelSkus(ticket, request.lineItems())
                            : Map.of();

                    Ticket saved = ticketIdLock.withLock(() -> {
                        if (ticket.getStatus() == null) {
                            ticket.setStatus(TicketStatus.New);
                        }
                        if (ticket.getHandlerId() == null) {
                            ticket.setHandlerId(s2UserRepository.findByUsername(authentication.getName())
                                    .map(S2User::getId)
                                    .orElse(null));
                        }
                        if (request.address() != null) {
                            ticket.setCustomerAddressId(saveAddress(null, request.address(), channel));
                        }
                        Long nextId = ticketRepository.findTopByOrderByIdDesc()
                                .map(existing -> existing.getId() + 1)
                                .orElse(1L);
                        ticket.setId(nextId);
                        ticket.setDateCreated(LocalDateTime.now());
                        ticket.setLastUpdated(LocalDateTime.now());
                        updateSearchText(ticket, request.lineItems());
                        return ticketRepository.save(ticket);
                    });
                    if (request.lineItems() != null) {
                        persistLineItems(saved, request.lineItems(), resolved, isRefund);
                    }
                    return saved;
                })
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    // Mirrors updateTicket(): rewrites the editable fields and blocks the edit outright if
    // the ticket is already Closed(3) or Cancelled(-1) — same read-only rule the frontend
    // already reflects visually (locked row + 🔒 icon).
    @PutMapping(value = "/api/tickets/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Ticket> updateTicket(@PathVariable Long id, @RequestBody TicketWriteRequest request,
                                      Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() ->
                AuditActorContext.runAs(authentication.getName(), () -> {
                    Ticket existing = ticketRepository.findById(id)
                            .orElseThrow(() -> new TicketNotFoundException(id));
                    if (isLocked(existing)) {
                        throw new IllegalStateException("Cannot edit a Closed or Cancelled ticket");
                    }
                    applyFields(existing, request);
                    if (request.address() != null) {
                        existing.setCustomerAddressId(saveAddress(existing.getCustomerAddressId(), request.address(),
                                existing.getSourceSystem()));
                    }
                    existing.setLastUpdated(LocalDateTime.now());
                    updateSearchText(existing, request.lineItems());
                    Ticket saved = ticketRepository.save(existing);
                    if (request.lineItems() != null) {
                        saveLineItems(saved, request.lineItems());
                    }
                    return saved;
                })
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    private void applyFields(Ticket ticket, TicketWriteRequest request) {
        ticket.setCustomerName(request.customerName());
        ticket.setEmail(request.email());
        ticket.setCustomerId(resolveCustomerId(request.customerId(), request.customerName(), request.email()));
        ticket.setOrderNumber(request.orderNumber());
        ticket.setAction(TicketAction.fromJson(request.action()));
        ticket.setReason(TicketReason.fromJson(request.reason()));
        TicketAction action = ticket.getAction();
        // legacy-app only ever populates refundType alongside a Refund action; mirror that here
        // instead of persisting whatever value the request happened to carry over.
        ticket.setRefundType(action == TicketAction.Refund ? RefundType.fromJson(request.refundType()) : null);
        ticket.setTrackingNumber(request.trackingNumber());
        ticket.setReplacementOrderNumber(request.replacementOrderNumber());
        // status and handler_id are the two NOT NULL columns here, and neither form renders an
        // input for them any more (legacy-app's own form has none). Status is derived from
        // action the same way legacy-app's AmazonSalesOrderProviderService#createTicketLineItems
        // does: NoAction closes the ticket outright, the other three actions put it into
        // Processing. An action outside that set (or none at all) falls back to the prior
        // "leave as is" behavior, and a brand-new ticket falls through to createTicket's own
        // New default below.
        if (action == TicketAction.NoAction) {
            ticket.setStatus(TicketStatus.Closed);
        } else if (action == TicketAction.Replacement || action == TicketAction.Refund
                || action == TicketAction.ManualHandling) {
            ticket.setStatus(TicketStatus.Processing);
        } else {
            TicketStatus status = TicketStatus.fromJson(request.status());
            if (status != null) {
                ticket.setStatus(status);
            }
        }
        Long handlerId = resolveHandlerUsername(request.handlerUsername());
        if (handlerId != null) {
            ticket.setHandlerId(handlerId);
        }
        ticket.setStarred(request.starred());
        ticket.setOutOfWarrant(request.outOfWarrant());
        ticket.setDescription(request.description());
    }

    // customerId has had no form input since (see TicketFormFields.jsx), so a blank one
    // is the normal case for a manually created/edited ticket — falls back to an email match
    // against crm_dev's real (not placeholder) customer table so the ticket still links to an
    // existing customer row when one exists. A non-blank customerId today only ever comes from a
    // ticket raised within a Sales Order lookup (SalesOrdersPage.jsx seeding it from the order's
    // own CustomerTO — live legacy-app host or the sales_order_archive fallback, both the same
    // SalesOrderInfoTO shape) and isn't necessarily in crm_dev.customer yet, so it's inserted
    // here rather than trusted blindly: Ticket.customerId has an FK to customer.id
    // (doc/0.2.0/migrate_tickets_to_crm_dev.sql) and would otherwise fail the ticket save outright.
    private String resolveCustomerId(String requestedCustomerId, String customerName, String email) {
        if (requestedCustomerId != null && !requestedCustomerId.isBlank()) {
            if (customerRepository.existsById(requestedCustomerId)) {
                return requestedCustomerId;
            }
            // customer.email is UNIQUE (doc/0.2.0/migrate_tickets_to_crm_dev.sql) — if this email
            // is already tied to a different customer id, reuse that row instead of inserting a
            // second one and hitting the constraint.
            if (email != null && !email.isBlank()) {
                Optional<Customer> byEmail = customerRepository.findFirstByEmailIgnoreCase(email);
                if (byEmail.isPresent()) {
                    return byEmail.get().getId();
                }
                return insertCustomer(requestedCustomerId, customerName, email);
            }
            // email is NOT NULL on this table — nothing safe to insert without one; the ticket
            // still keeps its own customerName/email snapshot set in applyFields above.
            return null;
        }
        if (email == null || email.isBlank()) {
            return null;
        }
        return customerRepository.findFirstByEmailIgnoreCase(email)
                .map(Customer::getId)
                .orElse(null);
    }

    private String insertCustomer(String id, String name, String email) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        customer.setEmail(email);
        LocalDateTime now = LocalDateTime.now();
        customer.setDateCreated(now);
        customer.setLastUpdated(now);
        customerRepository.save(customer);
        return id;
    }

    // A blank/absent username resolves to null (the "unassigned, resolve to whoever's creating
    // it" default createTicket already applies — see below); an unknown one is a clear 400, not
    // a silent null, since unlike "unassigned" it's a caller mistake worth surfacing.
    private Long resolveHandlerUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return s2UserRepository.findByUsername(username)
                .map(S2User::getId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown handler username: " + username));
    }

    // Ports legacy-app's Ticket.groovy#updateSearchText() (called from its beforeInsert/
    // beforeUpdate) so search stays functional now that CRM, not Grails, owns the write path.
    // Must run after the ticket's customerId/customerAddressId are already set on `ticket` (so
    // the customer/address lookups below resolve) but before ticketRepository.save(), since
    // search_text is a plain column, not something recomputed on read.
    private void updateSearchText(Ticket ticket, List<LineItemInput> lineItems) {
        Customer customer = ticket.getCustomerId() == null ? null
                : customerRepository.findById(ticket.getCustomerId()).orElse(null);
        Address address = ticket.getCustomerAddressId() == null ? null
                : addressRepository.findById(ticket.getCustomerAddressId()).orElse(null);

        List<String> custAddrInfo = new ArrayList<>();
        addIfPresent(custAddrInfo, customer == null ? null : customer.getName());
        addIfPresent(custAddrInfo, customer == null ? null : customer.getEmail());
        addIfPresent(custAddrInfo, address == null ? null : address.getName());
        addIfPresent(custAddrInfo, address == null ? null : address.getAddressLine1());
        addIfPresent(custAddrInfo, address == null ? null : address.getAddressLine2());
        addIfPresent(custAddrInfo, address == null ? null : address.getCity());
        addIfPresent(custAddrInfo, address == null ? null : address.getZipCode());
        addIfPresent(custAddrInfo, address == null ? null : address.getPhone());

        List<String> skuList = new ArrayList<>();
        if (lineItems != null) {
            for (LineItemInput item : lineItems) {
                addIfPresent(skuList, item.sku());
            }
        }

        String searchText = String.join("|",
                nullToEmpty(ticket.getOrderNumber()),
                nullToEmpty(ticket.getCustomerName()),
                nullToEmpty(ticket.getEmail()),
                nullToEmpty(ticket.getDescription()),
                String.join("|", custAddrInfo),
                String.join("|", skuList)
        ).replace("-", "");
        ticket.setSearchText(searchText);
    }

    private void addIfPresent(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // Creates a new address row (with an app-generated id — the real column is a plain
    // varchar with no DB-side generation) if this ticket doesn't have one yet, otherwise
    // updates the existing row in place — matching the real form's "prefilled from the
    // ticket's existing customerAddress" edit behavior rather than orphaning the old row.
    private String saveAddress(String existingAddressId, AddressInput input, String sourceSystem) {
        Address address = existingAddressId != null ? addressRepository.findById(existingAddressId).orElse(null) : null;
        boolean isNew = address == null;
        if (isNew) {
            address = new Address();
            address.setId(UUID.randomUUID().toString());
            address.setSourceSystem(sourceSystem);
            address.setDateCreated(LocalDateTime.now());
        }
        address.setName(input.name());
        address.setAddressLine1(input.addressLine1());
        address.setAddressLine2(input.addressLine2());
        address.setCity(input.city());
        address.setState(input.state());
        address.setZipCode(input.zipCode());
        address.setCountryCode(input.countryCode());
        address.setPhone(input.phone());
        address.setLastUpdated(LocalDateTime.now());
        addressRepository.save(address);
        return address.getId();
    }

    // Reconciles the ticket's line-item set against the form's "these rows are the current
    // truth" semantics (add/remove-row UI) — see persistLineItems' own comment for how that's
    // done by sku rather than a blind delete/reinsert.
    //
    // every non-Refund line item's channelSku is resolved fresh against legacy-app on
    // every single save (create AND edit — a stale value would otherwise never get corrected on
    // a later edit if resolution only ran on create). The client-supplied LineItemInput#channelSku
    // is deliberately ignored for these rows: server-side resolution is authoritative, never the
    // frontend's round-tripped value (which only exists because the form re-submits whatever a
    // row already had - see that field's own comment) — even if legacy-app's response happens to
    // omit one, that's still treated as an unresolved line and fails the same as any other,
    // rather than silently falling back to the stale client-supplied value. Resolution happens
    // before persistLineItems touches the database at all, so a LineItemResolutionException here
    // leaves the ticket's existing line items completely untouched.
    //
    // Refund-action tickets are the one exception: they skip legacy-app's product lookup entirely
    // (their channelSku is salesOrderLineItem.amazonsku, carried through from the separate,
    // not-yet-wired-up GET /api/ticketApi/salesOrderLines — see the isRefund check below), so
    // their line items keep whatever channelSku the input already carries (still required to be
    // non-null below).
    private void saveLineItems(Ticket ticket, List<LineItemInput> inputs) {
        boolean isRefund = ticket.getAction() == TicketAction.Refund;
        Map<String, ResolvedLineItemTO> resolved = isRefund ? Map.of() : resolveChannelSkus(ticket, inputs);
        persistLineItems(ticket, inputs, resolved, isRefund);
    }

    // Split out of saveLineItems() so createTicket can resolve/validate line items up front
    // (before the ticket header exists at all) while still sharing this same per-item
    // persistence logic — see createTicket's own comment for why.
    //
    // Reconciles by sku against what's already on the ticket rather than the old
    // delete-everything/reinsert-everything approach (fix): a line item whose fields
    // are unchanged keeps its id and is left alone entirely, so an edit that only touches an
    // unrelated ticket field (e.g. description) no longer fabricates a DELETE+INSERT pair —
    // and Envers-audited history for a real line item edit shows only the fields that actually
    // changed instead of the whole row appearing to be freshly created. A sku no longer present
    // in the input is deleted; a new sku is inserted as before. Also fixes a latent bug in the
    // old approach: a LineItemResolutionException thrown partway through (the Refund inline
    // channelSku check below) used to fire after everything had already been deleted, wiping
    // the ticket's existing line items on a failed save — this only ever saves/deletes what it
    // actually needs to change.
    private void persistLineItems(Ticket ticket, List<LineItemInput> inputs, Map<String, ResolvedLineItemTO> resolved,
                                   boolean isRefund) {
        boolean isReplacement = ticket.getAction() == TicketAction.Replacement;
        Map<String, TicketLineItem> existingBySku = ticketLineItemRepository.findByTicketIdIn(List.of(ticket.getId()))
                .stream()
                .collect(Collectors.toMap(TicketLineItem::getSku, item -> item, (a, b) -> a));
        LocalDateTime now = LocalDateTime.now();
        for (LineItemInput input : inputs) {
            ResolvedLineItemTO resolvedItem = resolved.get(input.sku());
            String channelSku = isRefund ? input.channelSku() : resolvedItem == null ? null : resolvedItem.getChannelSku();
            if (channelSku == null || channelSku.isBlank()) {
                throw new LineItemResolutionException(isRefund
                        ? "Line item '" + input.sku() + "' has no channel SKU: refund line items need one from "
                                + "the sales order lines lookup, which isn't available on this ticket yet"
                        : "legacy-app did not return a channel SKU for line item '" + input.sku() + "'");
            }
            // A Replacement line's name is legacy-app's own resolved product name, not whatever the
            // frontend round-tripped (see the isReplacement check above); ManualHandling keeps
            // the human-typed part description legacy-app never sees.
            String name = isReplacement && resolvedItem.getName() != null ? resolvedItem.getName() : input.name();
            // Refund rows never resolve against legacy-app (resolved is Map.of() for them — see
            // isRefund above), so resolvedItem is null there: guard, don't rely on the `&&`
            // short-circuit above like the name line does, or every Refund ticket save NPEs.
            String channelProductName = resolvedItem == null ? null : resolvedItem.getName();
            Double refundAmount = input.refundAmount() != null ? input.refundAmount() : 0.0;

            TicketLineItem item = existingBySku.remove(input.sku());
            boolean isNew = item == null;
            if (isNew) {
                item = new TicketLineItem();
                item.setTicketId(ticket.getId());
                item.setSourceSystem(ticket.getSourceSystem());
                item.setSku(input.sku());
                item.setDateCreated(now);
            }
            boolean fieldsChanged = isNew
                    || !Objects.equals(item.getName(), name)
                    || !Objects.equals(item.getChannelProductName(), channelProductName)
                    || !Objects.equals(item.getQuantity(), input.quantity())
                    || !Objects.equals(item.getChannelSku(), channelSku)
                    || !Objects.equals(item.getRefundAmount(), refundAmount);
            item.setName(name);
            item.setChannelProductName(channelProductName);
            item.setQuantity(input.quantity());
            item.setChannelSku(channelSku);
            item.setRefundAmount(refundAmount);
            if (fieldsChanged) {
                item.setLastUpdated(now);
                ticketLineItemRepository.save(item);
            }
        }
        // Whatever's left wasn't in the new input set at all — a real removal, not a save that
        // happened to leave a line item untouched.
        if (!existingBySku.isEmpty()) {
            ticketLineItemRepository.deleteAllById(existingBySku.values().stream().map(TicketLineItem::getId).toList());
        }
    }

    // Resolves every non-Refund line item against legacy-app on the ticket's own channel host;
    // blocking here is safe (see LegacyLineItemResolutionClient's own comment) since this whole
    // method already runs inside createTicket/updateTicket's Schedulers.boundedElastic()
    // callable.
    private Map<String, ResolvedLineItemTO> resolveChannelSkus(Ticket ticket, List<LineItemInput> inputs) {
        if (inputs.isEmpty()) {
            return Map.of();
        }
        List<TicketLineItemInputTO> toResolve = inputs.stream()
                .map(TicketController::toResolveInput)
                .toList();
        String action = ticket.getAction() == null ? null : ticket.getAction().name();
        return legacyLineItemResolutionClient.resolve(ticket.getSourceSystem(), action, ticket.getOrderNumber(), toResolve)
                .block();
    }

    // exampleco-core's TicketLineItemInputTO is a plain bean (Lombok @Getter/@Setter), not a
    // record, and types quantity as a primitive int where LineItemInput carries a nullable
    // Double — a missing quantity resolves as 0 here rather than throwing, exactly as
    // persistLineItems already treats a missing refundAmount.
    private static TicketLineItemInputTO toResolveInput(LineItemInput input) {
        TicketLineItemInputTO item = new TicketLineItemInputTO();
        item.setSku(input.sku());
        item.setName(input.name());
        item.setQuantity(input.quantity() == null ? 0 : (int) Math.round(input.quantity()));
        return item;
    }

    // Mirrors setStarred()/setOutOfWarrant() — small AJAX boolean toggles in the real UI
    // that just flip a field and save, no full-form edit required.
    @PatchMapping(value = "/api/tickets/{id}/starred", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Ticket> setStarred(@PathVariable Long id, @RequestParam boolean value,
                                    Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() ->
                AuditActorContext.runAs(authentication.getName(), () -> {
                    Ticket ticket = ticketRepository.findById(id)
                            .orElseThrow(() -> new TicketNotFoundException(id));
                    ticket.setStarred(value);
                    return ticketRepository.save(ticket);
                })
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    @PatchMapping(value = "/api/tickets/{id}/out-of-warrant", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Ticket> setOutOfWarrant(@PathVariable Long id, @RequestParam boolean value,
                                         Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() ->
                AuditActorContext.runAs(authentication.getName(), () -> {
                    Ticket ticket = ticketRepository.findById(id)
                            .orElseThrow(() -> new TicketNotFoundException(id));
                    ticket.setOutOfWarrant(value);
                    return ticketRepository.save(ticket);
                })
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    // Mirrors changeStatus() — blocks moving a ticket away from Closed/Cancelled, matching
    // the real system's "finished tickets are final" rule.
    @PatchMapping(value = "/api/tickets/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Ticket> changeStatus(@PathVariable Long id, @RequestParam String value,
                                      Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() ->
                AuditActorContext.runAs(authentication.getName(), () -> {
                    Ticket ticket = ticketRepository.findById(id)
                            .orElseThrow(() -> new TicketNotFoundException(id));
                    if (isLocked(ticket)) {
                        throw new IllegalStateException("Cannot change status of a Closed or Cancelled ticket");
                    }
                    ticket.setStatus(TicketStatus.fromJson(value));
                    ticket.setLastUpdated(LocalDateTime.now());
                    return ticketRepository.save(ticket);
                })
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    // Mirrors doCloseTicket() — the modal-driven counterpart to the plain changeStatus()
    // Cancel button: saves an optional tracking number and, if given, an optional closing
    // comment, alongside moving the ticket to Closed. carrier is null (TicketCloseService
    // derives it from the tracking number's own "1Z" prefix — see that class's own comment);
    // includeBcc is true here (the CS notify BCC, manual close only — see
    // ReplacementShippedMailService), unlike TicketCloseImportService's bulk path.
    @PostMapping(value = "/api/tickets/{id}/close", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Ticket> closeTicket(@PathVariable Long id, @RequestBody CloseTicketRequest request,
                                     Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() ->
                AuditActorContext.runAs(authentication.getName(), () ->
                        ticketCloseService.close(id, request.trackingNumber(), request.comment(),
                                authentication.getName(), null, true))
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    // POST .../fail — legacy-app's retained jobs need to record why an automated step
    // failed and move the ticket to ProcessFailed in one HTTP call. Grails does this as one
    // transaction across 4 call sites today; splitting it into a separate comment POST and
    // status PATCH would let a caller observe (or crash between) a half-done state — comment
    // saved but status unchanged, or vice versa. Delegates to TicketFailService, whose
    // @Transactional boundary is what actually prevents that half-done state (this controller
    // method itself can't provide one — see that class's comment).
    @PostMapping(value = "/api/tickets/{id}/fail", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Object> failTicket(@PathVariable Long id, @RequestBody FailTicketRequest request,
                                    Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() ->
                AuditActorContext.runAs(authentication.getName(), () ->
                        toResponse(ticketFailService.fail(id, SOURCE_SYSTEM, request.comment(), authentication.getName()), authentication))
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    // Mirrors #addComment — the standalone comment icon in the Comments column, independent
    // of ticket lock status (unlike edit/status changes, a closed ticket can still take a
    // follow-up note). Viewing existing comments needs no endpoint of its own: every ticket
    // fetch already embeds its full comment list via enrich(), and the frontend's existing
    // expandable detail row displays it. Also serves as the M2M comment endpoint:
    // legacy-app's jobs POST here too, optionally naming a real author (see AddCommentRequest).
    @PostMapping(value = "/api/tickets/{id}/comments", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CommentSummary> addTicketComment(@PathVariable Long id, @RequestBody AddCommentRequest request,
                                                  Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            ticketRepository.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
            if (request.comment() == null || request.comment().isBlank()) {
                throw new IllegalArgumentException("Comment text is required");
            }
            // A session-authenticated caller can't spoof this: `author` is only honored when
            // the caller authenticated as the shared service principal (see isService) —
            // everyone else's comment is always attributed to their own logged-in username, same
            // as before this field existed.
            String authorUsername = (isService(authentication) && request.author() != null && !request.author().isBlank())
                    ? request.author() : authentication.getName();
            // The audit "who" is this same authorUsername, not authentication.getName() — a
            // legacy-app comment attributed to a real author should show that author, not "legacy-app",
            // as the actor.
            return AuditActorContext.runAs(authorUsername, () ->
                    ticketCommentService.addComment(id, SOURCE_SYSTEM, request.comment(), authorUsername));
        }).subscribeOn(Schedulers.boundedElastic()));
    }
}
