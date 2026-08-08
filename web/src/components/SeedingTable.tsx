import { useState } from 'react'
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { GripVertical } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { seedingCsv } from '@/lib/seedingCsv'
import type { SeedingEntryResponse } from '@/api/generated/model'

/** Strip characters that are awkward in filenames; keep it deterministic from the source name. */
function sanitizeFilename(name: string): string {
  return name.trim().replace(/[^a-zA-Z0-9-_]+/g, '-').replace(/^-+|-+$/g, '') || 'list'
}

/** The stable drag id for a row: the user id, falling back to the always-present public code. */
function rowId(entry: SeedingEntryResponse): string {
  return entry.userId ?? entry.publicCode
}

/** A signature of the server-provided order, used to resync the local draft after a save/regenerate. */
function orderKey(entries: SeedingEntryResponse[], generatedAt: string): string {
  return `${generatedAt}|${entries.map(rowId).join(',')}`
}

/**
 * One rendered seeding row. When [editable] a grip handle (keyboard-accessible drag listeners) sits in
 * the Seed cell — kept in that first cell rather than a separate column so the table shape (and the CSV)
 * is unchanged. [displaySeed] is the number shown in the Seed cell: while editing it tracks the row's
 * current draft position (so a dragged row renumbers live, #733), else it's the server's stored seed.
 */
function SeedingRow({
  entry,
  editable,
  displaySeed,
}: {
  entry: SeedingEntryResponse
  editable: boolean
  displaySeed: number | null
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: rowId(entry),
    disabled: !editable,
  })
  const style = editable
    ? { transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.6 : 1 }
    : undefined
  return (
    <tr ref={setNodeRef} style={style} className="border-b">
      <td className="p-2">
        <span className="flex items-center gap-1">
          {editable ? (
            <button
              type="button"
              aria-label={`Reorder ${entry.displayName ?? entry.publicCode}`}
              className="cursor-grab touch-none rounded p-1 text-muted-foreground hover:bg-muted"
              {...attributes}
              {...listeners}
            >
              <GripVertical className="h-4 w-4" />
            </button>
          ) : null}
          {displaySeed ?? ''}
        </span>
      </td>
      <td className="p-2">{entry.displayName ?? entry.publicCode}</td>
      <td className="p-2">{entry.publicCode}</td>
      <td className="p-2">{entry.ntrpBand ?? ''}</td>
      <td className="p-2">{entry.rating}</td>
      <td className="p-2">{entry.sex ?? ''}</td>
      <td className="p-2">{entry.age ?? ''}</td>
    </tr>
  )
}

/**
 * The generated-seeding table + CSV export (#111/#714), shared by the Seeding tab (player lists) and
 * the Event Organizer (event participants). The seeding is computed and rating-sorted server-side.
 *
 * When [onSaveOrder] is provided the host can drag-and-drop (keyboard-accessible) to reorder the rows as
 * a local draft (#718); Save persists the new order (renumbering seeds 1..N by position) and Reset reverts
 * to the last-saved order (disabled when there are no unsaved changes). When [onRegenerate] is provided a
 * Regenerate button recomputes the deterministic order — warning first when a manually-edited order
 * (from [manuallyEdited]) would be discarded.
 */
export function SeedingTable({
  entries,
  generatedAt,
  name,
  emptyMessage = 'No seeding yet.',
  onSaveOrder,
  savingOrder = false,
  onRegenerate,
  regenerating = false,
  manuallyEdited = false,
}: {
  entries: SeedingEntryResponse[]
  generatedAt: string
  /** Source name used for the downloaded CSV filename (list name or event name). */
  name: string
  emptyMessage?: string
  /** When provided, enables drag-reorder editing; called with the new order's user ids on Save (#718). */
  onSaveOrder?: (userIds: string[]) => Promise<unknown> | void
  savingOrder?: boolean
  /** When provided, renders a Regenerate control that recomputes the deterministic order (#718). */
  onRegenerate?: () => void
  regenerating?: boolean
  /** Whether the current server seeding was hand-edited; drives the Regenerate discard warning (#718). */
  manuallyEdited?: boolean
}) {
  const editable = onSaveOrder != null
  const [draft, setDraft] = useState<SeedingEntryResponse[]>(entries)
  const [confirmingRegen, setConfirmingRegen] = useState(false)
  // Resync the draft whenever the server order changes (after a save/regenerate refetch), but never during
  // a drag: key off the order's content, not the array reference (props churn each render). Adjusting state
  // during render (React's documented "reset state on prop change" pattern) avoids a cascading effect.
  const key = orderKey(entries, generatedAt)
  const [syncedKey, setSyncedKey] = useState(key)
  if (key !== syncedKey) {
    setSyncedKey(key)
    setDraft(entries)
    setConfirmingRegen(false)
  }

  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const hasSeeding = entries.length > 0
  // Rows to render/export: the draft when editing (so the visible order + CSV reflect the pending
  // reorder), else the server rows. While editing, the Seed cell shows each row's live draft position
  // (#733) — a preview of the 1..N renumber Save applies — instead of the row's stale stored seed.
  const rows = editable ? draft : entries
  const dirty = editable && draft.map(rowId).join(',') !== entries.map(rowId).join(',')

  function onDownloadCsv() {
    const csv = seedingCsv(rows)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${sanitizeFilename(name)}-seeding-${generatedAt}.csv`
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    URL.revokeObjectURL(url)
  }

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event
    if (!over || active.id === over.id) return
    setDraft((prev) => {
      const ids = prev.map(rowId)
      return arrayMove(prev, ids.indexOf(active.id as string), ids.indexOf(over.id as string))
    })
  }

  async function onSave() {
    if (!onSaveOrder) return
    const userIds = draft.map((e) => e.userId).filter((id): id is string => id != null)
    await onSaveOrder(userIds)
  }

  function onRegenerateClick() {
    if (!onRegenerate) return
    // Only a manually-edited order is worth confirming — regenerating over a plain generated one is a no-op risk.
    if (manuallyEdited && !confirmingRegen) {
      setConfirmingRegen(true)
      return
    }
    setConfirmingRegen(false)
    onRegenerate()
  }

  if (!hasSeeding) {
    return <p className="text-sm text-muted-foreground">{emptyMessage}</p>
  }

  const ids = rows.map(rowId)

  const table = (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="p-2">Seed</th>
            <th className="p-2">Name</th>
            <th className="p-2">Code</th>
            <th className="p-2">NTRP</th>
            <th className="p-2">Rating</th>
            <th className="p-2">Sex</th>
            <th className="p-2">Age</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((entry, index) => (
            <SeedingRow
              key={rowId(entry)}
              entry={entry}
              editable={editable}
              displaySeed={editable ? index + 1 : entry.seed ?? null}
            />
          ))}
        </tbody>
      </table>
    </div>
  )

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" onClick={onDownloadCsv}>
          Download CSV
        </Button>
        {editable ? (
          <>
            <Button type="button" onClick={onSave} disabled={!dirty || savingOrder}>
              {savingOrder ? 'Saving…' : 'Save order'}
            </Button>
            <Button
              type="button"
              variant="ghost"
              onClick={() => setDraft(entries)}
              disabled={!dirty || savingOrder}
            >
              Reset
            </Button>
          </>
        ) : null}
        {onRegenerate ? (
          <Button
            type="button"
            variant={confirmingRegen ? 'default' : 'outline'}
            onClick={onRegenerateClick}
            disabled={regenerating}
          >
            {regenerating
              ? 'Regenerating…'
              : confirmingRegen
                ? 'Discard manual order and regenerate'
                : 'Regenerate seeding'}
          </Button>
        ) : null}
        {confirmingRegen ? (
          <Button type="button" variant="ghost" onClick={() => setConfirmingRegen(false)}>
            Cancel
          </Button>
        ) : null}
      </div>
      {confirmingRegen ? (
        <p className="text-sm text-destructive" role="alert">
          This seeding was reordered by hand. Regenerating will discard the manual order and recompute it
          from ratings.
        </p>
      ) : null}
      {editable ? (
        <p className="text-xs text-muted-foreground">
          Drag the handle (or focus it and use the arrow keys) to reorder, then Save. Saving renumbers the
          seeds 1..N by their new position.
        </p>
      ) : null}
      {editable ? (
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <SortableContext items={ids} strategy={verticalListSortingStrategy}>
            {table}
          </SortableContext>
        </DndContext>
      ) : (
        table
      )}
    </div>
  )
}
