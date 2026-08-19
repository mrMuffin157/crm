import { useEffect, useRef, useState } from 'react'
import { exportTicketReport, fetchAllTicketsForExport, fetchTicketById, fetchTickets } from './api.js'
import { downloadTicketsExcel } from './ticketExport.js'
import { useToast } from './Toast.jsx'
import CloseTicketModal from './CloseTicketModal.jsx'
import AddCommentModal from './AddCommentModal.jsx'
import TicketHistoryModal from './TicketHistoryModal.jsx'
import {
  ACTION_OPTIONS,
  SOURCE_SYSTEM_OPTIONS,
  STATUS_OPTIONS,
} from './ticketEnums.js'
import CreateTicketForm from './CreateTicketForm.jsx'
import EditTicketModal from './EditTicketModal.jsx'
import DashboardPage from './DashboardPage.jsx'
import { DASHBOARD_STATS, useDashboardStats } from './dashboardStats.js'
import { useChannelBreakdown } from './channelBreakdown.js'
import WipPlaceholder from './WipPlaceholder.jsx'
import SalesOrdersPage from './SalesOrdersPage.jsx'
import MembersPage from './MembersPage.jsx'
import QuartzJobsPage from './QuartzJobsPage.jsx'
import CloseTicketImportPage from './CloseTicketImportPage.jsx'
import ProfilePage from './ProfilePage.jsx'
import LoginPage from './LoginPage.jsx'
import { fetchCurrentUser, logout } from './authApi.js'
import { PaginationControls } from './tableControls.jsx'
import { ResetColumnsButton, useTicketColumnLayout } from './ticketColumns.jsx'
import TicketTable from './TicketTable.jsx'
import { PREF_KEYS, usePersistedToggle } from './viewPreferences.js'
import './index.css'

const PAGE_SIZE = 100

const DEFAULT_FILTERS = {
  search: '',
  action: '',
  status: '',
  starred: '',
  createdAfter: '',
  createdBefore: '',
  handlerUsername: '',
  sourceSystem: '',
}

// Flat nav, no more accordion — Tickets and All Sales Orders are the only two real pages,
// so there's no reason to nest them under a category the way the old placeholder modules
// (Sales Report, Product, Marketing, RMA, Common, etc.) used to be.
const NAV_TOP_ITEM = { id: 'dashboards', label: 'Dashboards', icon: '🏠' }

const PAGE_ITEMS = [
  { id: 'tickets', label: 'Tickets', icon: '🎫' },
  { id: 'all-sales-orders', label: 'All Sales Orders', icon: '🛒' },
  // Everyday CS work, not an admin action — every member who can close a ticket one
  // at a time can also close them in bulk from the shipping-label vendor export. The real
  // security boundary is server-side (SecurityConfig's own hasAnyRole(ADMIN, MEMBER) rule for
  // POST /api/tickets/close-import), not this list.
  { id: 'close-ticket-import', label: 'Close Ticket Import', icon: '📥' },
]

// Only ever shown to admins (see Sidebar's isAdmin check below) — and the real security
// boundary is server-side (SecurityConfig requires ROLE_ADMIN for /api/admin/**), not this.
// Hiding it here is purely so members don't see a section they have no access to anyway.
const ADMIN_ITEMS = [
  { id: 'members', label: 'Members', icon: '👥' },
  { id: 'quartz-jobs', label: 'Scheduled Jobs', icon: '⏱️' },
]

// Every user has a profile, admin or not — sits right above Log out rather than in the main
// nav/Admin lists above, since it's account self-service, not a page about tickets/orders/team.
const PROFILE_ITEM = { id: 'profile', label: 'Profile', icon: '👤' }

function SidebarLeaf({ item, currentView, collapsed, onNavigate }) {
  return (
    <button
      type="button"
      className={`sidebar-leaf${currentView === item.id ? ' sidebar-leaf-active' : ''}`}
      onClick={() => onNavigate(item.id, item.label)}
      title={item.label}
    >
      <span className="sidebar-icon">{item.icon}</span>
      {!collapsed && <span className="sidebar-label">{item.label}</span>}
    </button>
  )
}

function Sidebar({ collapsed, onToggleCollapsed, currentView, onNavigate, currentUser, onLogout, dashboardStats, onStatClick }) {
  const isAdmin = currentUser?.role === 'ADMIN'
  return (
    <aside className={`sidebar${collapsed ? ' sidebar-collapsed' : ''}`}>
      <div className="sidebar-header">
        {!collapsed && (
          <div className="sidebar-brand">
            <span>🌐</span>
            <span className="sidebar-brand-name">CRM</span>
            <span className="sidebar-version">v0.6.0</span>
          </div>
        )}
        <button
          type="button"
          className="sidebar-toggle"
          onClick={onToggleCollapsed}
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {collapsed ? '»' : '«'}
        </button>
      </div>

      <nav className="sidebar-nav">
        {!collapsed && <div className="sidebar-section-label">Navigation</div>}
        <SidebarLeaf item={NAV_TOP_ITEM} currentView={currentView} collapsed={collapsed} onNavigate={onNavigate} />
        {!collapsed && (
          <div className="sidebar-dashboard-stats">
            {dashboardStats.error && (
              <div className="sidebar-stat-error" title={`Couldn't load stats: ${dashboardStats.error}`}>
                ⚠ stats unavailable
              </div>
            )}
            {DASHBOARD_STATS.map((stat) => (
              <button
                key={stat.key}
                type="button"
                className={`sidebar-stat ${stat.sidebarClassName}`}
                onClick={() => onStatClick(stat.status)}
                title={`View ${stat.label} tickets`}
              >
                <span className="sidebar-stat-value">{dashboardStats.counts ? dashboardStats.counts[stat.key] : '—'}</span>
                <span className="sidebar-stat-label">{stat.sidebarLabel}</span>
              </button>
            ))}
          </div>
        )}

        {!collapsed && <div className="sidebar-section-label">Pages</div>}
        {PAGE_ITEMS.map((item) => (
          <SidebarLeaf key={item.id} item={item} currentView={currentView} collapsed={collapsed} onNavigate={onNavigate} />
        ))}

        {isAdmin && (
          <>
            {!collapsed && <div className="sidebar-section-label">Admin</div>}
            {ADMIN_ITEMS.map((item) => (
              <SidebarLeaf key={item.id} item={item} currentView={currentView} collapsed={collapsed} onNavigate={onNavigate} />
            ))}
          </>
        )}

        <div className="sidebar-logout-block">
          {!collapsed && currentUser && <div className="sidebar-username" title={currentUser.username}>{currentUser.username}</div>}
          <SidebarLeaf item={PROFILE_ITEM} currentView={currentView} collapsed={collapsed} onNavigate={onNavigate} />
          <button type="button" className="sidebar-leaf sidebar-logout-leaf" onClick={onLogout} title="Log out">
            <span className="sidebar-icon">⏻</span>
            {!collapsed && <span className="sidebar-label">Log out</span>}
          </button>
        </div>
      </nav>
    </aside>
  )
}

const DEFAULT_SORT = { field: 'id', dir: 'desc' }

function MainApp({ currentUser, onLogout }) {
  const showToast = useToast()
  const [tickets, setTickets] = useState([])
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [page, setPage] = useState(0)
  const [pageInput, setPageInput] = useState('')

  const [draft, setDraft] = useState(DEFAULT_FILTERS)
  const [filters, setFilters] = useState(DEFAULT_FILTERS)
  const [sort, setSort] = useState(DEFAULT_SORT)

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [editingTicket, setEditingTicket] = useState(null)
  const [closingTicket, setClosingTicket] = useState(null)
  const [commentingTicket, setCommentingTicket] = useState(null)
  const [historyTicket, setHistoryTicket] = useState(null)
  const [reloadToken, setReloadToken] = useState(0)
  const [exportMessage, setExportMessage] = useState(null)
  const [exportMessageFading, setExportMessageFading] = useState(false)
  const [excelExporting, setExcelExporting] = useState(false)

  // Shown for 0.5s at full opacity, then fades out over 0.5s and clears — the message
  // shouldn't linger in the toolbar indefinitely, but a plain instant disappear is jarring.
  useEffect(() => {
    if (!exportMessage) return
    setExportMessageFading(false)
    const fadeTimer = setTimeout(() => setExportMessageFading(true), 500)
    const clearTimer = setTimeout(() => setExportMessage(null), 1000)
    return () => {
      clearTimeout(fadeTimer)
      clearTimeout(clearTimer)
    }
  }, [exportMessage])

  // Opens straight to a ticket's edit modal when the app is loaded from the failed-ticket
  // digest email's link (?ticketId=<id> — see NotifyFailedTicketsService).
  // Runs once on mount; strips the param afterward so a later in-app reload/refresh doesn't
  // keep reopening the same modal.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const ticketId = params.get('ticketId')
    if (!ticketId) return
    fetchTicketById(ticketId)
      .then(setEditingTicket)
      .catch((e) => showToast(e.message, 'error'))
      .finally(() => {
        params.delete('ticketId')
        const query = params.toString()
        window.history.replaceState({}, '', window.location.pathname + (query ? `?${query}` : ''))
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Persisted, like every other view preference here — see viewPreferences.js.
  const [sidebarCollapsed, setSidebarCollapsed] = usePersistedToggle(PREF_KEYS.sidebarCollapsed, false)
  // Lifted out of TicketTable (unlike its striped-rows/collapse toggles, which that component
  // owns) because the pagination/footer below the table hides entirely while scroll-locked, and
  // only this page has that pagination to hide. Persisted under the same key TicketTable's own
  // toggles use, so the checkbox reads the same on every page that shows the table.
  const [scrollLocked, setScrollLocked] = usePersistedToggle(PREF_KEYS.scrollLocked, true)
  // Per-browser column widths/order for the ticket table (shared with the Sales Order page's
  // Linked Tickets table via ticketColumns.jsx's own localStorage keys).
  const columnLayout = useTicketColumnLayout()
  const [currentView, setCurrentView] = useState('tickets')
  const [currentViewLabel, setCurrentViewLabel] = useState('Tickets')
  // Set by clicking a ticket's order number, consumed (and cleared) by SalesOrdersPage as soon
  // as it mounts and runs the lookup — a plain nav-item click into 'all-sales-orders' never sets
  // this, so it can't stick around and re-trigger a stale lookup on a later visit.
  const [pendingSalesOrderNumber, setPendingSalesOrderNumber] = useState(null)
  const dashboardStats = useDashboardStats()
  const channelBreakdown = useChannelBreakdown()

  // the Sales Channel filter only ever offers channels this user is actually
  // authorized for — real enforcement happens server-side (TicketController) regardless, this
  // is UX only. currentUser.allowedChannels is always a concrete list (never null/undefined),
  // including for admins (the full channel universe — see AuthController#toCurrentUser).
  const allowedSourceSystemOptions = SOURCE_SYSTEM_OPTIONS.filter((o) => currentUser.allowedChannels.includes(o.value))

  // The filter/sort half of a tickets request, shared by the paged list fetch below and the
  // Excel export's own fetch-every-page loop (fetchAllTicketsForExport) — both need to search
  // on exactly the same criteria the Tickets page currently has applied.
  function currentSearchParams() {
    return {
      search: filters.search,
      action: filters.action,
      status: filters.status,
      starred: filters.starred,
      createdAfter: filters.createdAfter || undefined,
      createdBefore: filters.createdBefore || undefined,
      handlerUsername: filters.handlerUsername || undefined,
      sourceSystem: filters.sourceSystem || undefined,
      sortField: sort.field || undefined,
      sortDir: sort.dir || undefined,
    }
  }

  function loadTickets() {
    return fetchTickets({ ...currentSearchParams(), page, size: PAGE_SIZE })
  }

  function applyTicketData(data) {
    setTickets(data.content)
    setTotalElements(data.totalElements)
    setTotalPages(Math.max(data.totalPages, 1))
  }

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    loadTickets()
      .then((data) => {
        if (!cancelled) applyTicketData(data)
      })
      .catch((err) => {
        if (!cancelled) setError(err.message)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters, page, sort])

  // A row-level toggle (star, out-of-warranty, cancel) bumps reloadToken to refresh the list's
  // content in place. Deliberately skips setLoading — routing it through the effect above would
  // flip the table to its "Loading tickets…" placeholder and back for a single-row change,
  // flickering the whole table instead of just updating the toggled row.
  useEffect(() => {
    if (reloadToken === 0) return
    let cancelled = false

    loadTickets()
      .then((data) => {
        if (!cancelled) applyTicketData(data)
      })
      .catch((err) => {
        if (!cancelled) setError(err.message)
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reloadToken])

  // MainApp never unmounts on navigation — currentView just swaps which branch of the ternary
  // below renders — so leaving the Tickets view (e.g. to Close Ticket Import) and coming back
  // doesn't re-run the load effect above on its own; nothing in its [filters, page, sort] deps
  // changed. Bump reloadToken on the transition back into 'tickets' so a close/import/edit done
  // elsewhere is reflected instead of showing whatever the table last held. Skipped on the
  // initial mount (previousView already equals 'tickets') since the load effect above already
  // covers that.
  const previousViewRef = useRef(currentView)
  useEffect(() => {
    if (currentView === 'tickets' && previousViewRef.current !== 'tickets') {
      setReloadToken((t) => t + 1)
    }
    previousViewRef.current = currentView
  }, [currentView])

  function applyFilters() {
    setFilters({ ...draft })
    setPage(0)
  }

  // A plain <a href> can't see the 204-empty case ahead of the click, so this fetches the CSV
  // itself and either triggers the download or tells the user there's nothing to export. Shown
  // next to the button itself, not a toast — this is the button's own outcome, not a
  // page-level event.
  function handleExportReport() {
    // Both exports confirm first: each one downloads a file and the Excel one below fires a run
    // of search requests to do it, so a mis-click is worth a question. window.confirm, the same
    // affordance every other confirm-worthy action here uses (TicketTable's Cancel, MembersPage's
    // deactivate/delete) rather than a modal of its own.
    if (!window.confirm('Export the ticket report? This downloads a CSV of every ManualHandling ticket currently Processing.')) return
    setExportMessage(null)
    exportTicketReport()
      .then((blob) => {
        if (!blob) {
          setExportMessage('No tickets to export.')
          return
        }
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = 'ticket-report.csv'
        link.click()
        URL.revokeObjectURL(url)
      })
      .catch((err) => setExportMessage(err.message))
  }

  // "Excel export": everything currently matching the Tickets page's applied filters/sort, not
  // just the page on screen — fetchAllTicketsForExport loops the paginated search endpoint
  // (currentSearchParams(), same criteria loadTickets() uses) until every matching row across
  // every page has been collected, then hands them to downloadTicketsExcel to build the .xlsx.
  // Reuses the same exportMessage/exportMessageFading UI as the CSV report above.
  function handleExcelExport() {
    // Names the row count (totalElements — the applied filters' own total, the exact set
    // currentSearchParams() is about to page through) so the confirm says how big this export
    // actually is rather than asking a generic "are you sure?".
    if (!window.confirm(`Export all ${totalElements} ticket${totalElements === 1 ? '' : 's'} matching the current filters to Excel? Everything matching is exported, not just the page on screen.`)) return
    setExportMessage(null)
    setExcelExporting(true)
    fetchAllTicketsForExport(currentSearchParams())
      .then((allTickets) => {
        if (allTickets.length === 0) {
          setExportMessage('No tickets to export.')
          return
        }
        downloadTicketsExcel(allTickets)
      })
      .catch((err) => setExportMessage(err.message))
      .finally(() => setExcelExporting(false))
  }

  function handleFilterKeyDown(e) {
    if (e.key === 'Enter') applyFilters()
  }

  // off -> ascending -> descending -> off. Clicking a different column always starts it at
  // ascending and drops whatever column was previously active — only one sort at a time.
  function toggleSort(field) {
    setSort((prev) => {
      if (prev.field !== field) return { field, dir: 'asc' }
      if (prev.dir === 'asc') return { field, dir: 'desc' }
      return { field: null, dir: null }
    })
    setPage(0)
  }

  function handlePageJumpSubmit(e) {
    e.preventDefault()
    const parsed = parseInt(pageInput, 10)
    if (!Number.isFinite(parsed)) return
    setPage(Math.min(Math.max(parsed, 1), totalPages) - 1)
    setPageInput('')
  }

  function handleNavigate(id, label) {
    if (id === 'tickets') {
      setDraft(DEFAULT_FILTERS)
      setFilters(DEFAULT_FILTERS)
      setSort(DEFAULT_SORT)
      setPage(0)
      setPageInput('')
    }
    setCurrentView(id)
    setCurrentViewLabel(label)
  }

  // Sidebar's under-the-nav-item stats, the Dashboard page's stat cards, and the Dashboard's
  // channel breakdown widgets all jump straight to the Tickets page pre-filtered, rather than
  // just navigating there plain. partialFilters overlays onto DEFAULT_FILTERS (e.g. {status} for
  // a stat card, or {action, status, sourceSystem} for a channel breakdown row).
  function handleFilterByQuery(partialFilters) {
    const nextFilters = { ...DEFAULT_FILTERS, ...partialFilters }
    setDraft(nextFilters)
    setFilters(nextFilters)
    setSort(DEFAULT_SORT)
    setPage(0)
    setPageInput('')
    setCurrentView('tickets')
    setCurrentViewLabel('Tickets')
  }

  function handleFilterByStatus(status) {
    handleFilterByQuery({ status })
  }

  // A ticket's Order # cell (TicketTable) jumps straight to the Sales Order page with that order
  // already looked up, the same loop CustomerView's own order numbers form back into it — see
  // SalesOrdersPage's initialOrderNumber effect for the other half.
  function handleOpenSalesOrder(orderNumber) {
    setPendingSalesOrderNumber(orderNumber)
    setCurrentView('all-sales-orders')
    setCurrentViewLabel('All Sales Orders')
  }

  return (
    <div className="layout">
      <Sidebar
        collapsed={sidebarCollapsed}
        onToggleCollapsed={() => setSidebarCollapsed((c) => !c)}
        currentView={currentView}
        onNavigate={handleNavigate}
        currentUser={currentUser}
        onLogout={onLogout}
        dashboardStats={dashboardStats}
        onStatClick={handleFilterByStatus}
      />
      <div className="app">
        {currentView === 'dashboards' ? (
          <DashboardPage stats={dashboardStats} onStatClick={handleFilterByStatus} channelBreakdown={channelBreakdown} onFilterClick={handleFilterByQuery} />
        ) : currentView === 'all-sales-orders' ? (
          <SalesOrdersPage
            initialOrderNumber={pendingSalesOrderNumber}
            onInitialOrderConsumed={() => setPendingSalesOrderNumber(null)}
          />
        ) : currentView === 'members' ? (
          <MembersPage currentUsername={currentUser.username} />
        ) : currentView === 'close-ticket-import' ? (
          <CloseTicketImportPage />
        ) : currentView === 'quartz-jobs' ? (
          <QuartzJobsPage />
        ) : currentView === 'profile' ? (
          <ProfilePage />
        ) : currentView !== 'tickets' ? (
          <WipPlaceholder label={currentViewLabel} />
        ) : (
          <>
      <header className="app-header">
        <h1>Ticket List</h1>
      </header>

      <div className="filter-bar">
        <label className="filter-field">
          <span>Full text search</span>
          <input
            type="text"
            value={draft.search}
            onChange={(e) => setDraft((d) => ({ ...d, search: e.target.value }))}
            onKeyDown={handleFilterKeyDown}
            placeholder="Ticket #, Order #, customer, email…"
          />
        </label>

        <label className="filter-field">
          <span>Action</span>
          <select value={draft.action} onChange={(e) => setDraft((d) => ({ ...d, action: e.target.value }))}>
            <option value="">ALL</option>
            {ACTION_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>

        <label className="filter-field">
          <span>Status</span>
          <select value={draft.status} onChange={(e) => setDraft((d) => ({ ...d, status: e.target.value }))}>
            <option value="">ALL</option>
            {STATUS_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>

        <label className="filter-field">
          <span>Starred?</span>
          <select value={draft.starred} onChange={(e) => setDraft((d) => ({ ...d, starred: e.target.value }))}>
            <option value="">ALL</option>
            <option value="true">Starred</option>
            <option value="false">Not starred</option>
          </select>
        </label>

        <label className="filter-field">
          <span>Date Created After</span>
          <input
            type="date"
            value={draft.createdAfter}
            onChange={(e) => setDraft((d) => ({ ...d, createdAfter: e.target.value }))}
          />
        </label>

        <label className="filter-field">
          <span>Date Created Before</span>
          <input
            type="date"
            value={draft.createdBefore}
            onChange={(e) => setDraft((d) => ({ ...d, createdBefore: e.target.value }))}
          />
        </label>

        <label className="filter-field">
          <span>Handler</span>
          <input
            type="text"
            value={draft.handlerUsername}
            onChange={(e) => setDraft((d) => ({ ...d, handlerUsername: e.target.value }))}
            onKeyDown={handleFilterKeyDown}
            placeholder="Handler username"
          />
        </label>

        <label className="filter-field">
          <span>Sales Channel</span>
          <select value={draft.sourceSystem} onChange={(e) => setDraft((d) => ({ ...d, sourceSystem: e.target.value }))}>
            <option value="">ALL</option>
            {allowedSourceSystemOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>

        <button type="button" className="search-button" onClick={applyFilters}>Search</button>
      </div>

      <div className="toolbar-row">
        <div className="toolbar-left">
          <CreateTicketForm onCreated={() => setReloadToken((t) => t + 1)} allowedChannels={currentUser.allowedChannels} />
          <button
            type="button"
            className="export-button"
            onClick={handleExportReport}
            title="Download a CSV of ManualHandling tickets currently Processing"
          >
            📄 Export ticket report
          </button>
          <button
            type="button"
            className="export-button"
            onClick={handleExcelExport}
            disabled={excelExporting}
            title="Download every ticket currently matching the filters/search above as an Excel sheet"
          >
            {excelExporting ? 'Exporting…' : '📊 Excel export'}
          </button>
          {exportMessage && (
            <span className={`create-ticket-message${exportMessageFading ? ' fade-out' : ''}`}>
              {exportMessage}
            </span>
          )}
        </div>

        <PaginationControls
          page={page}
          totalPages={totalPages}
          totalElements={totalElements}
          pageSize={PAGE_SIZE}
          setPage={setPage}
          pageInput={pageInput}
          setPageInput={setPageInput}
          onJumpSubmit={handlePageJumpSubmit}
          leading={<ResetColumnsButton columnLayout={columnLayout} />}
        />
      </div>

      {error && <div className="error-banner">Couldn't load tickets: {error}</div>}

      <TicketTable
        tickets={tickets}
        loading={loading}
        error={error}
        columnLayout={columnLayout}
        sort={sort}
        onSort={toggleSort}
        scrollLocked={scrollLocked}
        onScrollLockedChange={setScrollLocked}
        onEdit={setEditingTicket}
        onCloseTicket={setClosingTicket}
        onAddComment={setCommentingTicket}
        onHistory={setHistoryTicket}
        onToggled={() => setReloadToken((t) => t + 1)}
        onOpenSalesOrder={handleOpenSalesOrder}
      />

      {!scrollLocked && (
        <>
          <PaginationControls
            page={page}
            totalPages={totalPages}
            totalElements={totalElements}
            pageSize={PAGE_SIZE}
            setPage={setPage}
            pageInput={pageInput}
            setPageInput={setPageInput}
            onJumpSubmit={handlePageJumpSubmit}
          />

          <footer className="app-footer">
            {totalElements} ticket{totalElements === 1 ? '' : 's'}
          </footer>
        </>
      )}

            {editingTicket && (
              <EditTicketModal
                ticket={editingTicket}
                onClose={(saved) => {
                  setEditingTicket(null)
                  if (saved) setReloadToken((t) => t + 1)
                }}
              />
            )}

            {closingTicket && (
              <CloseTicketModal
                ticket={closingTicket}
                onClose={(saved) => {
                  setClosingTicket(null)
                  if (saved) setReloadToken((t) => t + 1)
                }}
              />
            )}

            {commentingTicket && (
              <AddCommentModal
                ticket={commentingTicket}
                onClose={(saved) => {
                  setCommentingTicket(null)
                  if (saved) {
                    setReloadToken((t) => t + 1)
                    showToast('Comment added.')
                  }
                }}
              />
            )}

            {historyTicket && (
              <TicketHistoryModal
                ticket={historyTicket}
                onClose={() => setHistoryTicket(null)}
              />
            )}
          </>
        )}
      </div>
    </div>
  )
}

// Auth gate — the actual login/session check, kept entirely separate from MainApp so its
// ticket-fetching effects never even mount (let alone fire a request that would just 401)
// before a session exists. MainApp only ever renders once currentUser is set.
export default function App() {
  const [currentUser, setCurrentUser] = useState(null)
  const [authChecked, setAuthChecked] = useState(false)

  useEffect(() => {
    fetchCurrentUser()
      .then(setCurrentUser)
      .catch(() => setCurrentUser(null))
      .finally(() => setAuthChecked(true))
  }, [])

  function handleLogout() {
    logout()
      .catch(() => null)
      .then((redirectUrl) => {
        setCurrentUser(null)
        // Keycloak logout: the backend already invalidated the local session and handed back
        // Keycloak's own end-session URL — only a real browser navigation can carry us there.
        if (redirectUrl) {
          window.location.href = redirectUrl
        }
      })
  }

  if (!authChecked) {
    return null
  }

  if (!currentUser) {
    return <LoginPage onLoggedIn={setCurrentUser} />
  }

  return <MainApp currentUser={currentUser} onLogout={handleLogout} />
}
