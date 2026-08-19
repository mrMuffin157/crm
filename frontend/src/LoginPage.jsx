import { useEffect, useState } from 'react'
import { login, fetchAuthConfig } from './authApi.js'

function queryParam(name) {
  return new URLSearchParams(window.location.search).get(name)
}

export default function LoginPage({ onLoggedIn }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  // null while still checking /api/auth/config — render nothing rather than flashing the local
  // form before we know whether we're about to redirect to Keycloak.
  const [keycloakEnabled, setKeycloakEnabled] = useState(null)

  // ?local=1 is the break-glass escape hatch (mirrors legacy's params.boolean('local')) — skips
  // the config check entirely so it works even if Keycloak itself is unreachable.
  const forceLocal = queryParam('local') === '1'

  useEffect(() => {
    if (forceLocal) {
      setKeycloakEnabled(false)
      return
    }
    fetchAuthConfig()
      .then((config) => setKeycloakEnabled(config.keycloakEnabled))
      .catch(() => setKeycloakEnabled(false))
  }, [forceLocal])

  useEffect(() => {
    if (keycloakEnabled) {
      window.location.href = '/oauth2/authorization/keycloak'
    }
  }, [keycloakEnabled])

  useEffect(() => {
    if (queryParam('keycloakError') === '1') {
      setError('Your account is not authorized to use this app. Contact an admin.')
    }
  }, [])

  function handleSubmit(e) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    login(username, password)
      .then((user) => onLoggedIn(user))
      .catch((err) => setError(err.message))
      .finally(() => setSubmitting(false))
  }

  if (keycloakEnabled === null) {
    return null
  }

  if (keycloakEnabled) {
    return (
      <div className="login-screen">
        <div className="login-card">
          <div className="login-brand">🌐 CRM</div>
          <p className="login-subtitle">Redirecting to sign-in…</p>
          <a className="login-local-link" href="/?local=1">
            Sign in with username &amp; password instead
          </a>
        </div>
      </div>
    )
  }

  return (
    <div className="login-screen">
      <form className="login-card" onSubmit={handleSubmit}>
        <div className="login-brand">🌐 CRM</div>
        <p className="login-subtitle">Sign in to continue</p>
        <label className="login-field">
          <span>Username</span>
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoFocus
            autoComplete="username"
          />
        </label>
        <label className="login-field">
          <span>Password</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        {error && <div className="error-banner">{error}</div>}
        <button type="submit" className="login-submit" disabled={submitting}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}
