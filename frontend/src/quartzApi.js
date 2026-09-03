import { apiFetch } from './httpClient.js'

async function readError(response, fallback) {
  try {
    const body = await response.json()
    if (body && body.error) return body.error
  } catch {
    // response wasn't JSON — fall through to the generic message
  }
  return `${fallback} (${response.status})`
}

export async function fetchQuartzJobs() {
  const response = await apiFetch('/api/admin/quartz-jobs')
  if (!response.ok) throw new Error(await readError(response, 'Failed to load jobs'))
  return response.json()
}

export async function fetchTriggerDetail(triggerGroup, triggerName) {
  const response = await apiFetch(`/api/admin/quartz-jobs/triggers/${triggerGroup}/${triggerName}`)
  if (!response.ok) throw new Error(await readError(response, 'Failed to load trigger detail'))
  return response.json()
}

export async function pauseAllJobs() {
  const response = await apiFetch('/api/admin/quartz-jobs/pause-all', { method: 'POST' })
  if (!response.ok) throw new Error(await readError(response, 'Failed to pause all jobs'))
}

export async function resumeAllJobs() {
  const response = await apiFetch('/api/admin/quartz-jobs/resume-all', { method: 'POST' })
  if (!response.ok) throw new Error(await readError(response, 'Failed to resume all jobs'))
}

export async function pauseTrigger(triggerGroup, triggerName) {
  const response = await apiFetch(`/api/admin/quartz-jobs/triggers/${triggerGroup}/${triggerName}/pause`, { method: 'POST' })
  if (!response.ok) throw new Error(await readError(response, 'Failed to pause trigger'))
}

export async function resumeTrigger(triggerGroup, triggerName) {
  const response = await apiFetch(`/api/admin/quartz-jobs/triggers/${triggerGroup}/${triggerName}/resume`, { method: 'POST' })
  if (!response.ok) throw new Error(await readError(response, 'Failed to resume trigger'))
}

export async function triggerNow(triggerGroup, triggerName) {
  const response = await apiFetch(`/api/admin/quartz-jobs/triggers/${triggerGroup}/${triggerName}/trigger-now`, { method: 'POST' })
  if (!response.ok) throw new Error(await readError(response, 'Failed to trigger job'))
}

export async function rescheduleTrigger(triggerGroup, triggerName, cronExpression) {
  const response = await apiFetch(`/api/admin/quartz-jobs/triggers/${triggerGroup}/${triggerName}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cronExpression }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to reschedule trigger'))
}
