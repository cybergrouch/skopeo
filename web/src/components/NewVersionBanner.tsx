import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { useNewVersionAvailable } from '@/hooks/useNewVersionAvailable'

/**
 * Offers a reload once a newer bundle has been deployed (#752).
 *
 * Deliberately an offer, not an act. Reloading on the user's behalf would discard whatever they were
 * typing — a half-filled fixture form, a rename in progress — to fix a problem they may not have. The
 * banner sits at the bottom so it never covers a page header, and it is dismissible: someone
 * mid-entry can finish first and reload when they choose.
 *
 * Why it matters beyond tidiness: a stale bundle renders options the server has since started
 * rejecting (see the award-ranking-points flag in #752), so the user sees a control that cannot work.
 */
export function NewVersionBanner() {
  const available = useNewVersionAvailable()
  const [dismissed, setDismissed] = useState(false)
  if (!available || dismissed) return null

  return (
    // Bottom safe-area inset (#1076): this is pinned to `bottom-0`, and its Reload / Not now
    // buttons would otherwise sit in the iOS home-indicator (or Android gesture-pill) band, where a
    // tap is claimed by the OS swipe. `max()` floors at the old 12px.
    <div
      role="status"
      className="fixed inset-x-0 bottom-0 z-50 flex flex-wrap items-center justify-center gap-3 border-t bg-background/95 px-4 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3 text-sm shadow-lg backdrop-blur"
    >
      <span>A new version of Skopeo is available.</span>
      <span className="flex items-center gap-2">
        <Button type="button" size="sm" onClick={() => window.location.reload()}>
          Reload
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={() => setDismissed(true)}>
          Not now
        </Button>
      </span>
    </div>
  )
}
