import { useState } from 'react'
import { pauseTrigger, resumeTrigger, rescheduleTrigger, triggerNow } from './quartzApi.js'

// Small local copy of QuartzJobsPage's QuartzStatusBadge — not imported from there, to avoid a
// circular import between the page and this modal (the page also imports this modal).
const QUARTZ_STATUS_BADGE_CLASS = {
  NORMAL: 'badge-closed',
  PAUSED: 'badge-processing',
  COMPLETE: 'badge-neutral',
  ERROR: 'badge-warn',
  BLOCKED: 'badge-warn',
  NONE: 'badge-neutral',
}

function QuartzStatusBadge({ status }) {
  if (!status) return <span className="badge badge-neutral">—</span>
  const badgeClass = QUARTZ_STATUS_BADGE_CLASS[status] || 'badge-neutral'
  return <span className={`badge ${badgeClass}`}>{status}</span>
}

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

// Mirrors CloseTicketModal's structure (backdrop/header/actions, click-outside-to-close) — a
// focused single-record action panel for one trigger, matching the Job Manager "Trigger Detail"
// mockup: Job Key / Trigger Key / Description / Priority / Start-End Time / an editable
// cronExpression field / Status, plus Trigger Now / Pause / Resume / Re-Schedule.
export default function QuartzTriggerDetailModal({ trigger, onClose }) {
  const [cronExpression, setCronExpression] = useState(trigger.cronExpression || '')
  const [message, setMessage] = useState(null)
  const [busy, setBusy] = useState(false)

  const paused = trigger.status === 'PAUSED'

  function run(action) {
    setBusy(true)
    setMessage(null)
    action()
      .then(() => onClose?.(true))
      .catch((err) => setMessage(err.message))
      .finally(() => setBusy(false))
  }

  function handleReschedule(e) {
    e.preventDefault()
    run(() => rescheduleTrigger(trigger.triggerGroup, trigger.triggerName, cronExpression))
  }

  return (
    <div className="modal-backdrop" onClick={() => onClose?.(false)}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Trigger Detail</h2>
          <button type="button" className="modal-close" onClick={() => onClose?.(false)}>×</button>
        </div>

        <div className="form-grid">
          <label>
            <span>Job Key</span>
            <input type="text" value={`${trigger.jobGroup}.${trigger.jobName}`} readOnly />
          </label>
          <label>
            <span>Trigger Key</span>
            <input type="text" value={`${trigger.triggerGroup}.${trigger.triggerName}`} readOnly />
          </label>
        </div>

        <div className="form-grid">
          <label>
            <span>Description</span>
            <input type="text" value={trigger.description || '—'} readOnly />
          </label>
          <label>
            <span>Priority</span>
            <input type="text" value={trigger.priority} readOnly />
          </label>
        </div>

        <div className="form-grid">
          <label>
            <span>Start Time</span>
            <input type="text" value={formatDate(trigger.startTime)} readOnly />
          </label>
          <label>
            <span>End Time</span>
            <input type="text" value={formatDate(trigger.endTime)} readOnly />
          </label>
        </div>

        <form onSubmit={handleReschedule}>
          <label className="description-field">
            <span>cronExpression</span>
            <input type="text" value={cronExpression} onChange={(e) => setCronExpression(e.target.value)} required />
          </label>

          <div className="form-grid">
            <label>
              <span>Status</span>
              <div><QuartzStatusBadge status={trigger.status} /></div>
            </label>
          </div>

          {message && <div className="create-ticket-message">{message}</div>}

          <div className="modal-actions">
            <button
              type="button"
              className="member-action-button"
              disabled={busy}
              onClick={() => run(() => triggerNow(trigger.triggerGroup, trigger.triggerName))}
            >
              Trigger Now
            </button>
            {paused ? (
              <button
                type="button"
                className="member-action-button"
                disabled={busy}
                onClick={() => run(() => resumeTrigger(trigger.triggerGroup, trigger.triggerName))}
              >
                Resume
              </button>
            ) : (
              <button
                type="button"
                className="member-action-button"
                disabled={busy}
                onClick={() => run(() => pauseTrigger(trigger.triggerGroup, trigger.triggerName))}
              >
                Pause
              </button>
            )}
            <button type="submit" className="create-ticket-submit" disabled={busy}>
              {busy ? 'Working…' : 'Re-Schedule'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
