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

export async function fetchUsers() {
  const response = await apiFetch('/api/admin/users')
  if (!response.ok) throw new Error(await readError(response, 'Failed to load users'))
  return response.json()
}

// Real s2user accounts with no app_user_role grant yet — the pool "grant access" picks from.
export async function fetchCandidates() {
  const response = await apiFetch('/api/admin/users/candidates')
  if (!response.ok) throw new Error(await readError(response, 'Failed to load candidates'))
  return response.json()
}

// No password here — logins live in s2user, which this only grants an existing real account
// access to. To mint a brand new login outright, see createUser below.
export async function grantAccess(username) {
  const response = await apiFetch('/api/admin/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to grant access'))
  return response.json()
}

// Creates a brand new s2user login outright, for when no existing employee account exists yet
// to grant access to.
export async function createUser(username, password) {
  const response = await apiFetch('/api/admin/users/create', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to create user'))
  return response.json()
}

// Admin-driven reset of an existing account's password — for a locked-out user who can't reach
// ProfileController's own self-service change because they can't log in at all.
export async function resetPassword(id, newPassword) {
  const response = await apiFetch(`/api/admin/users/${id}/password`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newPassword }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to reset password'))
}

export async function setUserRole(id, role) {
  const response = await apiFetch(`/api/admin/users/${id}/role`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to update role'))
  return response.json()
}

// Replaces a member's whole set of authorized ticket channels — an admin already has
// every channel implicitly and can't be given an explicit set here (the backend rejects it).
export async function setUserChannels(id, channels) {
  const response = await apiFetch(`/api/admin/users/${id}/channels`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ channels }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to update channels'))
  return response.json()
}

// Removes only the app_user_role grant — the underlying s2user account (a real employee) is
// never touched, and it reappears in the "grant access" candidate pool for later reinstatement.
// Member accounts only; demote an admin first.
export async function deactivateAccount(id) {
  const response = await apiFetch(`/api/admin/users/${id}`, { method: 'DELETE' })
  if (!response.ok) throw new Error(await readError(response, 'Failed to deactivate account'))
}

// Permanently deletes the account outright — both the app_user_role grant and the underlying
// s2user row. Not reversible. Member accounts only; demote an admin first.
export async function deleteAccount(id) {
  const response = await apiFetch(`/api/admin/users/${id}/permanent`, { method: 'DELETE' })
  if (!response.ok) throw new Error(await readError(response, 'Failed to delete account'))
}
