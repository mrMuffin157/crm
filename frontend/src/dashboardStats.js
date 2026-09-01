import { useEffect, useState } from 'react'
import { fetchTickets } from './api.js'
import { STATUS_NEW, STATUS_PROCESSING, STATUS_PROCESS_FAILED } from './ticketEnums.js'

// Ticket.status values, matching ticketEnums.js STATUS_LABELS: New/Processing (shown here as
// "In Progress")/ProcessFailed.
export const DASHBOARD_STATS = [
  { key: 'new', status: STATUS_NEW, label: 'New Tickets', className: 'stat-card-blue', sidebarClassName: 'sidebar-stat-blue', sidebarLabel: 'new tickets' },
  { key: 'processing', status: STATUS_PROCESSING, label: 'In Progress', className: 'stat-card-green', sidebarClassName: 'sidebar-stat-green', sidebarLabel: 'in progress' },
  { key: 'processFailed', status: STATUS_PROCESS_FAILED, label: 'Process Failed', className: 'stat-card-red', sidebarClassName: 'sidebar-stat-red', sidebarLabel: 'process failed' },
]

// Shared by DashboardPage and Sidebar's under-the-nav-item summary (App.jsx) so the two never
// drift and so navigating between pages doesn't re-fire these requests. Lives in its own module
// rather than DashboardPage.jsx so the app shell (App.jsx) doesn't have to import from a page.
export function useDashboardStats() {
  const [counts, setCounts] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    Promise.all(
      // size: 1 just to keep the response small — totalElements reflects the full matching
      // count regardless of page size.
      DASHBOARD_STATS.map((stat) => fetchTickets({ status: stat.status, page: 0, size: 1 })),
    )
      .then((results) => {
        if (cancelled) return
        setCounts(Object.fromEntries(DASHBOARD_STATS.map((stat, i) => [stat.key, results[i].totalElements])))
      })
      .catch((err) => {
        if (!cancelled) setError(err.message)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { counts, error }
}
