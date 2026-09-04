// Best-effort per-browser persistence for view preferences — the ticket table's own display
// toggles (zebra stripes, frozen-header scroll lock, the Description/Line Items collapse
// checkboxes) and the sidebar's collapsed state.
//
// These used to be plain useState, which meant they only lived as long as the component that
// owned them: TicketTable unmounts whenever the sidebar navigates away from the Tickets view
// (MainApp swaps that whole branch out — see App.jsx), so coming back silently reset every
// toggle to its hardcoded default, and a reload reset the rest. They're user preferences, so
// they belong next to the column widths/order in localStorage.
//
// Same contract as ticketDraftStorage.js and ticketColumns.jsx: localStorage can throw (private
// browsing, disabled storage, quota) and a lost toggle is a cosmetic degradation, never a reason
// to break the page — so a failed read falls back to the caller's default and a failed write is
// swallowed. The keys share the `ticket-table:` namespace the column layout already uses, and
// are deliberately shared by every table instance (the Tickets page and the Sales Order page's
// Linked Tickets table), exactly like the column widths/order are.
import { useEffect, useState } from 'react'

export const PREF_KEYS = {
  stripedRows: 'ticket-table:striped-rows',
  scrollLocked: 'ticket-table:scroll-locked',
  lineItemsCollapsed: 'ticket-table:line-items-collapsed',
  descriptionCollapsed: 'ticket-table:description-collapsed',
  sidebarCollapsed: 'app:sidebar-collapsed',
}

function readBoolean(key, fallback) {
  try {
    const raw = localStorage.getItem(key)
    // Only a stored boolean counts; anything else (absent, or written by an older/newer version
    // of this app) leaves the caller's own default in charge.
    return raw === 'true' ? true : raw === 'false' ? false : fallback
  } catch {
    return fallback
  }
}

function writeBoolean(key, value) {
  try {
    localStorage.setItem(key, String(value))
  } catch {
    // ignore — best-effort only
  }
}

// Drop-in replacement for useState(<boolean>): same [value, setValue] pair (functional updates
// included), with the value seeded from storage and written back on every change.
export function usePersistedToggle(key, fallback) {
  const [value, setValue] = useState(() => readBoolean(key, fallback))
  useEffect(() => { writeBoolean(key, value) }, [key, value])
  return [value, setValue]
}
