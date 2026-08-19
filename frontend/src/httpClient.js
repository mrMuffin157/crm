// Every API call needs the session cookie attached now that the backend requires login —
// same-origin requests (via the Vite dev proxy) already send cookies by default, but
// `credentials: 'include'` makes that explicit and keeps working if frontend/backend are
// ever served from genuinely different origins.
export function apiFetch(url, options = {}) {
  return fetch(url, { ...options, credentials: 'include' })
}
