import { useState } from 'react'
import { DASHBOARD_STATS } from './dashboardStats.js'
import { STATUS_BREAKDOWNS, ACTION_BREAKDOWNS } from './channelBreakdown.js'
import { SOURCE_SYSTEM_LABELS, sourceSystemShortLabel, STATUS_PROCESSING } from './ticketEnums.js'
import WipPlaceholder from './WipPlaceholder.jsx'

// How many channel rows show before the list collapses behind a "N more" toggle. Only 8 real
// channels exist today (SOURCE_SYSTEM_LABELS), so this rarely bites yet, but the channel set
// grows over time (see TicketEnums.SOURCE_SYSTEM_CHANNELS's own comment on new storefronts), so
// the widget is built to stay usable past today's count rather than assuming it forever fits.
const VISIBLE_CHANNEL_LIMIT = 10

// Only the channels this card actually has tickets in, sorted by count descending. Zero rows
// are dropped rather than listed as 0: unlike ticketEnums.js's option lists (where the full
// enum matters because the user is picking from it), a 0 here is noise — nothing to click
// through to — and today 6 of the 8 channels are usually 0.
function channelRows(counts) {
  return Object.keys(SOURCE_SYSTEM_LABELS)
    .map((channel) => ({ channel, count: counts?.[channel] ?? 0 }))
    .filter(({ count }) => count > 0)
    .sort((a, b) => b.count - a.count)
}

// The action cards' own big number is the sum of their channel counts (unlike the status cards,
// whose totals come from DASHBOARD_STATS' own queries), so it has to be derivable outside the
// component below.
function channelTotal(counts) {
  return channelRows(counts).reduce((sum, row) => sum + row.count, 0)
}

// Fills the stat card's own empty space (to the right of its big number/label) with the
// per-channel breakdown, rather than a separate card next to it — no title and no Total row
// here, since the card's own big number already IS that total. Lives inside the same colored
// .stat-card box, so its text/link colors inherit that card's own color (see
// .stat-card-blue/-green/-red/-amber/-purple overriding color on the whole card, not just the
// value/label).
function StatCardChannelBreakdown({ counts, baseFilters, onFilterClick, linkTitle }) {
  const [expanded, setExpanded] = useState(false)
  const rows = channelRows(counts)
  const visibleRows = expanded ? rows : rows.slice(0, VISIBLE_CHANNEL_LIMIT)
  const hiddenCount = rows.length - visibleRows.length

  if (rows.length === 0) {
    return <div className="stat-card-breakdown stat-card-breakdown-empty">No tickets</div>
  }

  return (
    <div className="stat-card-breakdown">
      {visibleRows.map(({ channel, count }) => (
        <div className="stat-card-channel-row" key={channel}>
          <span className="stat-card-channel-name">{sourceSystemShortLabel(channel)}</span>
          <button
            type="button"
            className="link-button stat-card-channel-count"
            onClick={() => onFilterClick({ ...baseFilters, sourceSystem: channel })}
            title={`View ${linkTitle} tickets on ${sourceSystemShortLabel(channel)}`}
          >
            {count}
          </button>
        </div>
      ))}
      {hiddenCount > 0 && (
        <button type="button" className="link-button channel-breakdown-toggle" onClick={() => setExpanded(true)}>
          … {hiddenCount} more
        </button>
      )}
      {expanded && rows.length > VISIBLE_CHANNEL_LIMIT && (
        <button type="button" className="link-button channel-breakdown-toggle" onClick={() => setExpanded(false)}>
          Show less
        </button>
      )}
    </div>
  )
}

// One card shape for every dashboard widget — the 3 status cards and the 2 action cards — so
// the two sets can't drift apart in layout or behavior. The whole left side is one button
// (onMainClick, the unfiltered-by-channel view); every count on the right links to the same
// view narrowed to that one channel.
function StatCard({ className, value, label, onMainClick, counts, baseFilters, onFilterClick }) {
  return (
    <div className={`stat-card ${className}`}>
      <button type="button" className="stat-card-main" onClick={onMainClick} title={`View ${label} tickets`}>
        <div className="stat-card-value">{value}</div>
        <div className="stat-card-label">{label}</div>
      </button>
      <StatCardChannelBreakdown
        counts={counts}
        baseFilters={baseFilters}
        onFilterClick={onFilterClick}
        linkTitle={label}
      />
    </div>
  )
}

// Refund/Manual Handling have no DASHBOARD_STATS entry of their own, so their card color lives
// here alongside their ACTION_BREAKDOWNS key, the same way DASHBOARD_STATS carries className.
const ACTION_CARD_CLASSES = {
  [ACTION_BREAKDOWNS[0].key]: 'stat-card-amber',
  [ACTION_BREAKDOWNS[1].key]: 'stat-card-purple',
}

export default function DashboardPage({ stats, onStatClick, channelBreakdown, onFilterClick }) {
  const { counts, error } = stats
  const { breakdown, error: breakdownError } = channelBreakdown

  return (
    <div className="dashboard-page">
      <header className="app-header">
        <h1>Dashboard</h1>
      </header>

      {error && <div className="error-banner">Couldn't load dashboard stats: {error}</div>}
      {breakdownError && <div className="error-banner">Couldn't load channel breakdown: {breakdownError}</div>}

      {/* Same stat-card row as before — the channel breakdown lives inside each card's own
          whitespace (StatCardChannelBreakdown) rather than as a separate box next to or below
          it. DASHBOARD_STATS and STATUS_BREAKDOWNS share the same status codes (see
          channelBreakdown.js), so they're zipped together by that key. */}
      <div className="dashboard-stats">
        {DASHBOARD_STATS.map((stat) => {
          const breakdownItem = STATUS_BREAKDOWNS.find((item) => item.key === stat.status)
          return (
            <StatCard
              key={stat.key}
              className={stat.className}
              value={counts ? counts[stat.key] : '—'}
              label={stat.label}
              onMainClick={() => onStatClick(stat.status)}
              counts={breakdown?.byStatus?.[breakdownItem.key]}
              baseFilters={{ status: breakdownItem.key }}
              onFilterClick={onFilterClick}
            />
          )
        })}
      </div>

      {/* Scoped to Processing ("In Progress") tickets only — see
          TicketController#BREAKDOWN_ACTION_STATUS_CODE's own comment for why — so every filter
          these cards link through with carries that status alongside the action, matching what
          was counted. */}
      <h2 className="channel-breakdown-heading">In Progress Breakdown</h2>
      <div className="dashboard-stats">
        {ACTION_BREAKDOWNS.map((item) => {
          const actionCounts = breakdown?.byAction?.[item.key]
          return (
            <StatCard
              key={item.key}
              className={ACTION_CARD_CLASSES[item.key]}
              value={breakdown ? channelTotal(actionCounts) : '—'}
              label={item.label}
              onMainClick={() => onFilterClick({ action: item.key, status: STATUS_PROCESSING })}
              counts={actionCounts}
              baseFilters={{ action: item.key, status: STATUS_PROCESSING }}
              onFilterClick={onFilterClick}
            />
          )
        })}
      </div>

      <WipPlaceholder label="Dashboard" caption="More dashboard widgets are still under construction." />
    </div>
  )
}
