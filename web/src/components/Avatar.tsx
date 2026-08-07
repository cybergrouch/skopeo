import * as React from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * Preset avatar sizes, mirroring the inline avatars they replace:
 * - `sm` — 36px (match history, admin/research lists)
 * - `md` — 48px (own-profile header)
 * - `lg` — 56px (public profile header)
 */
export type AvatarSize = 'sm' | 'md' | 'lg'

const SIZE_CLASSES: Record<AvatarSize, string> = {
  sm: 'h-9 w-9 text-sm',
  md: 'h-12 w-12 text-lg',
  lg: 'h-14 w-14 text-xl',
}

export interface AvatarProps {
  /** The remote image (Google/Facebook photo). When null/blank, the initials fallback renders. */
  photoUrl?: string | null
  /** Display name; its first letter is the initials fallback, and it seeds the enlarge alt/label. */
  name?: string | null
  /** Preset size (default `sm`). */
  size?: AvatarSize
  /** Extra classes merged onto the avatar root (e.g. `shrink-0` is already applied). */
  className?: string
  /**
   * When true and a real image is present, clicking the avatar opens an enlarged lightbox (#697).
   * Initials-only avatars are never interactive regardless of this flag. Defaults to false so the
   * behaviour is explicit at each call site (and stays off where the avatar sits inside a link).
   */
  enlargeable?: boolean
}

/** First letter of the name (or "P" for an unnamed player), upper-cased — matches the old inline fallback. */
function initial(name: string | null | undefined): string {
  return (name ?? 'P').charAt(0).toUpperCase()
}

/** The non-interactive initials circle shown when there is no usable image. */
function InitialsFallback({
  name,
  size,
  className,
}: {
  name: string | null | undefined
  size: AvatarSize
  className?: string
}) {
  return (
    <div
      aria-hidden="true"
      className={cn(
        'flex shrink-0 items-center justify-center rounded-full bg-muted font-medium text-muted-foreground',
        SIZE_CLASSES[size],
        className,
      )}
    >
      {initial(name)}
    </div>
  )
}

/**
 * A player avatar: the remote photo when present, else an initials circle — visually identical to the
 * inline `<img …rounded-full>`/initials pattern it replaces. If the remote image fails to load it falls
 * back to initials (#697). When {@link AvatarProps.enlargeable} and a real image exists, the avatar is a
 * focusable button that opens an enlarged lightbox (backdrop / ✕ / Esc all dismiss). Initials-only
 * avatars render as a plain, non-interactive element.
 */
export function Avatar({
  photoUrl,
  name,
  size = 'sm',
  className,
  enlargeable = false,
}: AvatarProps) {
  // Track the exact src that failed to load rather than a boolean, so a changed photoUrl (e.g. a
  // reused Avatar in a list) is retried automatically without a state-resetting effect.
  const [failedSrc, setFailedSrc] = React.useState<string | null>(null)

  const hasImage = Boolean(photoUrl) && photoUrl !== failedSrc

  if (!hasImage) {
    return <InitialsFallback name={name} size={size} className={className} />
  }

  const displayName = name ?? 'Player'
  const alt = `${displayName}'s profile picture`
  const image = (
    <img
      src={photoUrl as string}
      alt={alt}
      referrerPolicy="no-referrer"
      onError={() => setFailedSrc(photoUrl as string)}
      className={cn('shrink-0 rounded-full object-cover', SIZE_CLASSES[size], className)}
    />
  )

  if (!enlargeable) {
    return image
  }

  return (
    <Dialog.Root>
      <Dialog.Trigger asChild>
        <button
          type="button"
          aria-label={`View ${displayName}'s profile picture`}
          className="shrink-0 rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        >
          {image}
        </button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/70 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <Dialog.Content
          // No descriptive body beyond the image itself; suppress Radix's missing-description warning.
          aria-describedby={undefined}
          className="fixed left-1/2 top-1/2 z-50 flex max-h-[90vh] max-w-[90vw] -translate-x-1/2 -translate-y-1/2 flex-col items-center focus:outline-none"
        >
          <Dialog.Title className="sr-only">{alt}</Dialog.Title>
          <img
            src={photoUrl as string}
            alt={alt}
            referrerPolicy="no-referrer"
            className="max-h-[90vh] max-w-[90vw] rounded-lg object-contain shadow-lg"
          />
          <Dialog.Close
            aria-label="Close"
            className="absolute right-2 top-2 rounded-full bg-black/60 p-1.5 text-white opacity-90 transition-opacity hover:opacity-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-white"
          >
            <X className="size-5" />
          </Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
