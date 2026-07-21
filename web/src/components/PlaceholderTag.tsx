import { cn } from '@/lib/utils'

/**
 * A compact "Unclaimed" chip (#505) shown beside a placeholder ("dummy") player's name or
 * `/players/:code` link, wherever one is rendered (match rosters, standings, head-to-head, event
 * participants, seeding, points/activity, match history, pickers). It reuses the amber treatment of
 * the public profile's placeholder indicator (#496 — "Unclaimed placeholder account") so the cue is
 * identical everywhere. A real/claimed player renders nothing.
 *
 * Usage: `<PlaceholderTag show={player.isPlaceholder} />` next to the name, so the tag is always
 * driven off the DTO's `isPlaceholder` flag and never appears for a normal player.
 */
export function PlaceholderTag({
  show,
  className,
}: {
  show: boolean | null | undefined
  className?: string
}) {
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
