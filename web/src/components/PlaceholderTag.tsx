import { cn } from '@/lib/utils'

/**
 * A compact status chip shown beside a player's name or `/players/:code` link, wherever one is
 * rendered (match rosters, standings, head-to-head, event participants, seeding, points/activity,
 * match history, pickers).
 *
 * Two states, driven off the DTO's flags:
 *   - **Deleted** (#518): an admin soft-deleted the account (`isDeleted`). A dominant red chip — the
 *     account is gone but retained in historical data, so the cue must be unmistakable.
 *   - **Unclaimed** (#505/#496): a login-less placeholder ("dummy") player (`isPlaceholder`). An amber
 *     chip reusing the public profile's placeholder indicator so the cue is identical everywhere.
 *
 * If an account is somehow both, **Deleted dominates** (only the Deleted chip shows). A normal,
 * active, claimed player renders nothing.
 *
 * Usage: `<PlaceholderTag show={player.isPlaceholder} deleted={player.isDeleted} />` next to the name.
 */
export function PlaceholderTag({
  show,
  deleted,
  className,
}: {
  show: boolean | null | undefined
  deleted?: boolean | null | undefined
  className?: string
}) {
  // Deleted dominates the placeholder ("Unclaimed") state (#518).
  if (deleted) {
    return (
      <span
        className={cn(
          'ml-1.5 inline-flex items-center rounded-full border border-red-500/50 bg-red-500/10 px-1.5 py-0.5 align-middle text-[0.65rem] font-medium leading-none text-red-700 dark:text-red-400',
          className,
        )}
        title="This account was deleted by an administrator. Its history is retained; it cannot be used in new events."
      >
        Deleted
      </span>
    )
  }
  if (!show) return null
  return (
    <span
      className={cn(
        'ml-1.5 inline-flex items-center rounded-full border border-amber-500/50 bg-amber-500/10 px-1.5 py-0.5 align-middle text-[0.65rem] font-medium leading-none text-amber-700 dark:text-amber-400',
        className,
      )}
      title="This player was created without a login and has not been claimed yet."
    >
      Unclaimed
    </span>
  )
}
