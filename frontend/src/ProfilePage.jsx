import { useEffect, useState } from 'react'
import { changePassword, fetchProfile, updateProfile } from './profileApi.js'

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
  })
}

export default function ProfilePage() {
  const [profile, setProfile] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [editingContact, setEditingContact] = useState(false)
  const [email, setEmail] = useState('')
  const [telegramChatId, setTelegramChatId] = useState('')
  const [savingContact, setSavingContact] = useState(false)
  const [contactMessage, setContactMessage] = useState(null)

  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showNewPassword, setShowNewPassword] = useState(false)
  const [showConfirmPassword, setShowConfirmPassword] = useState(false)
  const [changingPassword, setChangingPassword] = useState(false)
  const [passwordMessage, setPasswordMessage] = useState(null)

  function reload() {
    setLoading(true)
    setError(null)
    fetchProfile()
      .then((p) => {
        setProfile(p)
        setEmail(p.email || '')
        setTelegramChatId(p.telegramChatId != null ? String(p.telegramChatId) : '')
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  function handleContactSubmit(e) {
    e.preventDefault()
    setSavingContact(true)
    setContactMessage(null)
    const trimmedTelegramChatId = telegramChatId.trim()
    updateProfile({
      email: email.trim() || null,
      telegramChatId: trimmedTelegramChatId === '' ? null : Number(trimmedTelegramChatId),
    })
      .then((p) => {
        setProfile(p)
        setEditingContact(false)
        setContactMessage('Saved.')
      })
      .catch((err) => setContactMessage(err.message))
      .finally(() => setSavingContact(false))
  }

  function handlePasswordSubmit(e) {
    e.preventDefault()
    setChangingPassword(true)
    setPasswordMessage(null)
    changePassword(newPassword, confirmPassword)
      .then(() => {
        setNewPassword('')
        setConfirmPassword('')
        setPasswordMessage('Password updated.')
      })
      .catch((err) => setPasswordMessage(err.message))
      .finally(() => setChangingPassword(false))
  }

  return (
    <>
      <header className="app-header">
        <h1>Profile</h1>
      </header>

      {error && <div className="error-banner">Couldn't load profile: {error}</div>}
      {loading && <p className="detail-empty">Loading…</p>}

      {!loading && profile && (
        <>
          <div className="detail-section">
            <div className="detail-grid">
              <div><span className="detail-label">Username</span>{profile.username}</div>
              <div><span className="detail-label">Password</span>••••••••</div>
              <div><span className="detail-label">Email</span>{profile.email || '—'}</div>
              <div><span className="detail-label">Telegram ID</span>{profile.telegramChatId || '—'}</div>
              <div><span className="detail-label">Account Created</span>{formatDate(profile.dateCreated)}</div>
            </div>
          </div>

          <section className="create-ticket">
            <div className="create-ticket-form">
              <span className="detail-label">Contact info</span>
              {editingContact ? (
                <form className="form-grid" onSubmit={handleContactSubmit}>
                  <label>
                    <span>Email</span>
                    <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
                  </label>
                  <label>
                    <span>Telegram ID</span>
                    <input
                      type="text"
                      inputMode="numeric"
                      value={telegramChatId}
                      onChange={(e) => setTelegramChatId(e.target.value)}
                    />
                  </label>
                  <div className="checkbox-field" style={{ alignSelf: 'end', gap: 8 }}>
                    <button type="submit" className="create-ticket-submit" disabled={savingContact}>
                      {savingContact ? 'Saving…' : 'Save'}
                    </button>
                    <button
                      type="button"
                      className="create-ticket-toggle"
                      onClick={() => {
                        setEditingContact(false)
                        setEmail(profile.email || '')
                        setTelegramChatId(profile.telegramChatId != null ? String(profile.telegramChatId) : '')
                      }}
                    >
                      Cancel
                    </button>
                  </div>
                </form>
              ) : (
                <button type="button" className="create-ticket-toggle" onClick={() => setEditingContact(true)}>
                  Edit email / Telegram ID
                </button>
              )}
              {contactMessage && <div className="create-ticket-message">{contactMessage}</div>}
            </div>
          </section>

          <section className="create-ticket">
            <div className="create-ticket-form">
              <span className="detail-label">Change password</span>
              <form className="form-grid" onSubmit={handlePasswordSubmit}>
                <label>
                  <span>New password</span>
                  <div className="password-field">
                    <input
                      type={showNewPassword ? 'text' : 'password'}
                      value={newPassword}
                      onChange={(e) => setNewPassword(e.target.value)}
                      minLength={6}
                      required
                    />
                    <button
                      type="button"
                      className="password-toggle"
                      onClick={() => setShowNewPassword((s) => !s)}
                      title={showNewPassword ? 'Hide password' : 'Show password'}
                    >
                      {showNewPassword ? '🙈' : '👁'}
                    </button>
                  </div>
                </label>
                <label>
                  <span>Re-enter password</span>
                  <div className="password-field">
                    <input
                      type={showConfirmPassword ? 'text' : 'password'}
                      value={confirmPassword}
                      onChange={(e) => setConfirmPassword(e.target.value)}
                      minLength={6}
                      required
                    />
                    <button
                      type="button"
                      className="password-toggle"
                      onClick={() => setShowConfirmPassword((s) => !s)}
                      title={showConfirmPassword ? 'Hide password' : 'Show password'}
                    >
                      {showConfirmPassword ? '🙈' : '👁'}
                    </button>
                  </div>
                </label>
                <div className="checkbox-field" style={{ alignSelf: 'end' }}>
                  <button type="submit" className="create-ticket-submit" disabled={changingPassword}>
                    {changingPassword ? 'Updating…' : 'Update password'}
                  </button>
                </div>
              </form>
              {passwordMessage && <div className="create-ticket-message">{passwordMessage}</div>}
            </div>
          </section>
        </>
      )}
    </>
  )
}
