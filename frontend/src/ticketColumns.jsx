// Ticket-table column layout: which of the 9 content columns exist, what order they're in, and
// how wide each one is — all of it user-adjustable (drag a header divider to resize, right-click
// a header to reorder) and persisted per-browser in localStorage.
//
// The 3 leading icon columns (edit/star/bulb, close/cancel, comments) are deliberately NOT part
// of this model: they're fixed-pixel, always-first, never reorderable, so the row's action
// buttons never move out from under the mouse. Their widths live in LEADING_COLUMN_WIDTHS below.
//
// Widths are real pixels, not percentages: a percentage column can't be dragged to an exact size
// (the browser redistributes leftover space across every column), so the whole table is sized
// from a <colgroup> of px widths plus one unsized trailing spacer column that soaks up whatever
// slack is left in a wide viewport. See the .ticket-table colgroup notes in index.css.

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { SortIcon } from './tableControls.jsx'

// Anything narrower than this is unusable (the resize handle alone is 7px), so a drag clamps
// here rather than letting a column be dragged away to nothing.
export const MIN_COLUMN_WIDTH = 30

// Fixed, non-reorderable icon columns, in their fixed order. Sized to their actual content —
// measured, not guessed: the widest thing in each is either the body row's icon group
// (✎ ★ 💡 needs 79px) or the header toggle (🦓 / ↕️🔒). The old percentage widths were ~20px
// narrower than that and quietly clipped the icons.
export const LEADING_COLUMN_WIDTHS = [
  { key: 'quickActions', className: 'col-quick-actions', width: 80 },
  { key: 'lockActions', className: 'col-lock-actions', width: 62 },
  { key: 'comments', className: 'col-comments', width: 40 },
]

// The reorderable/resizable columns, in their default order.
//
// `width` is a RELATIVE WEIGHT until the column is dragged, not a final pixel count: an
// untouched column is laid out as its share of whatever space the measured container actually
// has (see resolveColumnWidths), so the default table fills the window exactly the way the old
// percentage widths did — a fixed pixel default can't, and leaves a dead gap a third of the
// table wide on a 1750px screen. The weights are those old percentages (which summed to 75.5%)
// expressed at a 1200px table, so the default proportions are unchanged from before this was
// resizable; # and Stat are the two nudged above their share, both having been too narrow for
// their own content (a 5-digit id, "In Prog"). A dragged column drops out of that sharing and
// holds its exact pixels —
// the live value comes from the stored widths map. `sortField` is the backend sort key
// (TicketRepositoryImpl.SORT_COLUMNS); null means the column isn't sortable.
export const TICKET_COLUMNS = [
  { key: 'id', label: '#', sortField: 'id', width: 58, cellClassName: 'col-id' },        /* was 3% */
  { key: 'reason', label: 'Reason', sortField: 'reason', width: 83, cellClassName: 'col-reason' },        /* was 5.5% */
  { key: 'description', label: 'Description', sortField: null, width: 260, cellClassName: 'col-description' },  /* was 18% */
  { key: 'orderNumber', label: 'Order #', sortField: 'orderNumber', width: 128, cellClassName: 'col-order' },      /* was 8.5% */
  { key: 'lineItems', label: 'Line Items', sortField: null, width: 220, cellClassName: 'col-line-items' },     /* was 15% */
  { key: 'source', label: 'Source', sortField: 'state', width: 58, cellClassName: 'col-source' },        /* was 4% */
  { key: 'action', label: 'Action', sortField: 'action', width: 60, cellClassName: 'col-action' },        /* was 4% */
  { key: 'status', label: 'Stat', sortField: 'status', width: 45, cellClassName: 'col-status' },        /* was 3% */
  { key: 'lastUpdated', label: 'Updated', sortField: 'lastUpdated', width: 106, cellClassName: 'col-id-updated' },   /* was 7% */
]

const LEADING_TOTAL = LEADING_COLUMN_WIDTHS.reduce((sum, c) => sum + c.width, 0)
const WEIGHT_TOTAL = TICKET_COLUMNS.reduce((sum, c) => sum + c.width, 0)
const COLUMN_BY_KEY = new Map(TICKET_COLUMNS.map((c) => [c.key, c]))
const DEFAULT_ORDER = TICKET_COLUMNS.map((c) => c.key)

// Same best-effort contract as ticketDraftStorage.js: localStorage can throw (private browsing,
// disabled storage, quota) and a lost table layout is a cosmetic degradation, never a reason to
// break the page — so every read falls back to the defaults and every write swallows its error.
const ORDER_KEY = 'ticket-table:column-order'
const WIDTHS_KEY = 'ticket-table:column-widths'

function readStored(key) {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function writeStored(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // ignore — best-effort only
  }
}

// A stored order is only ever a hint: unknown keys are dropped and columns added to
// TICKET_COLUMNS since it was written are appended, so shipping a new column never leaves
// someone stuck with a stale layout that hides it.
function normalizeOrder(stored) {
  if (!Array.isArray(stored)) return DEFAULT_ORDER
  const known = stored.filter((key) => COLUMN_BY_KEY.has(key))
  const seen = new Set(known)
  return [...new Set(known), ...DEFAULT_ORDER.filter((key) => !seen.has(key))]
}

function normalizeWidths(stored) {
  if (!stored || typeof stored !== 'object') return {}
  const widths = {}
  for (const column of TICKET_COLUMNS) {
    const value = stored[column.key]
    if (typeof value === 'number' && Number.isFinite(value)) {
      widths[column.key] = Math.max(MIN_COLUMN_WIDTH, Math.round(value))
    }
  }
  return widths
}

// Turns the stored widths into the pixel width every column is actually laid out at.
//
// A column the user has dragged is PINNED: it keeps its exact stored pixels, full stop. Every
// column that hasn't been dragged shares whatever space is left over in proportion to its
// default weight, so the table always ends up exactly as wide as its container — no dead gap on
// a wide window, no squeezing on a narrow one until the weights bottom out at MIN_COLUMN_WIDTH
// (past that the table overflows and .table-container scrolls sideways, which is the only
// honest option left). `containerWidth` of 0 means "not measured yet" — first paint falls back
// to the raw weights, and the ResizeObserver corrects it before anything visible happens.
function resolveColumnWidths(order, widths, containerWidth) {
  const columns = order.map((key) => COLUMN_BY_KEY.get(key))
  const fluid = columns.filter((c) => widths[c.key] == null)
  const weightTotal = fluid.reduce((sum, c) => sum + c.width, 0)
  const pinnedTotal = columns.reduce((sum, c) => sum + (widths[c.key] ?? 0), 0)
  const available = containerWidth - LEADING_TOTAL - pinnedTotal
  // With slack left over by the pinned columns, the fluid ones divide it up and the table lands
  // exactly on the container's width. With none left — every column dragged out past the window,
  // or one column un-pinned by a double-click on its divider while its dragged-out neighbours
  // stay put — there is nothing to divide, and the raw weight is the wrong answer: it's a width
  // from a 1200px table, so the column visibly collapses to a third of its size. Fall back to
  // the column's default share of the whole container instead, i.e. the width a full reset would
  // have given it at this window size.
  const defaultScale = containerWidth > 0 ? Math.max(1, (containerWidth - LEADING_TOTAL) / WEIGHT_TOTAL) : 1
  const scale = containerWidth > 0 && weightTotal > 0 && available > 0 ? available / weightTotal : defaultScale

  const resolved = columns.map((column) => ({
    ...column,
    width: widths[column.key] ?? Math.max(MIN_COLUMN_WIDTH, Math.round(column.width * scale)),
    pinned: widths[column.key] != null,
  }))

  // Rounding leaves a pixel or two on the table either way; hand it to the widest fluid column
  // so the total lands exactly on the container's width instead of the spacer showing a sliver
  // (or the table overflowing by 3px and putting a scrollbar under a table that fits).
  const widest = resolved.filter((c) => !c.pinned).sort((a, b) => b.width - a.width)[0]
  if (widest && available > 0) {
    const drift = available - resolved.reduce((sum, c) => sum + (c.pinned ? 0 : c.width), 0)
    widest.width = Math.max(MIN_COLUMN_WIDTH, widest.width + drift)
  }
  return resolved
}

export function useTicketColumnLayout() {
  const [order, setOrder] = useState(() => normalizeOrder(readStored(ORDER_KEY)))
  const [widths, setWidths] = useState(() => normalizeWidths(readStored(WIDTHS_KEY)))
  // A resize drag ends with a `click` that would otherwise reach the header cell and toggle its
  // sort. The handle stops its own clicks, but a drag that ends off the handle retargets the
  // click at the <th> itself — so the header also checks this timestamp before sorting.
  const resizeEndedAt = useRef(0)
  // Set for the duration of a resize drag. The header cell is HTML5-draggable for reordering,
  // and a pointer press that lands on the resize handle must not also start that drag —
  // dragstart checks this rather than relying on the handle's own preventDefault, which browsers
  // honour inconsistently.
  const resizing = useRef(false)
  // The scroll container the table lives in — its width is what the undragged columns divide
  // up, so it has to be measured rather than assumed (a window resize re-divides them).
  const [containerWidth, setContainerWidth] = useState(0)
  const observerRef = useRef(null)

  useEffect(() => { writeStored(ORDER_KEY, order) }, [order])
  useEffect(() => { writeStored(WIDTHS_KEY, widths) }, [widths])

  // A callback ref, not a plain ref read once from a mount effect: the element this observes
  // outlives neither of the two tables that own it. The Tickets page's table unmounts and
  // remounts every time the sidebar navigates away and back (MainApp — and this hook with it —
  // stays mounted the whole time, see App.jsx), and the Sales Order page's Linked Tickets table
  // only appears once its order has loaded. A one-shot effect would keep observing the FIRST
  // node forever: removing it from the DOM makes ResizeObserver report the now-detached element
  // as 0 wide, so containerWidth sticks at 0, and on the way back every undragged column falls
  // back to its raw weight — a ~1200px table sitting in a 1750px window until a full reload.
  // Re-pointing the observer at whatever node is currently mounted is what keeps the measured
  // width live across those remounts.
  const containerRef = useCallback((node) => {
    observerRef.current?.disconnect()
    observerRef.current = null
    if (!node) return
    // Seeded synchronously from the node itself rather than left to the observer: a
    // ResizeObserver delivers even its first observation asynchronously, and delivers nothing at
    // all while the tab isn't rendering frames (a background tab, a hidden window), so a mount
    // or remount that happens then would sit on containerWidth 0 — and lay every undragged
    // column out at its raw weight — until something else resized. clientWidth is the same
    // content-box width the observer reports (this container has a border but no padding), just
    // available immediately.
    setContainerWidth(node.clientWidth)
    const observer = new ResizeObserver(([entry]) => {
      const width = Math.round(entry.contentRect.width)
      // A width of 0 only ever means "not laid out / no longer in the document", never a real
      // measurement worth keeping — writing it would undo the last good one.
      if (width > 0) setContainerWidth(width)
    })
    observer.observe(node)
    observerRef.current = observer
  }, [])

  const columns = resolveColumnWidths(order, widths, containerWidth)

  const setColumnWidth = useCallback((key, width) => {
    setWidths((current) => ({ ...current, [key]: Math.max(MIN_COLUMN_WIDTH, Math.round(width)) }))
  }, [])

  // Moves `key` to sit immediately before `beforeKey`, or to the very end when it's null.
  const moveColumnBefore = useCallback((key, beforeKey) => {
    setOrder((current) => {
      const without = current.filter((k) => k !== key)
      const target = beforeKey == null ? without.length : without.indexOf(beforeKey)
      if (target < 0) return current
      without.splice(target, 0, key)
      return without
    })
  }, [])

  const nudgeColumn = useCallback((key, delta) => {
    setOrder((current) => {
      const from = current.indexOf(key)
      const to = from + delta
      if (from < 0 || to < 0 || to >= current.length) return current
      const next = [...current]
      next.splice(to, 0, ...next.splice(from, 1))
      return next
    })
  }, [])

  // Drives the Reset columns control's disabled state — "nothing has been customized" is worth
  // showing, since a layout only differs from the default if the user dragged something.
  const isDefaultLayout =
    Object.keys(widths).length === 0 && order.every((key, i) => key === DEFAULT_ORDER[i])

  const resetLayout = useCallback(() => {
    setOrder(DEFAULT_ORDER)
    setWidths({})
  }, [])

  const resetColumnWidth = useCallback((key) => {
    setWidths((current) => {
      const next = { ...current }
      delete next[key]
      return next
    })
  }, [])

  // Drag-to-reorder. The ref is the source of truth for WHICH column is in flight and the state
  // is only what the header cells render from (the faded cell, the drop indicator's edge): a
  // dragover or drop can arrive in the same tick as the dragstart that started it, before React
  // has re-rendered, so reading the dragged key out of state would drop the reorder on the
  // floor. Plain functions rather than useCallback — they close over `order`, which changes on
  // every reorder, and the 9 header cells re-render on drag state changes anyway.
  const dragKey = useRef(null)
  const [dragState, setDragState] = useState(null)

  function beginDrag(key) {
    dragKey.current = key
    setDragState({ key, overKey: null, side: 'before' })
  }

  function updateDragOver(overKey, side) {
    const key = dragKey.current
    if (!key) return
    setDragState((current) => {
      if (current && current.overKey === overKey && current.side === side) return current
      return { key, overKey, side }
    })
  }

  function endDrag() {
    dragKey.current = null
    setDragState(null)
  }

  // Dropping on a column's right half means "after it", which is the same thing as "before
  // whatever currently follows it" — and before nothing at all when it's the last column.
  function dropOnColumn(overKey, side) {
    const dragged = dragKey.current
    endDrag()
    if (!dragged || dragged === overKey) return
    if (side === 'before') {
      moveColumnBefore(dragged, overKey)
      return
    }
    const next = order[order.indexOf(overKey) + 1]
    if (next === dragged) return
    moveColumnBefore(dragged, next ?? null)
  }

  // Pointer-driven resize. The first drag PINS every column at the width it's currently laid
  // out at, not just the one being dragged: otherwise widening one column would silently steal
  // the pixels back out of its still-fluid neighbours, which reads as the whole table lurching
  // instead of one divider moving. From then on the table is all exact pixels and grows past
  // the container (scrolling) rather than redistributing — "Reset widths & order" puts every
  // column back to sharing the window. Start width comes from the live <th> for the same
  // reason: it's the width on screen, which a never-dragged column has no stored value for.
  const startResize = useCallback((event, key) => {
    event.preventDefault()
    event.stopPropagation()
    const header = event.currentTarget.closest('th')
    const startX = event.clientX
    const startWidth = header ? header.offsetWidth : MIN_COLUMN_WIDTH
    document.body.classList.add('column-resizing')
    resizing.current = true

    const row = header?.closest('tr')
    if (row) {
      const snapshot = {}
      for (const cell of row.querySelectorAll('th[data-column-key]')) {
        snapshot[cell.dataset.columnKey] = cell.offsetWidth
      }
      setWidths((current) => ({ ...snapshot, ...current }))
    }

    function handleMove(moveEvent) {
      setColumnWidth(key, startWidth + moveEvent.clientX - startX)
    }
    function handleUp() {
      window.removeEventListener('pointermove', handleMove)
      window.removeEventListener('pointerup', handleUp)
      window.removeEventListener('pointercancel', handleUp)
      document.body.classList.remove('column-resizing')
      resizing.current = false
      resizeEndedAt.current = Date.now()
    }
    window.addEventListener('pointermove', handleMove)
    window.addEventListener('pointerup', handleUp)
    window.addEventListener('pointercancel', handleUp)
  }, [setColumnWidth])

  const isSortSuppressed = useCallback(() => Date.now() - resizeEndedAt.current < 250, [])

  // Sets the table's min-width, so a table dragged wider than its container scrolls sideways
  // instead of being squeezed back. In the all-fluid default this equals the container width,
  // which is a no-op.
  const totalWidth = LEADING_TOTAL + columns.reduce((sum, c) => sum + c.width, 0)

  return {
    columns,
    containerRef,
    totalWidth,
    startResize,
    resizing,
    dragKey,
    dragState,
    beginDrag,
    updateDragOver,
    endDrag,
    dropOnColumn,
    resetColumnWidth,
    isDefaultLayout,
    isSortSuppressed,
    moveColumnBefore,
    nudgeColumn,
    resetLayout,
  }
}

// Shared by every page that embeds the ticket table (Tickets page, Sales Order page's Linked
// Tickets) — each owns its own useTicketColumnLayout() instance, but since both read/write the
// same ORDER_KEY/WIDTHS_KEY, this button resets whichever one the caller passes in identically.
export function ResetColumnsButton({ columnLayout }) {
  return (
    <button
      type="button"
      className="reset-columns-button"
      onClick={columnLayout.resetLayout}
      disabled={columnLayout.isDefaultLayout}
      title={
        columnLayout.isDefaultLayout
          ? 'Column widths and order are already at their defaults'
          : 'Put every column back to its default width and position'
      }
    >
      ↺ Reset columns
    </button>
  )
}

// The px widths every column in the table is actually laid out from (table-layout: fixed reads
// these and nothing else). The trailing spacer <col> is intentionally unsized: it absorbs any
// slack when the viewport is wider than the columns' combined width, so the real columns keep
// the exact pixel widths they were dragged to instead of being stretched proportionally.
export function TicketColumnGroup({ columns }) {
  return (
    <colgroup>
      {LEADING_COLUMN_WIDTHS.map((c) => <col key={c.key} style={{ width: c.width }} />)}
      {columns.map((c) => <col key={c.key} style={{ width: c.width }} />)}
      <col className="ticket-col-spacer" />
    </colgroup>
  )
}

export function TicketColumnHeader({ column, sort, onSort, layout, onOpenMenu, extra }) {
  const sortable = Boolean(column.sortField)
  const active = sortable && sort.field === column.sortField
  const { dragState } = layout
  const dragging = dragState?.key === column.key
  const dropSide = dragState && dragState.overKey === column.key && !dragging ? dragState.side : null

  function handleClick() {
    if (!sortable || layout.isSortSuppressed()) return
    onSort(column.sortField)
  }

  // Which edge the drop indicator goes on: the half of the cell the pointer is in. Dropping on
  // the right half means "put it after this column" (see dropOnColumn).
  function sideFor(event) {
    const box = event.currentTarget.getBoundingClientRect()
    return event.clientX - box.left < box.width / 2 ? 'before' : 'after'
  }

  function handleDragOver(event) {
    // Guarded on the ref, not on dragState: the first dragover can land before React has
    // re-rendered from the dragstart, and this is also what keeps the header from advertising
    // itself as a drop target for anything else being dragged across the page.
    if (!layout.dragKey.current) return
    // preventDefault is required on both dragenter and dragover, or the browser refuses the
    // drop and runs its own default (navigating to the dragged text) on release.
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
    layout.updateDragOver(column.key, sideFor(event))
  }

  const dropClass = dropSide ? ` column-drop-${dropSide}` : ''

  return (
    <th
      className={`ticket-col-header th-${column.key}${sortable ? ' sortable-header' : ''}${dragging ? ' column-dragging' : ''}${dropClass}`}
      data-column-key={column.key}
      draggable
      onDragStart={(event) => {
        // A press that started on the resize handle is a resize, not a reorder.
        if (layout.resizing.current) {
          event.preventDefault()
          return
        }
        event.dataTransfer.effectAllowed = 'move'
        // Firefox ignores a drag with no payload; the key itself is the natural one.
        event.dataTransfer.setData('text/plain', column.key)
        layout.beginDrag(column.key)
      }}
      onDragOver={handleDragOver}
      onDragEnter={handleDragOver}
      onDrop={(event) => {
        event.preventDefault()
        layout.dropOnColumn(column.key, sideFor(event))
      }}
      onDragEnd={layout.endDrag}
      onClick={handleClick}
      onContextMenu={(event) => {
        event.preventDefault()
        onOpenMenu({ key: column.key, x: event.clientX, y: event.clientY })
      }}
      title={`${sortable ? `Sort by ${column.label} · ` : ''}drag to move · right-click for more`}
    >
      {column.label}
      {sortable && <SortIcon direction={active ? sort.dir : null} />}
      {extra}
      <span
        className="column-resize-handle"
        title="Drag to resize · double-click to reset"
        onPointerDown={(event) => layout.startResize(event, column.key)}
        onClick={(event) => event.stopPropagation()}
        onDoubleClick={(event) => {
          event.stopPropagation()
          layout.resetColumnWidth(column.key)
        }}
      />
    </th>
  )
}

// Right-click menu for reordering. Deliberately offers both single-step nudges and a direct
// "move before <column>" list — stepping a column across 8 others one click at a time is the
// kind of thing that makes a reorderable table not worth using.
export function ColumnContextMenu({ menu, columns, layout, onClose }) {
  const menuRef = useRef(null)
  const [position, setPosition] = useState({ left: menu.x, top: menu.y })

  useEffect(() => {
    function handleOutside(event) {
      if (!menuRef.current?.contains(event.target)) onClose()
    }
    function handleKey(event) {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', handleOutside)
    document.addEventListener('contextmenu', handleOutside)
    window.addEventListener('keydown', handleKey)
    window.addEventListener('resize', onClose)
    return () => {
      document.removeEventListener('mousedown', handleOutside)
      document.removeEventListener('contextmenu', handleOutside)
      window.removeEventListener('keydown', handleKey)
      window.removeEventListener('resize', onClose)
    }
  }, [onClose])

  // Keeps the menu on screen when it's opened near the right/bottom edge — measured after
  // layout, before paint, so it never visibly jumps from the raw click point.
  useLayoutEffect(() => {
    const node = menuRef.current
    if (!node) return
    const { width, height } = node.getBoundingClientRect()
    setPosition({
      left: Math.max(4, Math.min(menu.x, window.innerWidth - width - 4)),
      top: Math.max(4, Math.min(menu.y, window.innerHeight - height - 4)),
    })
  }, [menu.x, menu.y])

  const index = columns.findIndex((c) => c.key === menu.key)
  if (index < 0) return null
  const column = columns[index]
  const others = columns.filter((c) => c.key !== menu.key)

  function run(action) {
    action()
    onClose()
  }

  return (
    <div ref={menuRef} className="column-menu" style={{ left: position.left, top: position.top }}>
      <div className="column-menu-title">{column.label || 'Column'}</div>
      <button
        type="button"
        className="column-menu-item"
        disabled={index === 0}
        onClick={() => run(() => layout.nudgeColumn(menu.key, -1))}
      >
        ← Move left
      </button>
      <button
        type="button"
        className="column-menu-item"
        disabled={index === columns.length - 1}
        onClick={() => run(() => layout.nudgeColumn(menu.key, 1))}
      >
        → Move right
      </button>
      <div className="column-menu-separator" />
      <div className="column-menu-title">Move before</div>
      {others.map((other) => (
        <button
          key={other.key}
          type="button"
          className="column-menu-item"
          onClick={() => run(() => layout.moveColumnBefore(menu.key, other.key))}
        >
          {other.label}
        </button>
      ))}
      <button
        type="button"
        className="column-menu-item"
        onClick={() => run(() => layout.moveColumnBefore(menu.key, null))}
      >
        (last column)
      </button>
      <div className="column-menu-separator" />
      <button type="button" className="column-menu-item" onClick={() => run(layout.resetLayout)}>
        Reset widths &amp; order
      </button>
    </div>
  )
}
