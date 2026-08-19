import { useEffect, useState } from 'react'
import { createUser, deactivateAccount, deleteAccount, fetchCandidates, fetchUsers, grantAccess, resetPassword, setUserChannels, setUserRole } from './adminApi.js'
import { SOURCE_SYSTEM_OPTIONS } from './ticketEnums.js'

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

function MemberRow({ user, currentUsername, onChanged }) {
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const [resetting, setResetting] = useState(false)
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [resetBusy, setResetBusy] = useState(false)
  const [resetMessage, setResetMessage] = useState(null)

  const [editingChannels, setEditingChannels] = useState(false)
  const [selectedChannels, setSelectedChannels] = useState(user.channels || [])
  const [channelsBusy, setChannelsBusy] = useState(false)
  const [channelsMessage, setChannelsMessage] = useState(null)

  function toggleChannel(value) {
    setSelectedChannels((current) =>
      current.includes(value) ? current.filter((c) => c !== value) : [...current, value])
  }

  function handleSaveChannels() {
    setChannelsBusy(true)
    setChannelsMessage(null)
    setUserChannels(user.id, selectedChannels)
      .then(() => {
        setEditingChannels(false)
        onChanged()
      })
      .catch((err) => setChannelsMessage(err.message))
      .finally(() => setChannelsBusy(false))
  }

  function handleResetSubmit(e) {
    e.preventDefault()
    if (newPassword !== confirmPassword) {
      setResetMessage('Passwords do not match')
      return
    }
    setResetBusy(true)
    setResetMessage(null)
    resetPassword(user.id, newPassword)
      .then(() => {
        setNewPassword('')
        setConfirmPassword('')
        setResetMessage('Password updated.')
      })
      .catch((err) => setResetMessage(err.message))
      .finally(() => setResetBusy(false))
  }

  function handleToggleRole() {
    const nextRole = user.role === 'ADMIN' ? 'MEMBER' : 'ADMIN'
    setError(null)
    setUserRole(user.id, nextRole)
      .then(onChanged)
      .catch((err) => setError(err.message))
  }

  function handleDeactivate() {
    if (!window.confirm(`Deactivate "${user.username}"'s access to this app? Their real account is untouched, and access can be granted again later.`)) return
    setError(null)
    setBusy(true)
    deactivateAccount(user.id)
      .then(onChanged)
      .catch((err) => setError(err.message))
      .finally(() => setBusy(false))
  }

  function handleDelete() {
    if (!window.confirm(`Permanently delete "${user.username}"'s account? This cannot be undone — their login will be gone entirely, not just their access to this app.`)) return
    setError(null)
    setBusy(true)
    deleteAccount(user.id)
      .then(onChanged)
      .catch((err) => setError(err.message))
      .finally(() => setBusy(false))
  }

  const isSelf = user.username === currentUsername
  const isAdmin = user.role === 'ADMIN'

  return (
    <>
      <tr>
        <td>{user.username}{isSelf && <span className="member-you-badge">you</span>}</td>
        <td>{user.name || user.email || '—'}</td>
        <td>{isAdmin ? 'All channels' : (user.channels?.length ? user.channels.join(', ') : 'None')}</td>
        <td>{formatDate(user.dateCreated)}</td>
        <td className="member-actions-cell">
          <button type="button" className="member-action-button" onClick={handleToggleRole}>
            {isAdmin ? 'Remove admin' : 'Make admin'}
          </button>
          {!isSelf && (
            <button type="button" className="member-action-button" onClick={() => setResetting((s) => !s)}>
              Reset password
            </button>
          )}
          {!isAdmin && (
            <button type="button" className="member-action-button" onClick={() => setEditingChannels((s) => !s)}>
              Edit channels
            </button>
          )}
          {!isAdmin && !isSelf && (
            <>
              <button type="button" className="member-action-button" onClick={handleDeactivate} disabled={busy}>
                Deactivate
              </button>
              <button type="button" className="member-action-button member-action-danger" onClick={handleDelete} disabled={busy}>
                Delete
              </button>
            </>
          )}
          {error && <span className="member-row-error">{error}</span>}
        </td>
      </tr>
      {editingChannels && (
        <tr>
          <td colSpan={5}>
            <div className="form-grid">
              <div className="member-channel-grid">
                {SOURCE_SYSTEM_OPTIONS.map((o) => {
                  const active = selectedChannels.includes(o.value)
                  return (
                    <button
                      key={o.value}
                      type="button"
                      className={`member-channel-toggle ${active ? 'member-channel-toggle-on' : 'member-channel-toggle-off'}`}
                      aria-pressed={active}
                      onClick={() => toggleChannel(o.value)}
                    >
                      {o.label}
                    </button>
                  )
                })}
              </div>
              <div className="checkbox-field" style={{ alignSelf: 'end', gap: 8 }}>
                <button type="button" className="member-channel-save" onClick={handleSaveChannels} disabled={channelsBusy}>
                  {channelsBusy ? 'Saving…' : 'Save channels'}
                </button>
                <button
                  type="button"
                  className="member-channel-cancel"
                  onClick={() => {
                    setEditingChannels(false)
                    setSelectedChannels(user.channels || [])
                    setChannelsMessage(null)
                  }}
                >
                  Cancel
                </button>
              </div>
              {channelsMessage && <div className="create-ticket-message">{channelsMessage}</div>}
            </div>
          </td>
        </tr>
      )}
      {resetting && (
        <tr>
          <td colSpan={5}>
            <form className="form-grid" onSubmit={handleResetSubmit}>
              <label>
                <span>New password for "{user.username}"</span>
                <div className="password-field">
                  <input
                    type={showPassword ? 'text' : 'password'}
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    minLength={6}
                    required
                  />
                  <button
                    type="button"
                    className="password-toggle"
                    onClick={() => setShowPassword((s) => !s)}
                    title={showPassword ? 'Hide password' : 'Show password'}
                  >
                    {showPassword ? '🙈' : '👁'}
                  </button>
                </div>
              </label>
              <label>
                <span>Re-enter password</span>
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  minLength={6}
                  required
                />
              </label>
              <div className="checkbox-field" style={{ alignSelf: 'end', gap: 8 }}>
                <button type="submit" className="create-ticket-submit" disabled={resetBusy}>
                  {resetBusy ? 'Updating…' : 'Update password'}
                </button>
                <button
                  type="button"
                  className="create-ticket-toggle"
                  onClick={() => {
                    setResetting(false)
                    setNewPassword('')
                    setConfirmPassword('')
                    setResetMessage(null)
                  }}
                >
                  Cancel
                </button>
              </div>
              {resetMessage && <div className="create-ticket-message">{resetMessage}</div>}
            </form>
          </td>
        </tr>
      )}
    </>
  )
}

function UserTable({ title, users, currentUsername, onChanged, emptyLabel }) {
  return (
    <div className="detail-section">
      <span className="detail-label">{title} ({users.length})</span>
      {users.length === 0 ? (
        <p className="detail-empty">{emptyLabel}</p>
      ) : (
        <div className="table-scroll">
          <table className="detail-subtable member-table">
            <thead>
              <tr><th>Username</th><th>Name / Email</th><th>Channels</th><th>Granted</th><th>Actions</th></tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <MemberRow key={user.id} user={user} currentUsername={currentUsername} onChanged={onChanged} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

export default function MembersPage({ currentUsername }) {
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [candidates, setCandidates] = useState([])
  const [selectedUsername, setSelectedUsername] = useState('')
  const [grantMessage, setGrantMessage] = useState(null)
  const [granting, setGranting] = useState(false)

  const [newUsername, setNewUsername] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [showNewPassword, setShowNewPassword] = useState(false)
  const [createMessage, setCreateMessage] = useState(null)
  const [creating, setCreating] = useState(false)

  function reload() {
    setLoading(true)
    setError(null)
    Promise.all([fetchUsers(), fetchCandidates()])
      .then(([userList, candidateList]) => {
        setUsers(userList)
        setCandidates(candidateList)
        setSelectedUsername((current) => (candidateList.some((c) => c.username === current) ? current : ''))
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  function handleGrantSubmit(e) {
    e.preventDefault()
    if (!selectedUsername) return
    setGranting(true)
    setGrantMessage(null)
    grantAccess(selectedUsername)
      .then((user) => {
        setGrantMessage(`Granted "${user.username}" access as a member.`)
        setSelectedUsername('')
        reload()
      })
      .catch((err) => setGrantMessage(err.message))
      .finally(() => setGranting(false))
  }

  function handleCreateSubmit(e) {
    e.preventDefault()
    setCreating(true)
    setCreateMessage(null)
    createUser(newUsername, newPassword)
      .then((user) => {
        setCreateMessage(`Created "${user.username}" as a new member.`)
        setNewUsername('')
        setNewPassword('')
        reload()
      })
      .catch((err) => setCreateMessage(err.message))
      .finally(() => setCreating(false))
  }

  const admins = users.filter((u) => u.role === 'ADMIN')
  const members = users.filter((u) => u.role === 'MEMBER')

  return (
    <>
      <header className="app-header">
        <h1>Members</h1>
      </header>

      <section className="create-ticket">
        <div className="create-ticket-form">
          <span className="detail-label">Grant access to an existing account</span>
          <form className="form-grid" onSubmit={handleGrantSubmit}>
            <label>
              <span>Employee account</span>
              <select value={selectedUsername} onChange={(e) => setSelectedUsername(e.target.value)} required>
                <option value="">Select…</option>
                {candidates.map((c) => (
                  <option key={c.id} value={c.username}>
                    {c.username}{c.name ? ` — ${c.name}` : c.email ? ` — ${c.email}` : ''}
                  </option>
                ))}
              </select>
            </label>
            <div className="checkbox-field" style={{ alignSelf: 'end' }}>
              <button type="submit" className="create-ticket-submit" disabled={granting || !selectedUsername}>
                {granting ? 'Granting…' : 'Grant access'}
              </button>
            </div>
          </form>
          {candidates.length === 0 && !loading && (
            <p className="detail-empty">Every real account already has access.</p>
          )}
          {grantMessage && <div className="create-ticket-message">{grantMessage}</div>}
        </div>
      </section>

      <section className="create-ticket">
        <div className="create-ticket-form">
          <span className="detail-label">Create a new user</span>
          <form className="form-grid" onSubmit={handleCreateSubmit}>
            <label>
              <span>Username</span>
              <input
                type="text"
                value={newUsername}
                onChange={(e) => setNewUsername(e.target.value)}
                required
              />
            </label>
            <label>
              <span>Password</span>
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
            <div className="checkbox-field" style={{ alignSelf: 'end' }}>
              <button type="submit" className="create-ticket-submit" disabled={creating || !newUsername || !newPassword}>
                {creating ? 'Creating…' : 'Create user'}
              </button>
            </div>
          </form>
          {createMessage && <div className="create-ticket-message">{createMessage}</div>}
        </div>
      </section>

      {error && <div className="error-banner">Couldn't load members: {error}</div>}
      {loading && <p className="detail-empty">Loading…</p>}

      {!loading && (
        <>
          <UserTable title="Admins" users={admins} currentUsername={currentUsername} onChanged={reload} emptyLabel="No admins." />
          <UserTable title="Members" users={members} currentUsername={currentUsername} onChanged={reload} emptyLabel="No members yet." />
        </>
      )}
    </>
  )
}
