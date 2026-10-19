import { useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { getGetApiV1ThemeQueryKey, usePutApiV1ThemeSchedule } from '@/api/generated/settings/settings'
import type { ThemeScheduleRow } from '@/api/generated/model'
import { serverMessage } from '@/observability/serverMessage'
import { toastError } from '@/observability/toastError'
import {
  DEFAULT_SCHEDULE,
  MONTH_NAMES,
  checkSchedule,
  findRowIndex,
  formatDate,
  formatSpan,
  nextChange,
  partsOf,
  sortByStart,
  type ScheduleDate,
  type ScheduleRow,
} from '@/lib/themeSchedule'
import { THEME_OPTIONS, themeLabel } from './themeOptions'

/** A schedule row plus a stable key, so React keeps each row's inputs attached as rows move. */
interface EditorRow extends ScheduleRow {
  id: string
}

const ROW_THEMES = THEME_OPTIONS.filter((option) => option.value !== 'AUTO')
const DAYS = Array.from({ length: 31 }, (_, i) => i + 1)
const SELECT_CLASS = 'h-9 rounded-md border bg-transparent px-2 py-1 text-sm'

/** "Row 4 'Clay'", or "Row 4" while it has no name. */
function rowLabel(rows: readonly ScheduleRow[], index: number): string {
  const name = rows[index].name.trim()
  return name === '' ? `Row ${index + 1}` : `Row ${index + 1} '${name}'`
}

/** One month + day pair, as two selects; an impossible pick (Apr 31) is reported by the live check. */
function DatePicker({
  label,
  idPrefix,
  value,
  onChange,
}: {
  label: string
  idPrefix: string
  value: ScheduleDate
  onChange: (value: ScheduleDate) => void
}) {
  return (
    <div className="space-y-1">
      <span className="text-xs">{label}</span>
      <div className="flex gap-2">
        <select
          aria-label={`${idPrefix} month`}
          className={SELECT_CLASS}
          value={value.month}
          onChange={(e) => onChange({ ...value, month: Number(e.target.value) })}
        >
          {MONTH_NAMES.map((name, i) => (
            <option key={name} value={i + 1}>
              {name}
            </option>
          ))}
        </select>
        <select
          aria-label={`${idPrefix} day`}
          className={SELECT_CLASS}
          value={value.day}
          onChange={(e) => onChange({ ...value, day: Number(e.target.value) })}
        >
          {DAYS.map((day) => (
            <option key={day} value={day}>
              {day}
            </option>
          ))}
        </select>
      </div>
    </div>
  )
}

/**
 * The AUTO season schedule editor (#1196), inside the UI Theme card. One block per row, stacked
 * vertically so it works on a phone (#1156): name, theme, start and end, with move up/down and remove.
 * Below: add, Sort by start date and Reset to default. Every rule the server enforces is checked live —
 * overlaps and duplicate names (and any incomplete row) show on the rows involved and disable Save;
 * uncovered dates are a warning only, since they simply show Skopeo OG. A preview shows what today
 * resolves to and when that next changes, from the schedule as edited.
 */
export function ThemeScheduleEditor({
  schedule,
  loading,
  now = new Date(),
}: {
  /** The saved schedule; undefined while loading (or if the read failed) → the built-in default. */
  schedule: ThemeScheduleRow[] | undefined
  loading: boolean
  now?: Date
}) {
  const queryClient = useQueryClient()
  const nextId = useRef(0)
  const freshId = () => {
    nextId.current += 1
    return `new-${nextId.current}`
  }

  // Null until the admin edits; until then the editor mirrors the saved schedule, so a background
  // refetch can't be clobbered by — or clobber — an edit in progress.
  const [draft, setDraft] = useState<EditorRow[] | null>(null)
  const saved = useMemo(
    () => (schedule ?? DEFAULT_SCHEDULE).map((row, i) => ({ ...row, id: `saved-${i}` })),
    [schedule],
  )
  const rows = draft ?? saved

  const check = useMemo(() => checkSchedule(rows), [rows])
  const messagesByRow = useMemo(() => {
    const byRow = new Map<number, string[]>()
    for (const problem of check.problems) {
      for (const row of problem.rows) byRow.set(row, [...(byRow.get(row) ?? []), problem.message])
    }
    return byRow
  }, [check])
  const hasErrors = check.problems.length > 0

  const save = usePutApiV1ThemeSchedule({
    mutation: {
      onSuccess: () => {
        toast.success('Schedule saved')
        setDraft(null)
        queryClient.invalidateQueries({ queryKey: getGetApiV1ThemeQueryKey() })
      },
      onError: (error) =>
        toastError(serverMessage(error) ?? 'Could not save the schedule. Try again.', {
          cause: error,
          duration: 8000,
        }),
    },
  })

  const edit = (next: EditorRow[]) => setDraft(next)
  const update = (index: number, patch: Partial<ScheduleRow>) =>
    edit(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)))
  const move = (index: number, by: number) => {
    const next = [...rows]
    ;[next[index], next[index + by]] = [next[index + by], next[index]]
    edit(next)
  }
  const add = () => {
    // A new row starts on the first uncovered range when there is one, so it lands where it's needed.
    const gap = check.gaps[0]
    const span = gap ?? { start: { month: 1, day: 1 }, end: { month: 1, day: 1 } }
    edit([...rows, { id: freshId(), name: '', theme: '', start: span.start, end: span.end }])
  }

  const onSave = () => {
    save.mutate({
      data: {
        schedule: rows.map((row) => ({
          name: row.name.trim(),
          theme: row.theme,
          start: row.start,
          end: row.end,
        })),
      },
    })
  }

  // The preview reads the schedule as edited, so the admin sees the effect before saving.
  const todayIndex = findRowIndex(rows, now)
  const change = nextChange(rows, now)
  const describe = (index: number) =>
    index >= 0
      ? `${themeLabel(rows[index].theme)} (${rowLabel(rows, index).replace('Row', 'row')})`
      : 'Skopeo OG (uncovered)'

  return (
    <section className="space-y-3" aria-label="AUTO season schedule">
      <div>
        <h3 className="text-sm font-medium">Auto season schedule</h3>
        <p className="text-xs text-muted-foreground">
          Rows are checked top to bottom and the first one containing a date wins. Ranges are inclusive and
          repeat every year; an end before the start runs past Dec 31. Dates no row covers show Skopeo OG.
        </p>
      </div>

      <ol className="space-y-3">
        {rows.map((row, index) => {
          const messages = messagesByRow.get(index + 1) ?? []
          const prefix = `Row ${index + 1}`
          return (
            <li
              key={row.id}
              className={`space-y-2 rounded-md border p-3 ${messages.length > 0 ? 'border-destructive' : ''}`}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-medium">{prefix}</span>
                <div className="flex gap-1">
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    aria-label={`Move ${prefix} up`}
                    disabled={index === 0}
                    onClick={() => move(index, -1)}
                  >
                    <ArrowUp className="h-4 w-4" />
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    aria-label={`Move ${prefix} down`}
                    disabled={index === rows.length - 1}
                    onClick={() => move(index, 1)}
                  >
                    <ArrowDown className="h-4 w-4" />
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    aria-label={`Remove ${prefix}`}
                    onClick={() => edit(rows.filter((_, i) => i !== index))}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>
              <div className="space-y-1">
                <Label htmlFor={`${row.id}-name`} className="text-xs">
                  Name
                </Label>
                <Input
                  id={`${row.id}-name`}
                  aria-label={`${prefix} name`}
                  value={row.name}
                  onChange={(e) => update(index, { name: e.target.value })}
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor={`${row.id}-theme`} className="text-xs">
                  Season theme
                </Label>
                <select
                  id={`${row.id}-theme`}
                  aria-label={`${prefix} theme`}
                  className={`${SELECT_CLASS} w-full`}
                  value={row.theme}
                  onChange={(e) => update(index, { theme: e.target.value })}
                >
                  <option value="">Choose a theme</option>
                  {ROW_THEMES.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex flex-wrap gap-4">
                <DatePicker
                  label="Starts"
                  idPrefix={`${prefix} start`}
                  value={row.start}
                  onChange={(start) => update(index, { start })}
                />
                <DatePicker
                  label="Ends"
                  idPrefix={`${prefix} end`}
                  value={row.end}
                  onChange={(end) => update(index, { end })}
                />
              </div>
              {messages.map((message) => (
                <p key={message} className="text-xs text-destructive">
                  {message}
                </p>
              ))}
            </li>
          )
        })}
      </ol>

      <div className="flex flex-wrap gap-2">
        <Button type="button" size="sm" variant="outline" onClick={add}>
          Add row
        </Button>
        <Button type="button" size="sm" variant="outline" onClick={() => edit(sortByStart(rows))}>
          Sort by start date
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => edit(DEFAULT_SCHEDULE.map((row) => ({ ...row, id: freshId() })))}
        >
          Reset to default
        </Button>
      </div>

      {check.gaps.length > 0 && (
        <div role="status" className="rounded-md border border-amber-500/50 bg-amber-500/10 p-2 text-xs">
          Not covered: {check.gaps.map(formatSpan).join(', ')} (these dates will show Skopeo OG). You can
          still save.
        </div>
      )}

      <div className="rounded-md border p-2 text-xs" aria-label="Schedule preview">
        <p>
          Today ({formatDate(partsOf(now))}): {describe(todayIndex)}
        </p>
        <p>
          {change === null
            ? 'Next change: none within a year.'
            : `Next change: ${formatDate(partsOf(change.date))} → ${describe(change.index)}`}
        </p>
      </div>

      <div className="flex items-center gap-2">
        <Button
          type="button"
          size="sm"
          onClick={onSave}
          disabled={loading || draft === null || hasErrors || save.isPending}
        >
          Save schedule
        </Button>
        {hasErrors && <span className="text-xs text-destructive">Fix the highlighted rows to save.</span>}
      </div>
    </section>
  )
}
