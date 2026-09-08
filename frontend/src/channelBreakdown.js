import { useEffect, useState } from 'react'
import { fetchChannelBreakdown } from './api.js'
import { STATUS_NEW, STATUS_PROCESSING, STATUS_PROCESS_FAILED, ACTION_REFUND } from './ticketEnums.js'

// Mirrors TicketController.BREAKDOWN_ACTION_NAMES — ManualHandling has no named export in
// ticketEnums.js the way ACTION_REFUND does, so it's spelled out here directly (matches
// ACTION_LABELS.ManualHandling).
const ACTION_MANUAL_HANDLING = 'ManualHandling'

// The 3 status breakdown widgets (New/In Progress/Process Failed, same set as
// dashboardStats.js's DASHBOARD_STATS) plus the 2 action breakdown widgets (Refund/Manual
// Handling) — each rendered as one channel table by ChannelBreakdownPage.
export const STATUS_BREAKDOWNS = [
  { key: STATUS_NEW, label: 'New' },
  { key: STATUS_PROCESSING, label: 'In Progress' },
  { key: STATUS_PROCESS_FAILED, label: 'Process Failed' },
]

// The backend counts these against Processing ("In Progress") tickets only — see
// TicketController's BREAKDOWN_ACTION_STATUS_CODE comment — so DashboardPage.jsx's link-through
// filters always include status: STATUS_PROCESSING alongside these, to match what was counted.
export const ACTION_BREAKDOWNS = [
  { key: ACTION_REFUND, label: 'Refund' },
  { key: ACTION_MANUAL_HANDLING, label: 'Manual Handling' },
]

// Fetched once and shared the same way useDashboardStats() is — lives in its own module so
// DashboardPage.jsx stays focused on rendering.
export function useChannelBreakdown() {
  const [breakdown, setBreakdown] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    fetchChannelBreakdown()
      .then((data) => {
        if (!cancelled) setBreakdown(data)
      })
      .catch((err) => {
        if (!cancelled) setError(err.message)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { breakdown, error }
}
