import { useEffect, useState } from 'react'
import { fetchQuartzJobs, fetchTriggerDetail, pauseAllJobs, resumeAllJobs } from './quartzApi.js'
import QuartzTriggerDetailModal from './QuartzTriggerDetailModal.jsx'

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

// Keyed by org.quartz.Trigger.TriggerState — deliberately a separate map from
// tableControls.jsx's STATUS_BADGE_CLASS, which is keyed to Ticket.status numeric codes and
// means something unrelated. Reuses the same underlying badge color classes (they're generic
// color tokens, not ticket-specific), just with a Quartz-specific key space.
const QUARTZ_STATUS_BADGE_CLASS = {
  NORMAL: 'badge-closed',
  PAUSED: 'badge-processing',
  COMPLETE: 'badge-neutral',
  ERROR: 'badge-warn',
  BLOCKED: 'badge-warn',
  NONE: 'badge-neutral',
}

export function QuartzStatusBadge({ status }) {
  if (!status) return <span className="badge badge-neutral">—</span>
  const badgeClass = QUARTZ_STATUS_BADGE_CLASS[status] || 'badge-neutral'
  return <span className={`badge ${badgeClass}`}>{status}</span>
}

// Admin-only Scheduled Jobs control page — lists every Quartz job/trigger registered
// with the app's Scheduler and lets an admin pause/resume/trigger-now/reschedule one, or pause/
// resume everything at once. Plain useState/useEffect + manual refresh, matching every other
// admin page here (MembersPage.jsx) rather than introducing this codebase's first polling loop.
export default function QuartzJobsPage() {
  const [jobs, setJobs] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [bulkBusy, setBulkBusy] = useState(false)
  const [bulkMessage, setBulkMessage] = useState(null)
  const [selectedTrigger, setSelectedTrigger] = useState(null)
  const [detailError, setDetailError] = useState(null)

  function reload() {
    setLoading(true)
    setError(null)
    fetchQuartzJobs()
      .then(setJobs)
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  function handleBulk(action, label) {
    setBulkBusy(true)
    setBulkMessage(null)
    action()
      .then(() => {
        setBulkMessage(`${label} succeeded.`)
        reload()
      })
      .catch((err) => setBulkMessage(err.message))
      .finally(() => setBulkBusy(false))
  }

  function openDetail(job) {
    setDetailError(null)
    fetchTriggerDetail(job.triggerGroup, job.triggerName)
      .then(setSelectedTrigger)
      .catch((err) => setDetailError(err.message))
  }

  function closeDetail(changed) {
    setSelectedTrigger(null)
    if (changed) reload()
  }

  return (
    <>
      <header className="app-header">
        <h1>Scheduled Jobs</h1>
      </header>

      <div className="toolbar-row">
        <div className="toolbar-left">
          <button type="button" className="member-action-button" disabled={bulkBusy} onClick={() => handleBulk(pauseAllJobs, 'Pause All')}>
            Pause All
          </button>
          <button type="button" className="member-action-button" disabled={bulkBusy} onClick={() => handleBulk(resumeAllJobs, 'Resume All')}>
            Resume All
          </button>
          <button type="button" className="member-action-button" disabled={loading} onClick={reload}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      {bulkMessage && <div className="create-ticket-message">{bulkMessage}</div>}
      {detailError && <div className="create-ticket-message">{detailError}</div>}
      {error && <div className="error-banner">Couldn't load jobs: {error}</div>}
      {loading && <p className="detail-empty">Loading…</p>}

      {!loading && !error && (
        jobs.length === 0 ? (
          <p className="detail-empty">No jobs registered.</p>
        ) : (
          <div className="table-scroll">
            <table className="detail-subtable quartz-jobs-table">
              <thead>
                <tr>
                  <th>Job Name</th>
                  <th>Trigger Name</th>
                  <th>Start Time</th>
                  <th>NextFireTime</th>
                  <th>Status</th>
                  <th>Type</th>
                  <th>Misfire</th>
                  <th>Schedule</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {jobs.map((job) => (
                  <tr key={`${job.triggerGroup}.${job.triggerName}`}>
                    <td>{job.jobGroup}.{job.jobName}</td>
                    <td>{job.triggerGroup}.{job.triggerName}</td>
                    <td>{formatDate(job.startTime)}</td>
                    <td>{formatDate(job.nextFireTime)}</td>
                    <td><QuartzStatusBadge status={job.status} /></td>
                    <td>{job.type}</td>
                    <td>{job.misfireInstruction}</td>
                    <td>{job.schedule}</td>
                    <td>
                      <button type="button" className="member-action-button" onClick={() => openDetail(job)}>
                        Details
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {selectedTrigger && <QuartzTriggerDetailModal trigger={selectedTrigger} onClose={closeDetail} />}
    </>
  )
}
