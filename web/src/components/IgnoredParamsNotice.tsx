import { plural } from '@/lib/plural'

/**
 * Says which URL view-state params were unusable (#1056), so a hand-edited, stale or truncated link
 * explains itself instead of quietly showing something the sender did not send.
 *
 * `useUrlViewState` already falls back to each field's default, so the view is complete and correct;
 * what would otherwise be missing is any hint that `?page=nope` was in the link at all. The Research
 * tab (#1054) carries its own filter-specific wording; this is the generic form for the public pages,
 * whose params are pages and bands rather than search filters.
 *
 * Renders nothing when there is nothing to report, so callers can pass `ignored` unconditionally.
 */
export function IgnoredParamsNotice({ ignored }: { ignored: string[] }) {
  if (ignored.length === 0) return null
  return (
    <p className="text-sm text-destructive" role="alert">
      This link had {ignored.length} unreadable view setting{plural(ignored.length)} (
      {ignored.join(', ')}), so {ignored.length === 1 ? 'it was' : 'they were'} ignored and the
      default is shown instead.
    </p>
  )
}
