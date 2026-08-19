import { apiFetch } from './httpClient.js'

// Reads the backend's { "error": "..." } body when available, same pattern as api.js.
async function readError(response, fallback) {
  try {
    const body = await response.json()
    if (body && body.error) return body.error
  } catch {
    // response wasn't JSON — fall through to the generic message
  }
  return `${fallback} (${response.status})`
}

// Resolves to the current user ({ username, role }) if a session is already active, or null
// if not logged in — a 401 here is an expected, non-error outcome, not something to throw.
export async function fetchCurrentUser() {
  const response = await apiFetch('/api/auth/me')
  if (response.status === 401) return null
  if (!response.ok) throw new Error(`Failed to check login state (${response.status})`)
  return response.json()
}

// Unauthenticated on purpose — LoginPage needs this before anyone is logged in, to decide
// whether to render the local form or redirect straight to Keycloak.
export async function fetchAuthConfig() {
  const response = await apiFetch('/api/auth/config')
  if (!response.ok) throw new Error(`Failed to load auth config (${response.status})`)
  return response.json()
}

export async function login(username, password) {
  const response = await apiFetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (response.status === 401) throw new Error('Incorrect username or password')
  if (!response.ok) throw new Error(await readError(response, 'Login failed'))
  return response.json()
}

// When Keycloak is enabled, the backend has already invalidated the local session and hands
// back Keycloak's own end-session URL — a fetch() response can't carry the browser there
// itself, so the caller (App.jsx) is responsible for navigating once this resolves.
export async function logout() {
  const response = await apiFetch('/api/auth/logout', { method: 'POST' })
  if (!response.ok) return null
  const body = await response.json().catch(() => null)
  return body?.redirectUrl ?? null
}
