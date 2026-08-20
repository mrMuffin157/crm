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

export async function fetchProfile() {
  const response = await apiFetch('/api/profile')
  if (!response.ok) throw new Error(await readError(response, 'Failed to load profile'))
  return response.json()
}

export async function updateProfile({ email, telegramChatId }) {
  const response = await apiFetch('/api/profile', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, telegramChatId }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to update profile'))
  return response.json()
}

export async function changePassword(newPassword, confirmPassword) {
  const response = await apiFetch('/api/profile/password', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newPassword, confirmPassword }),
  })
  if (!response.ok) throw new Error(await readError(response, 'Failed to update password'))
}
