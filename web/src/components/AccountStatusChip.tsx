import { cn } from '@/lib/utils'

/**
 * The account's lifecycle state as a chip, for a table column that needs a value in every row (#1050).
 *
 * Distinct from [PlaceholderTag], which is the *inline* cue rendered beside a name anywhere a player
 * appears and which shows nothing for a normal account. This one always renders something, and it
 * separates **Merged** from **Deleted** — a distinction `isDeleted` cannot make, since merging and
 * deleting both clear `is_active`.
 *
 * Colours and hover text match PlaceholderTag for the two states they share, so a reader who learned
 * "amber means unclaimed" on a match roster reads the same thing here.
 */
const STATUS_STYLES: Record<string, { label: string; className: string; title: string }> = {
  ACTIVE: {
    label: 'Active',
    className: 'border-border bg-muted text-muted-foreground',
    title: 'A normal, claimed account in good standing.',
  },
  UNCLAIMED: {
    label: 'Unclaimed',
    className:
      'border-amber-500/50 bg-amber-500/10 text-amber-700 dark:text-amber-400',
    title: 'This player was created without a login and has not been claimed yet.',
  },
  DELETED: {
    label: 'Deleted',
    className: 'border-red-500/50 bg-red-500/10 text-red-700 dark:text-red-400',
    title:
      'This account was deleted by an administrator. Its history is retained; it cannot be used in new events.',
  },
  MERGED: {
    label: 'Merged',
    className: 'border-sky-500/50 bg-sky-500/10 text-sky-700 dark:text-sky-400',
    title:
      'This account was merged into another one. Its records were consolidated there; the merge is irreversible.',
  },
}

export function AccountStatusChip({
  status,
  className,
}: {
  status: string | null | undefined
  className?: string
}) {
  if (!status) return null
  // An unrecognised value is shown verbatim rather than swallowed: a new server-side state should be
  // visible as soon as it ships, not render as a blank cell until someone remembers to add it here.
  const style = STATUS_STYLES[status]
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full border px-1.5 py-0.5 align-middle text-[0.65rem] font-medium leading-none',
        style?.className ?? 'border-border bg-muted text-muted-foreground',
        className,
      )}
      title={style?.title}
    >
      {style?.label ?? status}
    </span>
  )
}
